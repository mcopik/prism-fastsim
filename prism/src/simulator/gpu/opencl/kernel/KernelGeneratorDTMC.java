//==============================================================================
//	
//	Copyright (c) 2002-
//	Authors:
//	* Marcin Copik <mcopik@gmail.com> (Silesian University of Technology)
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
package simulator.gpu.opencl.kernel;

import java.util.List;

import prism.Preconditions;
import simulator.gpu.automaton.AbstractAutomaton;
import simulator.gpu.opencl.kernel.expression.Expression;
import simulator.gpu.opencl.kernel.expression.ExpressionGenerator;
import simulator.gpu.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.gpu.opencl.kernel.expression.IfElse;
import simulator.gpu.opencl.kernel.expression.KernelComponent;
import simulator.gpu.opencl.kernel.expression.Method;
import simulator.gpu.opencl.kernel.memory.CLVariable;
import simulator.gpu.opencl.kernel.memory.StdVariableType;
import simulator.sampler.Sampler;
import simulator.sampler.SamplerBoolean;
import simulator.sampler.SamplerBoundedUntilDisc;

public class KernelGeneratorDTMC extends KernelGenerator
{

	public KernelGeneratorDTMC(AbstractAutomaton model, List<Sampler> properties, KernelConfig config)
	{
		super(model, properties, config);
	}

	@Override
	public void mainMethodDefineLocalVars() throws KernelException
	{
		//number of transitions
		CLVariable time = new CLVariable(new StdVariableType(0, config.maxPathLength), "time");
		time.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(time);
		//number of transitions
		CLVariable selectionSize = new CLVariable(new StdVariableType(0, model.commandsNumber()), "selectionSize");
		selectionSize.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(selectionSize);
	}

	@Override
	protected void guardsMethodCreateLocalVars() throws KernelException
	{
	}

	@Override
	protected void guardsMethodCreateSignature()
	{
		currentMethod = new Method("checkNonsynGuards", new StdVariableType(0, commands.length - 1));
	}

	@Override
	protected void guardsMethodCreateCondition(int position, String guard)
	{
		CLVariable guardsTab = currentMethod.getArg("guardsTab");
		Preconditions.checkNotNull(guardsTab, "");
		CLVariable counter = currentMethod.getLocalVar("counter");
		Preconditions.checkNotNull(counter, "");
		CLVariable tabPos = guardsTab.varType.accessElement(guardsTab, ExpressionGenerator.postIncrement(counter));
		IfElse ifElse = new IfElse(new Expression(guard));
		ifElse.addCommand(0, ExpressionGenerator.createAssignment(tabPos, Integer.toString(position)));
		currentMethod.addExpression(ifElse);
	}

	@Override
	protected void guardsMethodReturnValue()
	{
		CLVariable counter = currentMethod.getLocalVar("counter");
		Preconditions.checkNotNull(counter, "");
		currentMethod.addReturn(counter);
	}

	@Override
	protected void updateMethodPerformSelection() throws KernelException
	{
		CLVariable sum = currentMethod.getArg("selectionSum");
		CLVariable number = currentMethod.getArg("numberOfCommands");
		CLVariable selection = currentMethod.getLocalVar("selection");
		Expression divideSum = ExpressionGenerator.createBasicExpression(selection, Operator.DIV, number);
		Expression reduceSum = ExpressionGenerator.createBasicExpression(sum, Operator.SUB, divideSum);
		ExpressionGenerator.addParentheses(reduceSum);
		reduceSum = ExpressionGenerator.createBasicExpression(number, Operator.MUL, reduceSum);
		currentMethod.addExpression(ExpressionGenerator.createAssignment(sum, reduceSum));
	}

	@Override
	protected void updateMethodAdditionalArgs() throws KernelException
	{
		//uint numberOfCommands
		CLVariable numberOfCommands = new CLVariable(new StdVariableType(0, commands.length - 1), "numberOfCommands");
		currentMethod.addArg(numberOfCommands);
	}

	@Override
	protected void updateMethodLocalVars() throws KernelException
	{
		//selection
		CLVariable sum = currentMethod.getArg("selectionSum");
		CLVariable number = currentMethod.getArg("numberOfCommands");
		CLVariable selection = currentMethod.getLocalVar("selection");
		String selectionExpression = String.format("floor(%s)", ExpressionGenerator.createBasicExpression(sum, Operator.MUL, number).getSource());
		selection.setInitValue(new Expression(selectionExpression));
	}

	@Override
	protected void propertiesMethodTimeArg() throws KernelException
	{
		boolean necessaryFlag = false;
		for (Sampler sampler : properties) {
			if (sampler instanceof SamplerBoundedUntilDisc) {
				necessaryFlag = true;
				break;
			}
		}
		if (necessaryFlag) {
			CLVariable time = new CLVariable(new StdVariableType(0, config.maxPathLength), "time");
			currentMethod.addArg(time);
		}
	}

	@Override
	protected KernelComponent propertiesMethodAddBoundedUntil(SamplerBoolean property, CLVariable propertyVar)
	{
		// TODO Auto-generated method stub
		return null;
	}
}