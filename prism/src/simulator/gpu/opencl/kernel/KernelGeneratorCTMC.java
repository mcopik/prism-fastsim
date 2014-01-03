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

import prism.Preconditions;
import simulator.gpu.automaton.AbstractAutomaton;
import simulator.gpu.opencl.kernel.expression.Expression;
import simulator.gpu.opencl.kernel.expression.ExpressionGenerator;
import simulator.gpu.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.gpu.opencl.kernel.expression.IfElse;
import simulator.gpu.opencl.kernel.expression.Method;
import simulator.gpu.opencl.kernel.memory.CLVariable;
import simulator.gpu.opencl.kernel.memory.PointerType;
import simulator.gpu.opencl.kernel.memory.StdVariableType;
import simulator.gpu.opencl.kernel.memory.StdVariableType.StdType;

public class KernelGeneratorCTMC extends KernelGenerator
{

	public KernelGeneratorCTMC(AbstractAutomaton model, CLVariable stateVector)
	{
		super(model, stateVector);
	}

	@Override
	protected void guardsMethodCreateLocalVars() throws KernelException
	{
		CLVariable sum = new CLVariable(new StdVariableType(StdType.FLOAT), "sum");
		sum.setInitValue(StdVariableType.initialize(0.0f));
		currentMethod.addLocalVar(sum);
	}

	@Override
	protected void guardsMethodCreateSignature()
	{
		currentMethod = new Method("checkNonsynGuards", new StdVariableType(StdType.FLOAT));
	}

	@Override
	protected void guardsMethodCreateCondition(int position, String guard)
	{
		CLVariable guardsTab = currentMethod.getArg("guardsTab");
		Preconditions.checkNotNull(guardsTab, "");
		CLVariable counter = currentMethod.getLocalVar("counter");
		Preconditions.checkNotNull(counter, "");
		CLVariable sum = currentMethod.getLocalVar("sum");
		Preconditions.checkNotNull(sum, "");
		CLVariable tabPos = guardsTab.varType.accessElement(guardsTab.varName, ExpressionGenerator.postIncrement(counter));
		IfElse ifElse = new IfElse(new Expression(guard));
		ifElse.addCommand(0, ExpressionGenerator.createAssignment(tabPos, Integer.toString(position)));
		Expression sumExpr = ExpressionGenerator.createBasicExpression(sum, Operator.ADD_AUGM, commands[position].getRateSum().toString());
		sumExpr.add(";");
		ifElse.addCommand(0, sumExpr);
		currentMethod.addExpression(ifElse);
	}

	@Override
	protected void guardsMethodReturnValue()
	{
		CLVariable sum = currentMethod.getLocalVar("sum");
		Preconditions.checkNotNull(sum, "");
		currentMethod.addReturn(sum);
	}

	@Override
	protected void updateMethodPerformSelection()
	{
		// TODO Auto-generated method stub

	}

	@Override
	protected void updateMethodAdditionalArgs() throws KernelException
	{
		//bool * guardsTab
		CLVariable guards = new CLVariable(new PointerType(new StdVariableType(0, commands.length)), "guardsTab");
		currentMethod.addArg(guards);
	}

	@Override
	protected void updateMethodLocalVars() throws KernelException
	{
		//float newSum
		CLVariable newSum = new CLVariable(new StdVariableType(StdType.FLOAT), "newSum");
		newSum.setInitValue(StdVariableType.initialize(0.0f));
		currentMethod.addLocalVar(newSum);
		//selection
		CLVariable selection = currentMethod.getLocalVar("selection");
		selection.setInitValue(StdVariableType.initialize(0));
	}
}