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

import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.addComma;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.addParentheses;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.createAssignment;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.createBasicExpression;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.fromString;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.postIncrement;

import java.util.List;

import prism.Preconditions;
import simulator.gpu.automaton.AbstractAutomaton;
import simulator.gpu.automaton.update.Rate;
import simulator.gpu.opencl.kernel.expression.ComplexKernelComponent;
import simulator.gpu.opencl.kernel.expression.Expression;
import simulator.gpu.opencl.kernel.expression.ExpressionGenerator;
import simulator.gpu.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.gpu.opencl.kernel.expression.ForLoop;
import simulator.gpu.opencl.kernel.expression.IfElse;
import simulator.gpu.opencl.kernel.expression.KernelComponent;
import simulator.gpu.opencl.kernel.expression.Method;
import simulator.gpu.opencl.kernel.expression.Switch;
import simulator.gpu.opencl.kernel.memory.CLValue;
import simulator.gpu.opencl.kernel.memory.CLVariable;
import simulator.gpu.opencl.kernel.memory.StdVariableType;
import simulator.gpu.opencl.kernel.memory.StdVariableType.StdType;
import simulator.sampler.Sampler;
import simulator.sampler.SamplerBoolean;
import simulator.sampler.SamplerBoundedUntilCont;

public class KernelGeneratorCTMC extends KernelGenerator
{
	protected CLVariable varUpdatedTime = null;
	protected boolean saveTimeUpdate = false;

	public KernelGeneratorCTMC(AbstractAutomaton model, List<Sampler> properties, KernelConfig config)
	{
		super(model, properties, config);
		checkPropertiesTypes();
	}

	protected void checkPropertiesTypes()
	{
		for (Sampler sampler : properties) {
			if (sampler instanceof SamplerBoundedUntilCont) {
				saveTimeUpdate = true;
				break;
			}
		}
	}

	@Override
	public void mainMethodDefineLocalVars(Method currentMethod) throws KernelException
	{
		//time
		varTime = new CLVariable(new StdVariableType(StdType.FLOAT), "time");
		varTime.setInitValue(StdVariableType.initialize(0.0f));
		currentMethod.addLocalVar(varTime);
		//updated time
		varUpdatedTime = new CLVariable(new StdVariableType(StdType.FLOAT), "updatedTime");
		varUpdatedTime.setInitValue(StdVariableType.initialize(0.0f));
		currentMethod.addLocalVar(varUpdatedTime);
		//number of transitions
		varSelectionSize = new CLVariable(new StdVariableType(0, model.commandsNumber()), "selectionSize");
		varSelectionSize.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(varSelectionSize);
		if (model.synchCmdsNumber() != 0) {
			//number of transitions
			varSynSelectionSize = new CLVariable(new StdVariableType(0, model.synchCmdsNumber()), "synSelectionSize");
			varSynSelectionSize.setInitValue(StdVariableType.initialize(0));
			currentMethod.addLocalVar(varSynSelectionSize);
		}
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
		CLVariable tabPos = guardsTab.varType.accessElement(guardsTab, postIncrement(counter));
		IfElse ifElse = new IfElse(new Expression(guard));
		ifElse.addExpression(0, createAssignment(tabPos, fromString(position)));
		Expression sumExpr = createBasicExpression(sum.getSource(), Operator.ADD_AUGM, fromString(commands[position].getRateSum()));
		sumExpr.add(";");
		ifElse.addExpression(0, sumExpr);
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
			_switch.addCommand(i, ExpressionGenerator.createAssignment(newSum, fromString(rateSum)));
		}
		loop.addExpression(_switch);
		// if(sum + newSum > selectionSum)
		Expression condition = createBasicExpression(
		//selectionSum
				selectionSum.getSource(),
				// <
				Operator.LT,
				//sum + newSum
				createBasicExpression(sum.getSource(), Operator.ADD, newSum.getSource()));
		IfElse ifElse = new IfElse(condition);
		Expression reduction = createBasicExpression(selectionSum.getSource(), Operator.SUB_AUGM, sum.getSource());
		ifElse.addExpression(0, reduction.add(";"));
		ifElse.addExpression(0, new Expression("break;"));
		loop.addExpression(ifElse);
		loop.addExpression(createBasicExpression(sum.getSource(), Operator.ADD_AUGM, newSum.getSource()).add(";"));
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
		varTime = new CLVariable(new StdVariableType(StdType.FLOAT), "time");
		currentMethod.addArg(varTime);
		if (saveTimeUpdate) {
			varUpdatedTime = new CLVariable(new StdVariableType(StdType.FLOAT), "updated_time");
			currentMethod.addArg(varUpdatedTime);
		}
	}

	@Override
	protected KernelComponent propertiesMethodAddBoundedUntil(Method currentMethod, SamplerBoolean property, CLVariable propertyVar)
	{
		return null;
	}

	@Override
	protected void mainMethodUpdateTimeBefore(Method currentMethod, ComplexKernelComponent parent)
	{
		CLValue random = config.prngType.getRandomUnifFloat(fromString(1));
		Expression substrRng = createBasicExpression(fromString(1),
		//1 - random()
				Operator.SUB, random.getSource());
		substrRng = new Expression(String.format("log(%s)", substrRng.getSource()));
		Expression sum = null;
		//for synchronized commands - selection_size + selection_syn
		if (varSynSelectionSize != null && varSelectionSize != null) {
			sum = createBasicExpression(varSelectionSize.getSource(), Operator.ADD, varSynSelectionSize.getSource());
			addParentheses(sum);
		} else if (varSynSelectionSize != null) {
			sum = varSelectionSize.getSource();
		} else {
			sum = varSynSelectionSize.getSource();
		}
		substrRng = createBasicExpression(substrRng, Operator.DIV, sum);
		// updated = time - new value
		// OR time -= new value
		if (saveTimeUpdate) {
			substrRng = createBasicExpression(varTime.getSource(), Operator.SUB, substrRng);
			parent.addExpression(createAssignment(varUpdatedTime, substrRng));
		} else {
			parent.addExpression(addComma(createBasicExpression(varTime.getSource(), Operator.SUB_AUGM, substrRng)));
		}
	}

	@Override
	protected void mainMethodUpdateTimeAfter(Method currentMethod, ComplexKernelComponent parent)
	{
		if (saveTimeUpdate) {
			parent.addExpression(createAssignment(varTime, varUpdatedTime));
		}
	}

	@Override
	protected Expression mainMethodCallCheckingProperties(Method currentMethod)
	{
		CLVariable propertiesArray = currentMethod.getLocalVar("properties");
		Expression call = null;
		if (saveTimeUpdate) {
			call = helperMethods.get(KernelMethods.UPDATE_PROPERTIES).callMethod(varStateVector.convertToPointer(), propertiesArray, varTime, varUpdatedTime);
		} else {
			call = helperMethods.get(KernelMethods.UPDATE_PROPERTIES).callMethod(varStateVector.convertToPointer(), propertiesArray, varTime);
		}
		String source = call.getSource();
		return new Expression(source.substring(0, source.indexOf(';')));
	}

	@Override
	protected int mainMethodRandomsPerIteration()
	{
		return 2;
	}

}