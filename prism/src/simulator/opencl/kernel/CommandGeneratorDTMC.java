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
package simulator.opencl.kernel;

import static simulator.opencl.kernel.expression.ExpressionGenerator.createAssignment;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createBinaryExpression;
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;
import static simulator.opencl.kernel.expression.ExpressionGenerator.postIncrement;

import java.util.Collection;
import java.util.Collections;

import prism.Preconditions;
import simulator.opencl.kernel.CommandGenerator.CheckGuardsVar;
import simulator.opencl.kernel.KernelGenerator.KernelMethods;
import simulator.opencl.kernel.KernelGenerator.LocalVar;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.ExpressionGenerator;
import simulator.opencl.kernel.expression.IfElse;
import simulator.opencl.kernel.expression.KernelComponent;
import simulator.opencl.kernel.expression.Method;
import simulator.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.opencl.kernel.memory.CLValue;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.opencl.kernel.memory.VariableTypeInterface;
import simulator.opencl.kernel.memory.StdVariableType.StdType;

public class CommandGeneratorDTMC extends CommandGenerator
{

	public CommandGeneratorDTMC(KernelGenerator generator) throws KernelException
	{
		super(generator);
	}
	
	@Override
	public VariableTypeInterface kernelUpdateSizeType()
	{
		return new StdVariableType(0, commands.length);
	}
	
	@Override
	public KernelComponent kernelCallGuardCheck() throws KernelException
	{
		if(activeGenerator) {
			checkGuards = createGuardsMethod();
			CLVariable transactionSize =
					generator.kernelGetLocalVar(LocalVar.UNSYNCHRONIZED_SIZE);
			return createAssignment(transactionSize,
					checkGuards.callMethod(
					generator.kernelGetLocalVar(LocalVar.STATE_VECTOR).convertToPointer(),
					kernelGuardsTab
					));
		}
		return new Expression();
	}
	
	@Override
	public Expression kernelCallUpdate(CLValue rnd, CLValue sum) throws KernelException
	{
		updateFunction = createUpdate();
		Expression call = updateFunction.callMethod(
				//&stateVector
				generator.kernelGetLocalVar(LocalVar.STATE_VECTOR).convertToPointer(),
				//non-synchronized guards tab
				kernelGuardsTab,
				//random float [0,1]
				rnd,
				//number of commands
				sum);
		return generator.getLoopDetector().kernelCallUpdate(call);
	}

	/*********************************
	 * GUARDS CHECK
	 ********************************/

	@Override
	protected Collection<CLVariable> guardsMethodAddArgs()
	{
		return Collections.emptyList();
	}
	
	@Override
	protected Collection<CLVariable> guardsMethodCreateLocalVars()
	{
		return Collections.emptyList();
	}

	@Override
	protected Method guardsMethodCreateSignature()
	{
		return new Method(CHECK_GUARDS_NAME, new StdVariableType(0, commands.length - 1));
	}

	@Override
	protected void guardsMethodCreateCondition(Method currentMethod, StateVector.Translations translations,
			int position, Expression guard)
	{
		CLVariable tabPos = checkGuardsVars.get(CheckGuardsVar.GUARDS_TAB).
				accessElement(postIncrement( checkGuardsVars.get(CheckGuardsVar.COUNTER) ));
		IfElse ifElse = new IfElse(guard);
		ifElse.addExpression(0, createAssignment(tabPos, fromString(position)));
		currentMethod.addExpression(ifElse);
	}

	@Override
	protected void guardsMethodReturnValue(Method currentMethod)
	{
		currentMethod.addReturn( checkGuardsVars.get(CheckGuardsVar.COUNTER) );
	}

	/*********************************
	 * UPDATE
	 ********************************/

	@Override
	protected void updateMethodPerformSelection(Method currentMethod, StateVector.Translations translations) throws KernelException
	{
		//INPUT: selectionSum - float [0, numberOfAllCommands];
		CLVariable sum = currentMethod.getArg("selectionSum");
		CLVariable number = currentMethod.getArg("numberOfCommands");
		CLVariable selection = currentMethod.getLocalVar("selection");
		CLVariable guardsTab = currentMethod.getArg("guardsTab");
		guardsTab = guardsTab.accessElement(selection.getName());

		// selectionSum = numberOfCommands * ( selectionSum - selection/numberOfCommands);
		Expression divideSelection = createBinaryExpression(selection.cast("float"), Operator.DIV, number.getSource());
		Expression subSum = createBinaryExpression(sum.getSource(), Operator.SUB, divideSelection);
		ExpressionGenerator.addParentheses(subSum);
		Expression asSum = createBinaryExpression(number.getSource(), Operator.MUL, subSum);
		currentMethod.addExpression(ExpressionGenerator.createAssignment(sum, asSum));
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
		String selectionExpression = String.format("floor(%s)", createBinaryExpression(sum.getSource(), Operator.MUL, number.getSource()).toString());
		selection.setInitValue(new Expression(selectionExpression));
	}
}
