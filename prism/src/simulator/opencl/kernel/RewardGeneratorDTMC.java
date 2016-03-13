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

import java.util.Collection;
import java.util.Map;

import static simulator.opencl.kernel.expression.ExpressionGenerator.createBinaryExpression;
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;
import prism.PrismLangException;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.ExpressionGenerator;
import simulator.opencl.kernel.expression.IfElse;
import simulator.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.opencl.kernel.expression.Method;
import simulator.opencl.kernel.memory.CLValue;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.opencl.kernel.memory.StructureType;
import simulator.opencl.kernel.memory.StdVariableType.StdType;
import simulator.sampler.SamplerDouble;
import simulator.sampler.SamplerRewardCumulCont;
import simulator.sampler.SamplerRewardCumulDisc;
import simulator.sampler.SamplerRewardInstCont;
import simulator.sampler.SamplerRewardInstDisc;

public class RewardGeneratorDTMC extends RewardGenerator
{
	public RewardGeneratorDTMC(KernelGenerator generator) throws KernelException, PrismLangException
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
		String[] vars = new String[] { REWARD_STRUCTURE_VAR_CUMULATIVE_TOTAL, REWARD_STRUCTURE_VAR_PREVIOUS_TRANSITION, 
				REWARD_STRUCTURE_VAR_CURRENT_STATE };
		map.put(SamplerRewardCumulDisc.class, vars);
	}

	@Override
	protected void initializeRewardRequiredVarsInstantaneous(Map<Class<? extends SamplerDouble>, String[]> map)
	{
		String[] vars = new String[] { REWARD_STRUCTURE_VAR_CURRENT_STATE };
		map.put(SamplerRewardInstDisc.class, vars);
	}

	@Override
	protected void stateRewardFunctionAdditionalArgs(Method function, StructureType rewardStructure) throws KernelException
	{
		/**
		 * DTMC implementation doesn't require additional arguments.
		 */
	}

	@Override
	protected Expression stateRewardFunctionComputeCumulRw(Expression cumulReward, CLVariable stateReward, CLVariable transitionReward) throws KernelException
	{
		/**
		 * Simple update: just add transition and state reward.
		 */
		Expression newValue = null;
		if (stateReward != null && transitionReward != null) {
			newValue = ExpressionGenerator.createBinaryExpression(stateReward.getSource(), Operator.ADD, transitionReward.getSource());
		} else if (stateReward != null ){
			newValue = stateReward.getSource();
		} else {
			newValue = transitionReward.getSource();
		}
		return ExpressionGenerator.createBinaryExpression(cumulReward, Operator.ADD_AUGM, newValue);
	}
	
	@Override
	protected void createPropertyInst(IfElse ifElse, SamplerDouble property, CLVariable propertyState, CLVariable rewardState)
	{
		CLVariable stateReward = rewardState.accessField(REWARD_STRUCTURE_VAR_CURRENT_STATE);
		Expression propertyCondition = createBinaryExpression( fromString( ((SamplerRewardInstDisc) property).getTime() ),
				Operator.EQ, argPropertyTime.getSource());
		/**
		 * If there's no state reward for a this reward structure - the reward will always be zero.
		 * 
		 * Very unlikely case (mostly a user error), but we want to be safe and avoid a nullptr exception.
		 */
		ifElse.addExpression( createPropertyCondition(propertyState, propertyCondition, 
				stateReward != null ? stateReward.getSource() : fromString(0.0)) );
	}	
	
	@Override
	protected void createPropertyCumul(IfElse ifElse, SamplerDouble property, CLVariable propertyState, CLVariable rewardState)
	{
		CLVariable cumulReward = rewardState.accessField(REWARD_STRUCTURE_VAR_CUMULATIVE_TOTAL);
		Expression propertyCondition = createBinaryExpression( fromString( ((SamplerRewardCumulDisc) property).getTime() ),
				Operator.EQ, argPropertyTime.getSource());
		ifElse.addExpression( createPropertyCondition(propertyState, propertyCondition, cumulReward.getSource()) );
	}
	
	@Override
	public boolean needsTimeDifference()
	{
		return false;
	}
	
	@Override
	protected Expression callStateRewardFunction(Method method, CLVariable stateVector, CLVariable rewardStructure)
	{
		return method.callMethod(stateVector.convertToPointer(), rewardStructure.convertToPointer());
	}
}
