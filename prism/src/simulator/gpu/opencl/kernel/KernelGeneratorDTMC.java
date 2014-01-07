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
	public void mainMethodDefineLocalVars(Method currentMethod) throws KernelException
	{
		//time
		CLVariable time = new CLVariable(new StdVariableType(0, config.maxPathLength), "time");
		time.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(time);
		//number of transitions
		CLVariable selectionSize = new CLVariable(new StdVariableType(0, model.commandsNumber()), "selectionSize");
		selectionSize.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(selectionSize);
	}

	@Override
	protected KernelComponent mainMethodCallUpdate(Method currentMethod)
	{
		Method update = helperMethods.get(KernelMethods.PERFORM_UPDATE);
		CLVariable sv = currentMethod.getLocalVar("stateVector");
		CLVariable selection = currentMethod.getLocalVar("selection");
		CLVariable selectionSize = currentMethod.getLocalVar("selectionSize");
		CLVariable guardsTab = currentMethod.getLocalVar("guardsTab");
		return update.callMethod(sv.convertToPointer(), guardsTab, selection, selectionSize);
	}

	@Override
	protected void guardsMethodCreateLocalVars(Method currentMethod) throws KernelException
	{
	}

	@Override
	protected Method guardsMethodCreateSignature()
	{
		return new Method("checkNonsynGuards", new StdVariableType(0, commands.length - 1));
	}

	@Override
	protected void guardsMethodCreateCondition(Method currentMethod, int position, String guard)
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
	protected void guardsMethodReturnValue(Method currentMethod)
	{
		CLVariable counter = currentMethod.getLocalVar("counter");
		Preconditions.checkNotNull(counter, "");
		currentMethod.addReturn(counter);
	}

	@Override
	protected void updateMethodPerformSelection(Method currentMethod) throws KernelException
	{
		//INPUT: selectionSum - float [0, numberOfAllCommands];
		CLVariable sum = currentMethod.getArg("selectionSum");
		CLVariable number = currentMethod.getArg("numberOfCommands");
		CLVariable selection = currentMethod.getLocalVar("selection");
		CLVariable guardsTab = currentMethod.getArg("guardsTab");
		guardsTab = guardsTab.varType.accessElement(guardsTab, selection.getName());

		// selection = floor(selectionSum)
		currentMethod.addExpression(ExpressionGenerator.createAssignment(selection, ExpressionGenerator.functionCall("floor", sum.getName())));

		// selectionSum = numberOfCommands * ( selectionSum - selection/numberOfCommands);
		Expression divideSelection = ExpressionGenerator.createBasicExpression(selection.cast("float"), Operator.DIV, number);
		Expression subSum = ExpressionGenerator.createBasicExpression(sum, Operator.SUB, divideSelection);
		ExpressionGenerator.addParentheses(subSum);
		Expression asSum = ExpressionGenerator.createBasicExpression(number, Operator.MUL, subSum);
		currentMethod.addExpression(ExpressionGenerator.createAssignment(sum, asSum));
		currentMethod.addExpression(ExpressionGenerator.createAssignment(selection, guardsTab.getName()));
	}

	@Override
	protected void updateMethodAdditionalArgs(Method currentMethod) throws KernelException
	{
		//uint numberOfCommands
		CLVariable numberOfCommands = new CLVariable(new StdVariableType(0, commands.length - 1), "numberOfCommands");
		currentMethod.addArg(numberOfCommands);
	}

	@Override
	protected void updateMethodLocalVars(Method currentMethod) throws KernelException
	{
		//selection
		CLVariable sum = currentMethod.getArg("selectionSum");
		CLVariable number = currentMethod.getArg("numberOfCommands");
		CLVariable selection = currentMethod.getLocalVar("selection");
		String selectionExpression = String.format("floor(%s)", ExpressionGenerator.createBasicExpression(sum, Operator.MUL, number).getSource());
		selection.setInitValue(new Expression(selectionExpression));
	}

	@Override
	protected void propertiesMethodTimeArg(Method currentMethod) throws KernelException
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
	protected KernelComponent propertiesMethodAddBoundedUntil(Method currentMethod, SamplerBoolean property, CLVariable propertyVar)
	{
		// TODO Auto-generated method stub
		return null;
	}
}