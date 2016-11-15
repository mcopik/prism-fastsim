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

import static simulator.opencl.kernel.expression.ExpressionGenerator.convertPrismGuard;
import static simulator.opencl.kernel.expression.ExpressionGenerator.convertPrismRate;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createAssignment;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createBinaryExpression;
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;
import static simulator.opencl.kernel.expression.ExpressionGenerator.preIncrement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import simulator.opencl.automaton.command.Command;
import simulator.opencl.automaton.command.SynchronizedCommand;
import simulator.opencl.automaton.update.Rate;
import simulator.opencl.kernel.KernelGenerator.LocalVar;
import simulator.opencl.kernel.expression.ComplexKernelComponent;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.ExpressionGenerator;
import simulator.opencl.kernel.expression.ForLoop;
import simulator.opencl.kernel.expression.IfElse;
import simulator.opencl.kernel.expression.KernelComponent;
import simulator.opencl.kernel.expression.Method;
import simulator.opencl.kernel.expression.Switch;
import simulator.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.opencl.kernel.memory.ArrayType;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.opencl.kernel.memory.StructureType;
import simulator.opencl.kernel.memory.VariableTypeInterface;
import simulator.opencl.kernel.memory.StdVariableType.StdType;

public class SynchCommandGeneratorCTMC extends SynchCommandGenerator
{
	
	protected CLVariable varGuardCounter = null;
	protected CLVariable varGuardCurCounter = null;
	
	public SynchCommandGeneratorCTMC(KernelGenerator generator)
	{
		super(generator);
	}

	@Override
	protected void createSynchronizedStructures()
	{
		synchronizedStates = new LinkedHashMap<>();
		CLVariable size = null, array = null, guards = null;
		for (SynchronizedCommand cmd : synCommands) {
			StructureType type = new StructureType(String.format("SynState__%s", cmd.synchLabel));

			size = new CLVariable(new StdVariableType(StdType.FLOAT), "size");
			array = new CLVariable(new ArrayType(new StdVariableType(StdType.FLOAT), cmd.getModulesNum()), "moduleSize");
			guards = new CLVariable(new ArrayType(new StdVariableType(StdType.BOOL), cmd.getCmdsNum()), "guards");
			type.addVariable(size);
			type.addVariable(array);
			type.addVariable(guards);
			synchronizedStates.put(cmd.synchLabel, type);
		}
	}

	@Override
	public VariableTypeInterface kernelUpdateSizeType()
	{
		return new StdVariableType(StdType.FLOAT);
	}

	@Override
	protected Expression mainMethodSynUpdateCondition(CLVariable selection, CLVariable synSum, Expression sum)
	{
		return createBinaryExpression(selection.getSource(), Operator.LT, synSum.getSource());
	}

	@Override
	protected KernelComponent kernelCallUpdateRecomputeSelection(CLVariable selection, CLVariable synSum, Expression sum,
			CLVariable currentLabelSize)
	{
		return createBinaryExpression(selection.getSource(), Operator.SUB_AUGM, synSum.getSource());
	}

	/*********************************
	 * SYNCHRONIZED GUARDS CHECK
	 ********************************/

	@Override
	protected Method guardsCreateMethod(String label, int maxCommandsNumber)
	{
		/**
		 * If we need to use a counter, return this counter.
		 * Otherwise we don't return anything.
		 */
		CLVariable counterVariable = generator.kernelGetLocalVar(LocalVar.TRANSITIONS_COUNTER);
		return new Method(label,
				counterVariable != null ? counterVariable.varType : new StdVariableType(StdType.VOID)
				);
	}
	
	@Override
	protected Collection<CLVariable> guardsLocalVars(int moduleCount, int cmdsCount, int maxCmdsCount)
	{
		/**
		 * If we need to use a counter, declare two additional vars:
		 * counter and cur_counter
		 * 
		 * However, when only one module is present, optimize by not using counter
		 * and returning cur_counter.
		 */
		if(generator.kernelGetLocalVar(LocalVar.TRANSITIONS_COUNTER) != null) {
			List<CLVariable> vars = new ArrayList<>();

			if(moduleCount > 1) {
				if(varGuardCounter == null) {
					varGuardCounter = new CLVariable(new StdVariableType(0, maxCmdsCount), "counter");
					varGuardCounter.setInitValue(StdVariableType.initialize(1));
				}
				vars.add(varGuardCounter);
			} else {
				varGuardCounter = null;
			}

			if(varGuardCurCounter == null) {
				varGuardCurCounter = new CLVariable(new StdVariableType(0, cmdsCount), "cur_counter");
				varGuardCurCounter.setInitValue(StdVariableType.initialize(0));
			}
			vars.add(varGuardCurCounter);

			return vars;
		}
		return Collections.emptyList();
	}

