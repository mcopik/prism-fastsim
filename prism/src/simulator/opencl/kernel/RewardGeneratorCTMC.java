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

import java.util.Collection;
import java.util.Map;

import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.opencl.kernel.expression.Method;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.StdVariableType;
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

	public RewardGeneratorCTMC(KernelGenerator generator) throws KernelException
	{
		super(generator);
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
		map.put(SamplerRewardCumulDisc.class, vars);
		map.put(SamplerRewardCumulCont.class, vars);
	}

	@Override
	protected void initializeRewardRequiredVarsInstantaneous(Map<Class<? extends SamplerDouble>, String[]> map)
	{
		String[] vars = new String[] { REWARD_STRUCTURE_VAR_PREVIOUS_STATE, REWARD_STRUCTURE_VAR_CURRENT_STATE };
		map.put(SamplerRewardInstDisc.class, vars);
		map.put(SamplerRewardInstCont.class, vars);
	}

	@Override
	protected void stateRewardFunctionAdditionalArgs(Method function) throws KernelException
	{
		function.addArg(PREVIOUS_TIME_ARG);
		function.addArg(NEW_TIME_ARG);
	}

	@Override
	protected Expression stateRewardFunctionComputeCumulRw(Expression cumulReward, Expression stateReward, Expression transitionReward) throws KernelException
	{
		/**
		 * More complex update: add transition and state rewards, but the second one needs to be multiplied by time spent in state.
		 */
		Expression newValue = createBinaryExpression(stateReward, Operator.MUL, TIME_SPENT_STATE);
		newValue = createBinaryExpression(newValue, Operator.ADD, transitionReward);
		return createBinaryExpression(cumulReward, Operator.ADD_AUGM, newValue);
	}
}
