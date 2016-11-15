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

import static simulator.opencl.kernel.expression.ExpressionGenerator.convertPrismRate;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createAssignment;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createBinaryExpression;
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;
import static simulator.opencl.kernel.expression.ExpressionGenerator.postIncrement;

import java.util.Collection;
import java.util.Collections;

import prism.Preconditions;

import com.sun.corba.se.spi.orbutil.fsm.Guard;

import simulator.opencl.automaton.update.Rate;
import simulator.opencl.kernel.KernelGenerator.KernelMethods;
import simulator.opencl.kernel.KernelGenerator.LocalVar;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.ExpressionGenerator;
import simulator.opencl.kernel.expression.ExpressionList;
import simulator.opencl.kernel.expression.ForLoop;
import simulator.opencl.kernel.expression.IfElse;
import simulator.opencl.kernel.expression.KernelComponent;
import simulator.opencl.kernel.expression.Method;
import simulator.opencl.kernel.expression.Switch;
import simulator.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.opencl.kernel.memory.CLValue;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.PointerType;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.opencl.kernel.memory.VariableTypeInterface;
import simulator.opencl.kernel.memory.StdVariableType.StdType;

public class CommandGeneratorCTMC extends CommandGenerator
{
	/**
	 * True iff both transition count and rate sum has to be computed.
	 */
	protected boolean transitionsCountRequested = false;
	
	/**
	 * Stores sum of rates when transitions are not counted.
	 * Used as a local variable
	 */
	protected CLVariable guardsVarSum = null;
	
	/**
	 * Stores sum of rates when transitions are counted.
	 * Used as a pointer in argument list
	 */
	protected CLVariable guardsVarPtrSum = null;
	
	public CommandGeneratorCTMC(KernelGenerator generator) throws KernelException
	{
		super(generator);
		
		transitionsCountRequested =
				generator.kernelGetLocalVar(LocalVar.TRANSITIONS_COUNTER) != null;
	}
	
	@Override
	public VariableTypeInterface kernelUpdateSizeType()
	{
		return new StdVariableType(StdType.FLOAT);
	}
	
	@Override
	public KernelComponent kernelCallGuardCheck() throws KernelException
	{
		if(activeGenerator) {

			checkGuards = createGuardsMethod();
			CLVariable stateVectorPtr = 
					generator.kernelGetLocalVar(LocalVar.STATE_VECTOR).convertToPointer();
			// transactionCounter += nonSynGuards(&size);
			if(transitionsCountRequested) {
				ExpressionList list = new ExpressionList();
				CLVariable transactionCounter =
						generator.kernelGetLocalVar(LocalVar.TRANSITIONS_COUNTER);
				CLVariable transactionSize =
						generator.kernelGetLocalVar(LocalVar.UNSYNCHRONIZED_SIZE);
				Expression callCheckGuards = checkGuards.callMethod(
						stateVectorPtr,
						kernelGuardsTab,
						transactionSize.convertToPointer());
				// rate_sum = 0; before passing it to function
				list.addExpression( createAssignment(transactionSize, fromString(0)) );
				list.addExpression( createAssignment(transactionCounter, callCheckGuards) );
				return list;
			}
			// otherwise size += synGuards()
			else {
				CLVariable transactionSize =
						generator.kernelGetLocalVar(LocalVar.UNSYNCHRONIZED_SIZE);
				return createAssignment(transactionSize,
						checkGuards.callMethod(
						stateVectorPtr,
						kernelGuardsTab
						));
			}
			
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
				rnd);
		return generator.getLoopDetector().kernelCallUpdate(call);
	}
	
	/*********************************
	 * GUARDS CHECK
	 ********************************/

	@Override
	protected Collection<CLVariable> guardsMethodCreateLocalVars()
	{
		// when transition counting is not required, declare sum variable locally
		if(!transitionsCountRequested) {
			guardsVarSum = new CLVariable(new StdVariableType(StdType.FLOAT), "sum");
			guardsVarSum.setInitValue(StdVariableType.initialize(0.0f));
			return Collections.singleton(guardsVarSum);
		}
		return Collections.emptyList();
	}