	@Override
	protected CLVariable guardsLabelVar(int maxCommandsNumber)
	{
		return new CLVariable(new StdVariableType(StdType.FLOAT), "labelSize");
	}

	@Override
	protected CLVariable guardsCurrentVar(int maxCommandsNumber)
	{
		return new CLVariable(new StdVariableType(StdType.FLOAT), "currentSize");
	}
	
	@Override
	protected KernelComponent guardsBeforeModule(int module)
	{
		/**
		 * For i-th module, i > 0, reset current counter
		 */
		if(varGuardCurCounter != null && module > 0) {
			return createAssignment(varGuardCurCounter, fromString(0));
		} else {
			return new Expression();
		}
	}
	
	@Override
	protected KernelComponent guardsAfterModule(int module)
	{
		/**
		 * After module update count:
		 * counter *= cur_counter;
		 * 
		 * When counter has been optimized and removed,
		 * ignore it and simply return cur_counter later.
		 */
		if(varGuardCurCounter != null && varGuardCounter != null) {
			return createBinaryExpression(
					varGuardCounter.getSource(),
					Operator.MUL_AUGM,
					varGuardCurCounter.getSource());
		} else {
			return new Expression();
		}
	}

	@Override
	protected void guardsReturn(Method method)
	{
		/**
		 * Return count of transitions or don't return anything,
		 * when counting is not performed.
		 */
		if(varGuardCurCounter != null) {
			method.addReturn(varGuardCounter != null ? varGuardCounter : varGuardCurCounter);
		}
	}

	@Override
	protected void guardsAddGuard(ComplexKernelComponent parent, StateVector.Translations svTranslations, CLVariable guardArray, Command cmd, CLVariable size)
	{
		//TODO: optimize this by removing if and setting rate add:
		// rateSum += rate*guards, firstly setting guards[0] = PRISM_guard
		IfElse ifElse = new IfElse(convertPrismGuard(svTranslations, cmd.getGuard()));
		ifElse.addExpression(createBinaryExpression(size.getSource(), Operator.ADD_AUGM,
		//converted rate
				new Expression(convertPrismRate(svTranslations, cmd.getRateSum()))));
		ifElse.addExpression(createAssignment(guardArray, fromString(1)));
		// transition counting requested: current_counter++;
		if(generator.kernelGetLocalVar(LocalVar.TRANSITIONS_COUNTER) != null) {
			ifElse.addExpression( preIncrement(varGuardCurCounter) );
		}
		ifElse.addElse();
		ifElse.addExpression(1, createAssignment(guardArray, fromString(0)));
		parent.addExpression(ifElse);
	}

	/*********************************
	 * SYNCHRONIZED UPDATE
	 ********************************/

	@Override
	protected void updateAdditionalVars(Method parent, SynchronizedCommand cmd) throws KernelException
	{
		/**
		 * Used only if at least one module has more than one guard
		 * Selection of proper command in module.
		 */
		boolean flag = false;
		for (int i = 0; i < cmd.getModulesNum(); ++i) {
			if (cmd.getCommandNumber(i) > 1) {
				flag = true;
				break;
			}
		}
		if (flag) {
			CLVariable sum = new CLVariable(new StdVariableType(StdType.FLOAT), "sum");
			sum.setInitValue(StdVariableType.initialize(0.0f));
			parent.addLocalVar(sum);
			CLVariable newSum = new CLVariable(new StdVariableType(StdType.FLOAT), "newSum");
			newSum.setInitValue(StdVariableType.initialize(0.0f));
			parent.addLocalVar(newSum);
		}

	}

