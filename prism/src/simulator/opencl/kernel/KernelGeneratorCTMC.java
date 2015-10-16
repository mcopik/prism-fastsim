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

import static simulator.opencl.kernel.expression.ExpressionGenerator.addComma;
import static simulator.opencl.kernel.expression.ExpressionGenerator.addParentheses;
import static simulator.opencl.kernel.expression.ExpressionGenerator.convertPrismGuard;
import static simulator.opencl.kernel.expression.ExpressionGenerator.convertPrismRate;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createAssignment;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createBinaryExpression;
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;
import static simulator.opencl.kernel.expression.ExpressionGenerator.postIncrement;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import parser.ast.ExpressionLiteral;
import prism.Preconditions;
import prism.PrismLangException;
import simulator.opencl.RuntimeConfig;
import simulator.opencl.automaton.AbstractAutomaton;
import simulator.opencl.automaton.command.Command;
import simulator.opencl.automaton.command.SynchronizedCommand;
import simulator.opencl.automaton.update.Rate;
import simulator.opencl.kernel.expression.ComplexKernelComponent;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.ExpressionGenerator;
import simulator.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.opencl.kernel.expression.ForLoop;
import simulator.opencl.kernel.expression.IfElse;
import simulator.opencl.kernel.expression.Method;
import simulator.opencl.kernel.expression.Switch;
import simulator.opencl.kernel.memory.ArrayType;
import simulator.opencl.kernel.memory.CLValue;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.opencl.kernel.memory.StdVariableType.StdType;
import simulator.opencl.kernel.memory.StructureType;
import simulator.sampler.SamplerBoolean;
import simulator.sampler.SamplerBoundedUntilCont;
import simulator.sampler.SamplerDouble;

public class KernelGeneratorCTMC extends KernelGenerator
{
	/**
	 * Variable containing time of leaving state.
	 */
	protected CLVariable varUpdatedTime = null;

