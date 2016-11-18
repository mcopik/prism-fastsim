//==============================================================================
//	
//	Copyright (c) 2002-
//	Authors:
//	* Marcin Copik <mcopik@gmail.com> (RWTH Aachen, formerly Silesian University of Technology)
//	
//------------------------------------------------------------------------------
//	
//	This file is part of PRISM.
//	
//	PRISM is free software; you can redistribute it and/or modify
//	it under the terms of the GNU General Public License as published by
//	the Free Software Foundation; either version 2 of the License, or
//	(at your option) any later version.
//	
//	PRISM is distributed in the hope that it will be useful,
//	but WITHOUT ANY WARRANTY; without even the implied warranty of
//	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//	GNU General Public License for more details.
//	
//	You should have received a copy of the GNU General Public License
//	along with PRISM; if not, write to the Free Software Foundation,
//	Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//	
//==============================================================================
package simulator.opencl.kernel;

import static simulator.opencl.kernel.expression.ExpressionGenerator.addParentheses;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createBinaryExpression;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createAssignment;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createConditionalAssignment;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createNegation;
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;
import static simulator.opencl.kernel.expression.ExpressionGenerator.standardFunctionCall;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import prism.Preconditions;
import prism.PrismLangException;
import simulator.opencl.kernel.KernelGenerator.LocalVar;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.IfElse;
import simulator.opencl.kernel.expression.KernelComponent;
import simulator.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.opencl.kernel.expression.Method;
import simulator.opencl.kernel.expression.While;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.opencl.kernel.memory.StructureType;
import simulator.opencl.kernel.memory.StdVariableType.StdType;
import simulator.sampler.SamplerDouble;
import simulator.sampler.SamplerRewardCumulCont;
import simulator.sampler.SamplerRewardCumulDisc;
import simulator.sampler.SamplerRewardInstCont;
import simulator.sampler.SamplerRewardReach;

public class RewardGeneratorCTMC extends RewardGenerator
{
	/**
	 * Two additional args used by state reward functions.
	 * Keeps time of entering and leaving state.
	 */
	static final CLVariable PREVIOUS_TIME_ARG = new CLVariable(new StdVariableType(StdType.FLOAT), "previous_time");
	static final CLVariable NEW_TIME_ARG = new CLVariable(new StdVariableType(StdType.FLOAT), "time");
	
	/**
	 * Reuse this expression for all updates:
	 * (time - previous_time)
	 */
	static final Expression TIME_SPENT_STATE = addParentheses(createBinaryExpression(NEW_TIME_ARG.getSource(), Operator.SUB, PREVIOUS_TIME_ARG.getSource()));
	
	/**
	 * True iff there is at least one reward structure with cumulative reward.
	 */
	protected boolean computesCumulativeReward = false;
	
	public RewardGeneratorCTMC(KernelGenerator generator) throws KernelException, PrismLangException
	{
		super(generator);

		if(activeGenerator) {
			generateRewardCode();
		}
	}

	@Override
	protected boolean isInstantaneous(SamplerDouble sampler)
	{
		return sampler instanceof SamplerRewardInstCont;
	}

	@Override
	protected boolean isCumulative(SamplerDouble sampler)
	{
		return sampler instanceof SamplerRewardCumulCont;
	}
	
	@Override
	protected void initializeRewardRequiredVarsCumulative(Map<Class<? extends SamplerDouble>, String[]> map)
	{
		String[] vars = new String[] { REWARD_STRUCTURE_VAR_CUMULATIVE_TOTAL, REWARD_STRUCTURE_VAR_PREVIOUS_STATE, REWARD_STRUCTURE_VAR_PREVIOUS_TRANSITION,
				REWARD_STRUCTURE_VAR_CURRENT_STATE };
		map.put(SamplerRewardCumulCont.class, vars);
	}

	@Override
	protected void initializeRewardRequiredVarsInstantaneous(Map<Class<? extends SamplerDouble>, String[]> map)
	{
		String[] vars = new String[] { REWARD_STRUCTURE_VAR_PREVIOUS_STATE, REWARD_STRUCTURE_VAR_CURRENT_STATE };
		map.put(SamplerRewardInstCont.class, vars);
	}

