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
import simulator.gpu.automaton.update.Rate;
import simulator.gpu.opencl.kernel.expression.Expression;
import simulator.gpu.opencl.kernel.expression.ExpressionGenerator;
import simulator.gpu.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.gpu.opencl.kernel.expression.ForLoop;
import simulator.gpu.opencl.kernel.expression.IfElse;
import simulator.gpu.opencl.kernel.expression.KernelComponent;
import simulator.gpu.opencl.kernel.expression.Method;
import simulator.gpu.opencl.kernel.expression.Switch;
import simulator.gpu.opencl.kernel.memory.CLVariable;
import simulator.gpu.opencl.kernel.memory.StdVariableType;
import simulator.gpu.opencl.kernel.memory.StdVariableType.StdType;
import simulator.sampler.Sampler;
import simulator.sampler.SamplerBoolean;
import simulator.sampler.SamplerBoundedUntilCont;

public class KernelGeneratorCTMC extends KernelGenerator
{

	public KernelGeneratorCTMC(AbstractAutomaton model, List<Sampler> properties, KernelConfig config)
	{
		super(model, properties, config);
	}

	@Override
	public void mainMethodDefineLocalVars(Method currentMethod) throws KernelException
	{
		//time
		CLVariable time = new CLVariable(new StdVariableType(StdType.FLOAT), "time");
		time.setInitValue(StdVariableType.initialize(0.0f));
		currentMethod.addLocalVar(time);
		//updated time
		CLVariable updatedTime = new CLVariable(new StdVariableType(StdType.FLOAT), "updatedTime");
		updatedTime.setInitValue(StdVariableType.initialize(0.0f));
		currentMethod.addLocalVar(updatedTime);
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
		CLVariable guardsTab = currentMethod.getLocalVar("guardsTab");
		return update.callMethod(sv.convertToPointer(), guardsTab, selection);
	}

	@Override
	protected void guardsMethodCreateLocalVars(Method currentMethod) throws KernelException
	{
		CLVariable sum = new CLVariable(new StdVariableType(StdType.FLOAT), "sum");
		sum.setInitValue(StdVariableType.initialize(0.0f));
		currentMethod.addLocalVar(sum);
	}

	@Override
	protected Method guardsMethodCreateSignature()
	{
		return new Method("checkNonsynGuards", new StdVariableType(StdType.FLOAT));
	}

	@Override
	protected void guardsMethodCreateCondition(Method currentMethod, int position, String guard)
	{
		CLVariable guardsTab = currentMethod.getArg("guardsTab");
		Preconditions.checkNotNull(guardsTab, "");
		CLVariable counter = currentMethod.getLocalVar("counter");
		Preconditions.checkNotNull(counter, "");
		CLVariable sum = currentMethod.getLocalVar("sum");
		Preconditions.checkNotNull(sum, "");
		CLVariable tabPos = guardsTab.varType.accessElement(guardsTab, ExpressionGenerator.postIncrement(counter));
		IfElse ifElse = new IfElse(new Expression(guard));
		ifElse.addCommand(0, ExpressionGenerator.createAssignment(tabPos, Integer.toString(position)));
		Expression sumExpr = ExpressionGenerator.createBasicExpression(sum, Operator.ADD_AUGM, commands[position].getRateSum().toString());
		sumExpr.add(";");
		ifElse.addCommand(0, sumExpr);
		currentMethod.addExpression(ifElse);
	}

	@Override
	protected void guardsMethodReturnValue(Method currentMethod)
	{
		CLVariable sum = currentMethod.getLocalVar("sum");
		Preconditions.checkNotNull(sum, "");
		currentMethod.addReturn(sum);
	}

	@Override
	protected void updateMethodPerformSelection(Method currentMethod) throws KernelException
	{
		CLVariable selection = currentMethod.getLocalVar("selection");
		CLVariable guardsTab = currentMethod.getArg("guardsTab");
		CLVariable newSum = currentMethod.getLocalVar("newSum");
		CLVariable selectionSum = currentMethod.getArg("selectionSum");
		CLVariable sum = new CLVariable(new StdVariableType(StdType.FLOAT), "sum");
		currentMethod.addLocalVar(sum);
		ForLoop loop = new ForLoop(selection, false);
		Switch _switch = new Switch(guardsTab.varType.accessElement(guardsTab, selection.getName()));
		for (int i = 0; i < commands.length; ++i) {
			Rate rateSum = commands[i].getRateSum();
			_switch.addCase(new Expression(Integer.toString(i)));
			_switch.addCommand(i, ExpressionGenerator.createAssignment(newSum, rateSum.toString()));
		}
		loop.addExpression(_switch);
		// if(sum + newSum > selectionSum)
		Expression condition = ExpressionGenerator.createBasicExpression(
		//selectionSum
				selectionSum,
				// <
				Operator.LT,
				//sum + newSum
				ExpressionGenerator.createBasicExpression(sum, Operator.ADD, newSum));
		IfElse ifElse = new IfElse(condition);
		Expression reduction = ExpressionGenerator.createBasicExpression(selectionSum, Operator.SUB_AUGM, sum);
		ifElse.addCommand(0, reduction.add(";"));
		ifElse.addCommand(0, new Expression("break;"));
		loop.addExpression(ifElse);
		loop.addExpression(ExpressionGenerator.createBasicExpression(sum, Operator.ADD_AUGM, newSum).add(";"));
		currentMethod.addExpression(loop);
	}

	@Override
	protected void updateMethodAdditionalArgs(Method currentMethod) throws KernelException
	{
	}

	@Override
	protected void updateMethodLocalVars(Method currentMethod) throws KernelException
	{
		//float newSum
		CLVariable newSum = new CLVariable(new StdVariableType(StdType.FLOAT), "newSum");
		newSum.setInitValue(StdVariableType.initialize(0.0f));
		currentMethod.addLocalVar(newSum);
		//selection
		CLVariable selection = currentMethod.getLocalVar("selection");
		selection.setInitValue(StdVariableType.initialize(0));
	}

	@Override
	protected void propertiesMethodTimeArg(Method currentMethod) throws KernelException
	{
		boolean necessaryFlag = false;
		for (Sampler sampler : properties) {
			if (sampler instanceof SamplerBoundedUntilCont) {
				necessaryFlag = true;
				break;
			}
		}
		if (necessaryFlag) {
			CLVariable time = new CLVariable(new StdVariableType(StdType.FLOAT), "time");
			CLVariable updated_time = new CLVariable(new StdVariableType(StdType.FLOAT), "time");
			currentMethod.addArg(time);
			currentMethod.addArg(updated_time);
		}
	}

	@Override
	protected KernelComponent propertiesMethodAddBoundedUntil(Method currentMethod, SamplerBoolean property, CLVariable propertyVar)
	{
		return null;
	}

}