	@Override
	protected Collection<CLVariable> guardsMethodAddArgs()
	{
		/**
		 * If we need to count transitions, pass sum as a parameter
		 */
		if(transitionsCountRequested) {
			guardsVarPtrSum = new CLVariable(new PointerType(new StdVariableType(StdType.FLOAT)), "sum");
			guardsVarPtrSum.setInitValue(StdVariableType.initialize(0.0f));
			return Collections.singleton(guardsVarPtrSum);
		}
		return Collections.emptyList();
	}


	@Override
	protected Method guardsMethodCreateSignature()
	{
		/**
		 * If we need to use a counter, return this counter.
		 * Otherwise we don't return anything.
		 */
		CLVariable counterVariable = generator.kernelGetLocalVar(LocalVar.TRANSITIONS_COUNTER);
		return new Method(CHECK_GUARDS_NAME,
				counterVariable != null ? counterVariable.varType : new StdVariableType(StdType.FLOAT)
				);
			
	}

	@Override
	protected void guardsMethodCreateCondition(Method currentMethod, StateVector.Translations translations,
			int position, Expression guard)
	{
		CLVariable tabPos = checkGuardsVars.get(CheckGuardsVar.GUARDS_TAB).
				accessElement(postIncrement( checkGuardsVars.get(CheckGuardsVar.COUNTER) ));

		IfElse ifElse = new IfElse(guard);
		ifElse.addExpression(0, createAssignment(tabPos, fromString(position)));
		Expression sumExpr = createBinaryExpression(
				guardsVarSum != null ? guardsVarSum.getSource() : guardsVarPtrSum.dereference().getSource(),
				Operator.ADD_AUGM,
				convertPrismRate(translations, commands[position].getRateSum())
				);
		ifElse.addExpression(0, sumExpr);
		currentMethod.addExpression(ifElse);
	}

	@Override
	protected void guardsMethodReturnValue(Method currentMethod)
	{
		/**
		 * If we need to use a counter, return this counter.
		 * Otherwise return sum of rates.
		 */
		if(transitionsCountRequested){
			currentMethod.addReturn( checkGuardsVars.get(CheckGuardsVar.COUNTER) );
		} else {
			currentMethod.addReturn( guardsVarSum );
		}
	}

	/*********************************
	 * UPDATE
	 ********************************/

	@Override
	protected void updateMethodPerformSelection(Method currentMethod, StateVector.Translations translations) throws KernelException
	{
		CLVariable selection = currentMethod.getLocalVar("selection");
		CLVariable guardsTab = currentMethod.getArg("guardsTab");
		CLVariable newSum = currentMethod.getLocalVar("newSum");
		CLVariable selectionSum = currentMethod.getArg("selectionSum");
		CLVariable sum = currentMethod.getLocalVar("sum");

		//loop over all selected guards
		ForLoop loop = new ForLoop(selection, false);
		//switch with all possible guards - in i-th iteration go to guardsTab[i] guard
		Switch _switch = new Switch(guardsTab.accessElement(selection.getName()));

		//sum rates and check if we reached the probability of update
		for (int i = 0; i < commands.length; ++i) {
			Rate rateSum = commands[i].getRateSum();
			_switch.addCase(new Expression(Integer.toString(i)));
			_switch.addExpression(i, ExpressionGenerator.createAssignment(newSum, convertPrismRate(translations, rateSum)));
		}
		loop.addExpression(_switch);
		// if(sum + newSum > selectionSum)
		Expression condition = createBinaryExpression(
		//selectionSum
				selectionSum.getSource(),
				// <
				Operator.LT,
				//sum + newSum
				createBinaryExpression(sum.getSource(), Operator.ADD, newSum.getSource()));
		IfElse ifElse = new IfElse(condition);
		Expression reduction = createBinaryExpression(selectionSum.getSource(), Operator.SUB_AUGM, sum.getSource());
		ifElse.addExpression(0, reduction.add(";"));
		ifElse.addExpression(0, new Expression("break;"));
		loop.addExpression(ifElse);
		loop.addExpression(createBinaryExpression(sum.getSource(), Operator.ADD_AUGM, newSum.getSource()).add(";"));

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
		//float sum
		CLVariable sum = new CLVariable(new StdVariableType(StdType.FLOAT), "sum");
		sum.setInitValue(StdVariableType.initialize(0.0f));
		currentMethod.addLocalVar(sum);

		//selection
		CLVariable selection = currentMethod.getLocalVar("selection");
		selection.setInitValue(StdVariableType.initialize(0));
	}
}