	@Override
	protected void stateRewardFunctionAdditionalArgs(Method function, StructureType rewardStructure) throws KernelException
	{
		if ( rewardStructure.containsField(REWARD_STRUCTURE_VAR_CUMULATIVE_TOTAL) && 
				rewardStructure.containsField(REWARD_STRUCTURE_VAR_CURRENT_STATE)) {
			function.addArg(PREVIOUS_TIME_ARG);
			function.addArg(NEW_TIME_ARG);
			
			computesCumulativeReward = true;
		}
		/*else if (rewardStructure.containsField(REWARD_STRUCTURE_VAR_CURRENT_STATE)) {
			// for instanetous reward we need only current time
			function.addArg(PREVIOUS_TIME_ARG);
		}*/
	}

	@Override
	protected Expression stateRewardFunctionComputeCumulRw(Expression cumulReward, CLVariable stateReward, CLVariable transitionReward) throws KernelException
	{
		/**
		 * More complex update: add transition and state rewards, but the second one needs to be multiplied by time spent in state.
		 */
		Expression newValue = null;
		if (stateReward != null) {
			newValue = createBinaryExpression(stateReward.getSource(), Operator.MUL, TIME_SPENT_STATE);
			if(transitionReward != null) {
				newValue = createBinaryExpression(newValue, Operator.ADD, transitionReward.getSource());
			}
		} else {
			newValue = transitionReward.getSource();
		}
		return createBinaryExpression(cumulReward, Operator.ADD_AUGM, newValue);
	}
	
	@Override
	protected void createPropertyInst(IfElse ifElse, SamplerDouble property, CLVariable propertyVar, CLVariable rewardState)
	{
		/*
		 * If there's no state reward for a this reward structure - the reward will always be zero.
		 * 
		 * Very unlikely case (mostly a user error), but we want to be safe and avoid a nullptr exception.
		 */ 
		CLVariable curStateReward = rewardState.accessField(REWARD_STRUCTURE_VAR_CURRENT_STATE);
		CLVariable prevStateReward = rewardState.accessField(REWARD_STRUCTURE_VAR_PREVIOUS_STATE);
		CLVariable propertyState = propertyVar.accessField("propertyState");
		CLVariable valueKnown = propertyVar.accessField("valueKnown");
		
		/**
		 * If we have achieved the desired time, there are two possibilities:
		 * - we are above time - previous state reward
		 * - we hit exactly the time barrier (very unlikely) - current state reward
		 * 
		 * TODO: is a > b as fabs(a - b) > 1e-5 the best choice?
		 */
		
		/**
		 * Current time >= barrier
		 */
		Expression expectedTime = fromString( ((SamplerRewardInstCont) property).getTime() );
		Expression timeCondition = createBinaryExpression(NEW_TIME_ARG.getSource(), Operator.GE, expectedTime);
		IfElse timeReached = new IfElse(timeCondition);
		
		/**
		 * Current_time > barrier
		 */
		Expression differenceCall = standardFunctionCall("fabs", createBinaryExpression(NEW_TIME_ARG.getSource(), Operator.SUB, expectedTime) );
		Expression timeAboveCond = createBinaryExpression( differenceCall, Operator.GT, fromString(1e-7));
		IfElse timeAbove = new IfElse(timeAboveCond);
		timeAbove.addExpression( createAssignment(propertyState, prevStateReward != null ? prevStateReward.getSource() : fromString(0.0)) );
		timeAbove.addExpression(0, createAssignment(valueKnown, fromString("true")));

		/**
		 * Current_time == barrier
		 */
		timeAbove.addElse();
		timeAbove.addExpression(1, createAssignment(propertyState, curStateReward != null ? curStateReward.getSource() : fromString(0.0)) );
		timeAbove.addExpression(1, createAssignment(valueKnown, fromString("true")));
		timeReached.addExpression(timeAbove);
		
		ifElse.addExpression( timeReached );
	}
	
