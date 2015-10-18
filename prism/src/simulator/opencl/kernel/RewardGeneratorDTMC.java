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

import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.ExpressionGenerator;
import simulator.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.opencl.kernel.expression.Method;
import simulator.sampler.SamplerDouble;
import simulator.sampler.SamplerRewardCumulCont;
import simulator.sampler.SamplerRewardCumulDisc;
import simulator.sampler.SamplerRewardInstCont;
import simulator.sampler.SamplerRewardInstDisc;

public class RewardGeneratorDTMC extends RewardGenerator
{
	public RewardGeneratorDTMC(KernelGenerator generator) throws KernelException
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
		String[] vars = new String[] { REWARD_STRUCTURE_VAR_CUMULATIVE_TOTAL };
		map.put(SamplerRewardCumulCont.class, vars);
		map.put(SamplerRewardCumulDisc.class, vars);
	}

	@Override
	protected void initializeRewardRequiredVarsInstantaneous(Map<Class<? extends SamplerDouble>, String[]> map)
	{
		String[] vars = new String[] { REWARD_STRUCTURE_VAR_CURRENT_STATE };
		map.put(SamplerRewardInstCont.class, vars);
		map.put(SamplerRewardInstDisc.class, vars);
	}

	@Override
	protected void stateRewardFunctionAdditionalArgs(Method function) throws KernelException
	{
		/**
		 * DTMC implementation doesn't require additional arguments.
		 */
	}

	@Override
	protected Expression stateRewardFunctionComputeCumulRw(Expression cumulReward, Expression stateReward, Expression transitionReward) throws KernelException
	{
		/**
		 * Simple update: just add transition and state reward.
		 */
		Expression newValue = ExpressionGenerator.createBinaryExpression(stateReward, Operator.ADD, transitionReward);
		return ExpressionGenerator.createBinaryExpression(cumulReward, Operator.ADD_AUGM, newValue);
	}
}
