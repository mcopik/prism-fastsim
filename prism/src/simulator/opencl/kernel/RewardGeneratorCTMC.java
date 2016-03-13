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
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;

import java.util.Collection;
import java.util.Map;

import prism.PrismLangException;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.IfElse;
import simulator.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.opencl.kernel.expression.Method;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.opencl.kernel.memory.StructureType;
import simulator.opencl.kernel.memory.StdVariableType.StdType;
import simulator.sampler.SamplerDouble;
import simulator.sampler.SamplerRewardCumulCont;
import simulator.sampler.SamplerRewardCumulDisc;
import simulator.sampler.SamplerRewardInstCont;
import simulator.sampler.SamplerRewardInstDisc;

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
		generateRewardCode();
	}

	@Override
	public Collection<Method> getMethods()
	{
		// TODO Auto-generated method stub
		return null;
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
	protected void createPropertyInst(IfElse ifElse, SamplerDouble property, CLVariable propertyState, CLVariable rewardState)
	{
	}	
	
	@Override
	protected void createPropertyCumul(IfElse ifElse, SamplerDouble property, CLVariable propertyState, CLVariable rewardState)
	{
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
			CLVariable[] timeVars = generator.mainMethodTimeVariable();
			return method.callMethod(stateVector.convertToPointer(), rewardStructure.convertToPointer(), timeVars[0], timeVars[1]);
		}
		/**
		 * Otherwise time is not needed.
		 */
		else {
			return method.callMethod(stateVector.convertToPointer(), rewardStructure.convertToPointer());
		}
	}
}