	@Override
	protected void createPropertyCumul(IfElse ifElse, SamplerDouble property, CLVariable propertyVar, CLVariable rewardState)
	{
		CLVariable cumulReward = rewardState.accessField(REWARD_STRUCTURE_VAR_CUMULATIVE_TOTAL);
		CLVariable prevStateReward = rewardState.accessField(REWARD_STRUCTURE_VAR_PREVIOUS_STATE);
		CLVariable prevTransReward = rewardState.accessField(REWARD_STRUCTURE_VAR_PREVIOUS_TRANSITION);
		Expression expectedTime = fromString( ((SamplerRewardCumulCont) property).getTime() );
		Expression timeCondition = createBinaryExpression(NEW_TIME_ARG.getSource(), Operator.GE, expectedTime);
		IfElse timeAboveBoundary = new IfElse(timeCondition);
		
		/**
		 * Update cumulative reward by subtracting state reward corresponding to excess time
		 * in state and transition reward, but only if there is an excess time spent in state.
		 */
		
		// excess_time = (time - time_bound) 
		Expression excess_time = addParentheses(
				createBinaryExpression(NEW_TIME_ARG.getSource(), Operator.SUB, expectedTime)
		);
		Expression updatedReward = cumulReward.getSource();
		// subtract state reward multiplied by excess time
		if (prevStateReward != null) {
			Expression stateRewardUpdate = createBinaryExpression(prevStateReward.getSource(), Operator.MUL, excess_time);
			updatedReward = createBinaryExpression(
					updatedReward,
					Operator.SUB,
					stateRewardUpdate
					);
		}
		// subtract transition reward iff excess time > 0
		if (prevTransReward != null) {
			Expression transitionRewardUpdate = createConditionalAssignment(
					createBinaryExpression(excess_time, Operator.GT, fromString(0.0f)),
					prevTransReward.getSource(),
					fromString(0.0f)
					);
			updatedReward = createBinaryExpression(
					updatedReward,
					Operator.SUB,
					addParentheses(transitionRewardUpdate)
					);
		}

		/**
		 * Write the value of property
		 */
		CLVariable propertyState = propertyVar.accessField("propertyState");
		CLVariable valueKnown = propertyVar.accessField("valueKnown");
		timeAboveBoundary.addExpression(0, createAssignment(propertyState, updatedReward) );
		timeAboveBoundary.addExpression(0, createAssignment(valueKnown, fromString("true")));
		
		ifElse.addExpression( timeAboveBoundary );
	}
	
	@Override
	public boolean needsTimeDifference()
	{
		return computesCumulativeReward;
	}
	
	@Override
	protected Expression callStateRewardFunction(Method method, CLVariable stateVector, CLVariable rewardStructure)
	{
		/**
		 * Cumulative reward with current state? Requires both current and new time.
		 */
		if (rewardStructure.accessField(REWARD_STRUCTURE_VAR_CUMULATIVE_TOTAL) != null
				&& rewardStructure.accessField(REWARD_STRUCTURE_VAR_CURRENT_STATE) != null) {
			CLVariable timeVar = generator.kernelGetLocalVar(LocalVar.TIME);
			CLVariable updatedTimeVar = generator.kernelGetLocalVar(LocalVar.UPDATED_TIME);
			return method.callMethod(stateVector.convertToPointer(), rewardStructure.convertToPointer(), timeVar, updatedTimeVar);
		}
		/**
		 * Otherwise time is not needed.
		 */
		else {
			return method.callMethod(stateVector.convertToPointer(), rewardStructure.convertToPointer());
		}
	}
	