	@Override
	protected void updateBeforeUpdateLabel(Method parent, StateVector.Translations translations,
			SynchronizedCommand cmd, int moduleNumber, CLVariable guardsTab, CLVariable guard,
			CLVariable moduleSize, CLVariable totalSize, CLVariable probability)
	{
		/**
		 * Divide total size and compute the size of rest of modules.
		 */
		parent.addExpression(createBinaryExpression(totalSize.getSource(), Operator.DIV_AUGM, moduleSize.getSource()));
		/**
		 * FROM prob in [0,allModulesSize]
		 * TO prob in [0,moduleSize]
		 */
		parent.addExpression(createBinaryExpression(probability.getSource(), Operator.DIV_AUGM, totalSize.getSource()));
		/**
		 * float sum = 0.0f;
		 * for(;;guard++) {
		 *  switch(guard){
		 *  case i:
		 * 	sum += guards[moduleOffset+guardCounter]*i_cmd_size;
		 * 	if(guardsSelection == guard)
		 * 		break;
		 * }
		 * switch(guardCounter)...
		 */
		CLVariable sum = parent.getLocalVar("sum");
		CLVariable newSum = parent.getLocalVar("newSum");
		int cmdSum = 0;
		for (int i = 0; i < moduleNumber; ++i) {
			cmdSum += cmd.getCommandNumber(i);
		}
		CLVariable guardFlag = guardsTab.accessElement(createBinaryExpression(guard.getSource(), Operator.ADD, fromString(cmdSum)));
		if (cmd.getCommandNumber(moduleNumber) > 1) {
			parent.addExpression(createAssignment(guard, fromString(0)));
			parent.addExpression(createAssignment(sum, fromString(0.0f)));
			parent.addExpression(createAssignment(newSum, fromString(0.0f)));
			/**
			 * Select guard
			 */
			ForLoop guardSelectionLoop = new ForLoop(guard, false);
			Switch _switch = new Switch(guard);
			for (int i = 0; i < cmd.getCommandNumber(moduleNumber); ++i) {
				Rate rateSum = cmd.getCommand(moduleNumber, i).getRateSum();
				Expression convertedRate = ExpressionGenerator.convertPrismRate(translations, rateSum);
				_switch.addCase(new Expression(Integer.toString(i)));
				_switch.addExpression(i, ExpressionGenerator.createAssignment(newSum, convertedRate));
			}
			guardSelectionLoop.addExpression(_switch);
			/**
			 * Multiply newSum by guardFlag -> if guard is inactive, then it is 0.
			 */
			guardSelectionLoop.addExpression(createBinaryExpression(newSum.getSource(), Operator.MUL_AUGM, guardFlag.getSource()));

			/**
			 * Check whether sum + newSum is greater than rate.
			 */
			// if(sum + newSum > selectionSum)
			Expression condition = createBinaryExpression(
			//selectionSum
					probability.getSource(),
					// <
					Operator.LT,
					//sum + newSum
					createBinaryExpression(sum.getSource(), Operator.ADD, newSum.getSource()));
			IfElse ifElse = new IfElse(condition);
			Expression reduction = createBinaryExpression(probability.getSource(), Operator.SUB_AUGM, sum.getSource());
			ifElse.addExpression(0, reduction.add(";"));
			ifElse.addExpression(0, new Expression("break;"));
			guardSelectionLoop.addExpression(ifElse);
			guardSelectionLoop.addExpression(createBinaryExpression(sum.getSource(), Operator.ADD_AUGM, newSum.getSource()).add(";"));
			parent.addExpression(guardSelectionLoop);
		} else {
			// Restart guard variable - only one to select
			parent.addExpression(createAssignment(guard, fromString(0)));
		}
	}

	protected void updateAfterUpdateLabel(ComplexKernelComponent parent, CLVariable guard, CLVariable moduleSize, CLVariable totalSize,
			CLVariable probability)
	{
		/**
		 * FROM prob in [0,currentModuleSize]
		 * TO prob in [0,modulesSize]
		 * 
		 * obtain that by multiplying with: totalSize, storing  totalSize / currentModuleSize currently
		 * later update totalSize to store original value
		 */
		parent.addExpression(createBinaryExpression(probability.getSource(), Operator.MUL_AUGM, totalSize.getSource()));
		parent.addExpression(createBinaryExpression(totalSize.getSource(), Operator.MUL_AUGM, moduleSize.getSource()));
	}

	@Override
	protected CLVariable updateLabelMethodGuardCounter(SynchronizedCommand cmd)
	{
		return null;
	}

	@Override
	protected CLVariable updateLabelMethodGuardSelection(SynchronizedCommand cmd, CLVariable guard)
	{
		CLVariable guardCounter = new CLVariable(new StdVariableType(0, cmd.getCmdsNum()), "guardSelection");
		guardCounter.setInitValue(guard.getSource());
		return guardCounter;
	}

	@Override
	protected void updateLabelMethodSelectGuard(Method currentMethod, ComplexKernelComponent parent, CLVariable guardSelection,
			CLVariable guardCounter, int moduleOffset)	
	{

	}
}