	/**
	 * Constructor for CTMC kernel generator.
	 * @param model
	 * @param properties
	 * @param rewardProperties
	 * @param config
	 */
	public KernelGeneratorCTMC(AbstractAutomaton model, List<SamplerBoolean> properties, List<SamplerDouble> rewardProperties, RuntimeConfig config)
			throws KernelException
	{
		super(model, properties, rewardProperties, config);
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

	/*********************************
	 * MAIN METHOD
	 ********************************/
	@Override
	public void mainMethodDefineLocalVars(Method currentMethod) throws KernelException
	{
		//time
		varTime = new CLVariable(new StdVariableType(StdType.FLOAT), "time");
		varTime.setInitValue(StdVariableType.initialize(0.0f));
		currentMethod.addLocalVar(varTime);
		//updated time
		if (timingProperty) {
			varUpdatedTime = new CLVariable(new StdVariableType(StdType.FLOAT), "updatedTime");
			varUpdatedTime.setInitValue(StdVariableType.initialize(0.0f));
			currentMethod.addLocalVar(varUpdatedTime);
		}
		//number of transitions
		varSelectionSize = new CLVariable(new StdVariableType(StdType.FLOAT), "selectionSize");
		varSelectionSize.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(varSelectionSize);
		if (hasSynchronized) {
			//number of transitions
			varSynSelectionSize = new CLVariable(new StdVariableType(StdType.FLOAT), "synSelectionSize");
			varSynSelectionSize.setInitValue(StdVariableType.initialize(0));
			currentMethod.addLocalVar(varSynSelectionSize);
		}
	}

	@Override
	protected void mainMethodUpdateTimeBefore(Method currentMethod, ComplexKernelComponent parent)
	{
		CLValue random = config.prngType.getRandomUnifFloat(fromString(1));
		Expression substrRng = createBinaryExpression(fromString(1),
		//1 - random()
				Operator.SUB, random.getSource());
		substrRng = new Expression(String.format("log(%s)", substrRng.getSource()));
		Expression sum = null;
		//for synchronized commands - selection_size + selection_syn
		if (hasSynchronized && hasNonSynchronized) {
			sum = createBinaryExpression(varSelectionSize.getSource(), Operator.ADD, varSynSelectionSize.getSource());
			addParentheses(sum);
		} else if (hasNonSynchronized) {
			sum = varSelectionSize.getSource();
		} else {
			sum = varSynSelectionSize.getSource();
		}
		substrRng = createBinaryExpression(substrRng, Operator.DIV, sum);
		// updated = time - new value
		// OR time -= new value
		if (timingProperty) {
			substrRng = createBinaryExpression(varTime.getSource(), Operator.SUB, substrRng);
			parent.addExpression(createAssignment(varUpdatedTime, substrRng));
		} else {
			parent.addExpression(addComma(createBinaryExpression(varTime.getSource(), Operator.SUB_AUGM, substrRng)));
		}
	}

	@Override
	protected void mainMethodUpdateTimeAfter(Method currentMethod, ComplexKernelComponent parent)
	{
		// time = updated_time;
		if (timingProperty) {
			parent.addExpression(createAssignment(varTime, varUpdatedTime));
		}
	}

	@Override
	protected int mainMethodRandomsPerIteration()
	{
		//1 for update selection, one for time generation
		return 2;
	}

	@Override
	protected void mainMethodFirstUpdateProperties(ComplexKernelComponent parent)
	{
		/**
		 * Special case - the translations are prepared for StateVector * sv,
		 * but this one case works in main method - we have to use the StateVector instance directly.
		 */
		Map<String, String> oldTranslations = new HashMap<>(svPtrTranslations);
		CLVariable stateVector = parent.getLocalVar("stateVector");
		for (CLVariable var : stateVectorType.getFields()) {
			String name = var.varName.substring(STATE_VECTOR_PREFIX.length());
			CLVariable second = stateVector.accessField(var.varName);
			svPtrTranslations.put(name, second.varName);
		}

		/**
		 * For the case of bounded until in CTMC, we have to check initial state at time 0.
		 */
		for (int i = 0; i < properties.size(); ++i) {
			if (properties.get(i) instanceof SamplerBoundedUntilCont) {
				SamplerBoundedUntilCont prop = (SamplerBoundedUntilCont) properties.get(i);
				CLVariable propertyVar = varPropertiesArray.accessElement(fromString(i));
				if (prop.getLowBound() == 0.0) {
					IfElse ifElse = createPropertyCondition(propertyVar, false, prop.getRightSide().toString(), true);
					parent.addExpression(ifElse);
				}
				/**
				 * we do not have to check left side if it is constant 'true'
				 */
				else if (!(prop.getLeftSide() instanceof ExpressionLiteral)) {
					IfElse ifElse = createPropertyCondition(propertyVar, true, prop.getLeftSide().toString(), false);
					parent.addExpression(ifElse);
				}
			}
		}

		svPtrTranslations = oldTranslations;
	}

	@Override
	protected void mainMethodCallNonsynUpdate(ComplexKernelComponent parent)
	{
		CLValue random = config.prngType.getRandomFloat(fromString(0), varSelectionSize.getSource());
		parent.addExpression(mainMethodCallNonsynUpdate(random));
	}

	/**
	 * Private helper method - generate call to non-synchronized update in CTMC. 
	 * @param rnd
	 * @return call expression
	 */
	private Expression mainMethodCallNonsynUpdate(CLValue rnd)
	{
		Method update = helperMethods.get(KernelMethods.PERFORM_UPDATE);
		Expression call = update.callMethod(
		//stateVector
				varStateVector.convertToPointer(),
				//non-synchronized guards tab
				varGuardsTab,
				//random float [0,1]
				rnd);
		return timingProperty ? call : createAssignment(varLoopDetection, call);
	}

	@Override
	protected CLVariable mainMethodBothUpdatesSumVar()
	{
		return new CLVariable(new StdVariableType(StdType.FLOAT), "synSum");
	}

	@Override
	protected CLVariable mainMethodSelectionVar(Expression selectionSize)
	{
		CLVariable selection = new CLVariable(new StdVariableType(StdType.FLOAT), "selection");
		Expression rndNumber = null;
		if (config.prngType.numbersPerRandomize() == 2) {
			rndNumber = fromString(0);
		}
		//we assume that this is an even number!
		else {
			rndNumber = new Expression(String.format("%s%%%d",
			//pathLength*2
					addParentheses(createBinaryExpression(varPathLength.getSource(), Operator.MUL,
					// % numbersPerRandom
							fromString(2))).toString(), config.prngType.numbersPerRandomize()));
		}
		selection.setInitValue(config.prngType.getRandomFloat(fromString(rndNumber), selectionSize));
		return selection;
	}

	@Override
	protected IfElse mainMethodBothUpdatesCondition(CLVariable selection)
	{
		Expression condition = createBinaryExpression(selection.getSource(), Operator.LT,
		//random < selectionSize
				varSelectionSize.getSource());
		IfElse ifElse = new IfElse(condition);
		/**
		 * if(selection < selectionSize/sum)
		 * callNonsynUpdate(..)
		 */
		ifElse.addExpression(mainMethodCallNonsynUpdate(selection));
		return ifElse;
	}

	@Override
	protected Expression mainMethodSynUpdateCondition(CLVariable selection, CLVariable synSum, Expression sum)
	{
		return createBinaryExpression(selection.getSource(), Operator.LT, synSum.getSource());
	}

	@Override
	protected void mainMethodSynRecomputeSelection(ComplexKernelComponent parent, CLVariable selection, CLVariable synSum, Expression sum,
			CLVariable currentLabelSize)
	{
		parent.addExpression(createBinaryExpression(selection.getSource(), Operator.SUB_AUGM, synSum.getSource()));
	}

	@Override
	protected void mainMethodUpdateProperties(ComplexKernelComponent parent)
	{
		Expression call = null;
		if (timingProperty) {
			call = helperMethods.get(KernelMethods.UPDATE_PROPERTIES)
					.callMethod(varStateVector.convertToPointer(), varPropertiesArray, varTime, varUpdatedTime);
		} else {
			call = helperMethods.get(KernelMethods.UPDATE_PROPERTIES).callMethod(varStateVector.convertToPointer(), varPropertiesArray, varTime);
		}
		IfElse ifElse = new IfElse(call);
		ifElse.addExpression(0, new Expression("break;\n"));
		parent.addExpression(ifElse);
	}

	/*********************************
	 * NON-SYNCHRONIZED GUARDS CHECK
	 ********************************/

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
		CLVariable tabPos = guardsTab.accessElement(postIncrement(counter));

		IfElse ifElse = new IfElse(new Expression(guard));
		ifElse.addExpression(0, createAssignment(tabPos, fromString(position)));
		Expression sumExpr = createBinaryExpression(sum.getSource(), Operator.ADD_AUGM,
				fromString(convertPrismRate(svPtrTranslations, null, commands[position].getRateSum())));
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

	/*********************************
	 * NON-SYNCHRONIZED UPDATE
	 ********************************/

	@Override
	protected void updateMethodPerformSelection(Method currentMethod) throws KernelException
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
			_switch.addExpression(i, ExpressionGenerator.createAssignment(newSum, fromString(convertPrismRate(svPtrTranslations, null, rateSum))));
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

	/*********************************
	 * PROPERTY METHODS
	 ********************************/

	@Override
	protected void propertiesMethodTimeArg(Method currentMethod) throws KernelException
	{
		// TODO: is time really necessary for non timed properties?
		CLVariable varTime = new CLVariable(new StdVariableType(StdType.FLOAT), "time");
		currentMethod.addArg(varTime);
		if (timingProperty) {
			CLVariable varUpdatedTime = new CLVariable(new StdVariableType(StdType.FLOAT), "updated_time");
			currentMethod.addArg(varUpdatedTime);
		}
	}

	@Override
	protected void propertiesMethodAddBoundedUntil(Method currentMethod, ComplexKernelComponent parent, SamplerBoolean property, CLVariable propertyVar)
			throws PrismLangException
	{
		CLVariable updTime = currentMethod.getArg("updated_time");
		SamplerBoundedUntilCont prop = (SamplerBoundedUntilCont) property;

		String propertyStringRight = visitPropertyExpression(prop.getRightSide()).toString();
		String propertyStringLeft = visitPropertyExpression(prop.getLeftSide()).toString();
		/**
		 * if(updated_time > upper_bound)
		 */
		IfElse ifElse = null;
		if (!Double.isInfinite(prop.getUpperBound())) {
			ifElse = new IfElse(createBinaryExpression(updTime.getSource(), Operator.GT, fromString(prop.getUpperBound())));
			/**
			 * if(right_side == true) -> true
			 * else -> false
			 */
			IfElse rhsCheck = createPropertyCondition(propertyVar, false, propertyStringRight, true);
			createPropertyCondition(rhsCheck, propertyVar, false, null, false);
			ifElse.addExpression(rhsCheck);
		}

		/**
		 * else if(updated_time < low_bound)
		 */
		if (prop.getLowBound() != 0.0) {
			int position = 0;
			Expression condition = createBinaryExpression(updTime.getSource(), Operator.LE,
			// updated_time < lb
					fromString(prop.getLowBound()));
			if (ifElse != null) {
				ifElse.addElif(condition);
				position = 1;
			} else {
				ifElse = new IfElse(condition);
				position = 0;
			}
			/**
			 * if(left_side == false) -> false
			 */
			if (!(prop.getLeftSide() instanceof ExpressionLiteral)) {
				IfElse lhsCheck = createPropertyCondition(propertyVar, true, propertyStringLeft, false);
				ifElse.addExpression(position, lhsCheck);
			}
		}

		/**
		 * Else - inside the interval
		 */

		/**
		 * if(right_side == true) -> true
		 * else if(left_side == false) -> false
		 */
		IfElse betweenBounds = createPropertyCondition(propertyVar, false, propertyStringRight, true);
		if (!(prop.getLeftSide() instanceof ExpressionLiteral)) {
			createPropertyCondition(betweenBounds, propertyVar, true, propertyStringLeft, false);
		}

		/**
		 * No condition before, just add this check to method.
		 */
		if (ifElse == null) {
			parent.addExpression(betweenBounds);
		}
		/**
		 * Add 'else'
		 */
		else {
			ifElse.addElse();
			ifElse.addExpression(ifElse.size() - 1, betweenBounds);
			parent.addExpression(ifElse);
		}
	}

	/*********************************
	 * SYNCHRONIZED GUARDS CHECK
	 ********************************/

	@Override
	protected Method guardsSynCreateMethod(String label, int maxCommandsNumber)
	{
		Method currentMethod = new Method(label, new StdVariableType(StdType.FLOAT));
		return currentMethod;
	}

	@Override
	protected CLVariable guardsSynLabelVar(int maxCommandsNumber)
	{
		return new CLVariable(new StdVariableType(StdType.FLOAT), "labelSize");
	}

	@Override
	protected CLVariable guardsSynCurrentVar(int maxCommandsNumber)
	{
		return new CLVariable(new StdVariableType(StdType.FLOAT), "currentSize");
	}

	@Override
	protected void guardsSynAddGuard(ComplexKernelComponent parent, CLVariable guardArray, Command cmd, CLVariable size)
	{
		//TODO: optimize this by removing if and setting rate add:
		// rateSum += rate*guards
		IfElse ifElse = new IfElse(new Expression(convertPrismGuard(svPtrTranslations, cmd.getGuard().toString())));
		ifElse.addExpression(createBinaryExpression(size.getSource(), Operator.ADD_AUGM,
		//converted rate
				new Expression(convertPrismRate(svPtrTranslations, null, cmd.getRateSum()))));
		ifElse.addExpression(createAssignment(guardArray, fromString(1)));
		ifElse.addElse();
		ifElse.addExpression(1, createAssignment(guardArray, fromString(0)));
		parent.addExpression(ifElse);
	}

	/*********************************
	 * SYNCHRONIZED UPDATE
	 ********************************/

	@Override
	protected void updateSynAdditionalVars(Method parent, SynchronizedCommand cmd) throws KernelException
	{
		/**
		 * Used only if at least one module has more than one guard.
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
	protected void updateSynBeforeUpdateLabel(Method parent, SynchronizedCommand cmd, int moduleNumber, CLVariable guardsTab, CLVariable guard,
			CLVariable moduleSize, CLVariable totalSize, CLVariable probability)
	{
		/**
		 * Divide total size and compute the size of rest of modules.
		 */
		parent.addExpression(createBinaryExpression(totalSize.getSource(), Operator.DIV_AUGM, moduleSize.getSource()));
		/**
		 * FROM prob in [0,moduleLength]
		 * TO prob in [0,moduleLength]
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
				_switch.addCase(new Expression(Integer.toString(i)));
				_switch.addExpression(i, ExpressionGenerator.createAssignment(newSum, fromString(rateSum)));
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
		}
	}

	protected void updateSynAfterUpdateLabel(ComplexKernelComponent parent, CLVariable guard, CLVariable moduleSize, CLVariable totalSize,
			CLVariable probability)
	{
		/**
		 * FROM prob in [0,moduleLength]
		 * TO prob in [0,moduleLength]
		 */
		parent.addExpression(createBinaryExpression(probability.getSource(), Operator.MUL_AUGM, totalSize.getSource()));
	}

	@Override
	protected CLVariable updateSynLabelMethodGuardCounter(SynchronizedCommand cmd)
	{
		return null;
	}

	@Override
	protected CLVariable updateSynLabelMethodGuardSelection(SynchronizedCommand cmd, CLVariable guard)
	{
		CLVariable guardCounter = new CLVariable(new StdVariableType(0, cmd.getCmdsNum()), "guardSelection");
		guardCounter.setInitValue(guard.getSource());
		return guardCounter;
	}

	@Override
	protected void updateSynLabelMethodSelectGuard(Method currentMethod, ComplexKernelComponent parent, CLVariable guardSelection, CLVariable guardCounter,
			int moduleOffset)
	{

	}

}