	@Override
	protected Collection<KernelComponent> handleLoopCumul(SamplerDouble sampler, CLVariable propertyDest, CLVariable rewardVar)
	{
		CLVariable cumulReward = rewardVar.accessField(REWARD_STRUCTURE_VAR_CUMULATIVE_TOTAL);
		CLVariable stateReward = rewardVar.accessField(REWARD_STRUCTURE_VAR_CURRENT_STATE);
		CLVariable transReward = rewardVar.accessField(REWARD_STRUCTURE_VAR_PREVIOUS_TRANSITION);
		CLVariable currentTime = generator.kernelGetLocalVar(LocalVar.TIME);
		SamplerRewardCumulCont samplerCumul = (SamplerRewardCumulCont)sampler;
		KernelGeneratorCTMC generatorCTMC = (KernelGeneratorCTMC)generator;
		List<KernelComponent> exprs = new ArrayList<>();
		
		Expression timeDifference = addParentheses(createBinaryExpression(
				fromString( samplerCumul.getTime() ),
				Operator.SUB,
				currentTime.getSource()
				));
		/**
		 * Update cumulative reward by adding timeDiff * stateReward
		 */
		if(stateReward != null) {
			Expression updatedRew = createBinaryExpression(
					timeDifference,
					Operator.MUL,
					stateReward.getSource()
					);
			exprs.add( createBinaryExpression(
					cumulReward.getSource(),
					Operator.ADD_AUGM,
					updatedRew
					));
		}
		
		/**
		 * If transition reward is non-zero,
		 * keeps iterating to update cumulative reward
		 */
		if(transReward != null) {
			
			PRNGType prng = generator.getPRNG();
			/**
			 * Current implementation handles PRNGs which produce one or two
			 * random numbers per iteration. Keep this assertion to ensure that
			 * this part of code is not forgotten when a new PRNG is implemented.
			 * 
			 * For details see method kernelHandleLoop() in RewardGenerator
			 */
			Preconditions.checkCondition( prng.numbersPerRandomize() <= 2 );
			
			// TODO: make epsilon constant
			Expression transRwPos = createBinaryExpression(
					transReward.getSource(),
					Operator.GT,
					fromString("1e-7")
					);
			IfElse updateTrans = new IfElse(transRwPos);
			/**
			 * Boolean flag when there two numbers to access.
			 */
			CLVariable randomFlag = null;
			if(prng.numbersPerRandomize() > 1) {
				randomFlag = new CLVariable(new StdVariableType(StdType.BOOL), "randomFlag");
				randomFlag.setInitValue( StdVariableType.initialize(1) );
				updateTrans.addExpression( randomFlag.getDefinition() );
			}
			
			Expression timeReached = createBinaryExpression(
					currentTime.getSource(),
					Operator.LT,
					fromString( samplerCumul.getTime() )
					);
			/**
			 * while(time <= time_bound) {
			 * 	//fill body
			 * }
			 */
			While untilTimeReached = new While(timeReached);
			/**
			 * cumulative += transition_rw;
			 */
			untilTimeReached.addExpression( createBinaryExpression(
					cumulReward.getSource(),
					Operator.ADD_AUGM,
					transReward.getSource()
					));
			/**
			 * update_time()
			 * Access PRNG through either 'zero' or flag position
			 */
			untilTimeReached.addExpression( generatorCTMC.timeUpdate(
					currentTime, randomFlag != null ? randomFlag.getSource() : fromString(1)
					));
			/**
			 * randomize() - always or when flag is true
			 */
			if( randomFlag != null ) {
				IfElse ifFlag = new IfElse( randomFlag.getSource() );
				ifFlag.addExpression( prng.randomize() );
				untilTimeReached.addExpression( ifFlag );
			} else {
				untilTimeReached.addExpression( prng.randomize() );
			}
			/**
			 * Flip flag
			 */
			if( randomFlag != null ) {
				untilTimeReached.addExpression( createAssignment(
						randomFlag,
						createNegation(randomFlag.getSource())
						));
			}
			/**
			 * End loop
			 */
			updateTrans.addExpression(untilTimeReached);
			
			/**
			 * Correct reward by subtracting an additional transition reward.
			 * time > time_bound ? prevTransReward : 0.0
			 */
			Expression correction = createConditionalAssignment(
					createBinaryExpression(currentTime.getSource(), Operator.GT, fromString(samplerCumul.getTime()) ),
					transReward.getSource(),
					fromString(0.0)
					);
			updateTrans.addExpression(createBinaryExpression(
					cumulReward.getSource(),
					Operator.SUB_AUGM,
					correction
					));
			
			exprs.add(updateTrans);
		}

		/**
		 * Write final value
		 */
		exprs.add( createAssignment(propertyDest, cumulReward) );
		
		return exprs;
	}
}
