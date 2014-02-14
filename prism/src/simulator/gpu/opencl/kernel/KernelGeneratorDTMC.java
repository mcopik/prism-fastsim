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
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.convertPrismAction;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.convertPrismGuard;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.convertPrismRate;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.createAssignment;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.createBasicExpression;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.fromString;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.functionCall;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.postIncrement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import parser.ast.ExpressionLiteral;
import prism.Preconditions;
import simulator.gpu.automaton.AbstractAutomaton;
import simulator.gpu.automaton.command.Command;
import simulator.gpu.automaton.command.SynchronizedCommand;
import simulator.gpu.automaton.update.Rate;
import simulator.gpu.automaton.update.Update;
import simulator.gpu.opencl.kernel.expression.ComplexKernelComponent;
import simulator.gpu.opencl.kernel.expression.Expression;
import simulator.gpu.opencl.kernel.expression.ExpressionGenerator;
import simulator.gpu.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.gpu.opencl.kernel.expression.ForLoop;
import simulator.gpu.opencl.kernel.expression.IfElse;
import simulator.gpu.opencl.kernel.expression.Method;
import simulator.gpu.opencl.kernel.expression.Switch;
import simulator.gpu.opencl.kernel.memory.ArrayType;
import simulator.gpu.opencl.kernel.memory.CLValue;
import simulator.gpu.opencl.kernel.memory.CLVariable;
import simulator.gpu.opencl.kernel.memory.PointerType;
import simulator.gpu.opencl.kernel.memory.RValue;
import simulator.gpu.opencl.kernel.memory.StdVariableType;
import simulator.gpu.opencl.kernel.memory.StdVariableType.StdType;
import simulator.gpu.opencl.kernel.memory.StructureType;
import simulator.sampler.Sampler;
import simulator.sampler.SamplerBoolean;
import simulator.sampler.SamplerBoundedUntilDisc;

public class KernelGeneratorDTMC extends KernelGenerator
{
	protected int maximalNumberOfSynchsUpdates = 0;
	protected boolean timingProperty = false;

	public KernelGeneratorDTMC(AbstractAutomaton model, List<Sampler> properties, KernelConfig config)
	{
		super(model, properties, config);
	}

	@Override
	public void mainMethodDefineLocalVars(Method currentMethod) throws KernelException
	{
		//time
		varTime = new CLVariable(new StdVariableType(0, config.maxPathLength), "time");
		varTime.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(varTime);
		//number of transitions
		varSelectionSize = new CLVariable(new StdVariableType(0, model.commandsNumber()), "selectionSize");
		varSelectionSize.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(varSelectionSize);
		//number of synchronized transitions
		if (synCommands != null) {
			for (SynchronizedCommand cmd : synCommands) {
				maximalNumberOfSynchsUpdates += cmd.getMaxCommandsNum();
			}
			varSynSelectionSize = new CLVariable(new StdVariableType(0, maximalNumberOfSynchsUpdates - 1), "selectionSynSize");
			varSynSelectionSize.setInitValue(StdVariableType.initialize(0));
			currentMethod.addLocalVar(varSynSelectionSize);
		}
	}

	@Override
	protected void mainMethodUpdateTimeBefore(Method currentMethod, ComplexKernelComponent parent)
	{
		parent.addExpression(addComma(postIncrement(currentMethod.getLocalVar("time"))));
	}

	@Override
	protected void mainMethodUpdateTimeAfter(Method currentMethod, ComplexKernelComponent parent)
	{
		//don't need to do anything!
	}

	@Override
	protected void guardsMethodCreateLocalVars(Method currentMethod) throws KernelException
	{
		//don't need to do anything!
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
		ifElse.addExpression(0, createAssignment(tabPos, fromString(position)));
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
		//currentMethod.addExpression(new Expression(
		//	"if(get_global_id(0)<10)printf(\"before %f %d %d %f\\n\",selectionSum,selection,numberOfCommands,((float)selection) / numberOfCommands);"));
		// selection = floor(selectionSum)
		//currentMethod.addExpression(ExpressionGenerator.createAssignment(selection, ExpressionGenerator.functionCall("floor", sum.getName())));

		// selectionSum = numberOfCommands * ( selectionSum - selection/numberOfCommands);
		Expression divideSelection = createBasicExpression(selection.cast("float"), Operator.DIV, number.getSource());
		Expression subSum = createBasicExpression(sum.getSource(), Operator.SUB, divideSelection);
		ExpressionGenerator.addParentheses(subSum);
		Expression asSum = createBasicExpression(number.getSource(), Operator.MUL, subSum);
		currentMethod.addExpression(ExpressionGenerator.createAssignment(sum, asSum));
		//currentMethod.addExpression(new Expression("if(get_global_id(0)<10)printf(\"after %f %d %d\\n\",selectionSum,selection,numberOfCommands);"));
		//currentMethod.addExpression(ExpressionGenerator.createAssignment(selection, guardsTab.getName()));
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
		String selectionExpression = String.format("floor(%s)", createBasicExpression(sum.getSource(), Operator.MUL, number.getSource()).toString());
		selection.setInitValue(new Expression(selectionExpression));
	}

	@Override
	protected void propertiesMethodTimeArg(Method currentMethod) throws KernelException
	{
		for (Sampler sampler : properties) {
			if (sampler instanceof SamplerBoundedUntilDisc) {
				timingProperty = true;
				break;
			}
		}
		if (timingProperty) {
			CLVariable time = new CLVariable(new StdVariableType(0, config.maxPathLength), "time");
			currentMethod.addArg(time);
		}
	}

	@Override
	protected void propertiesMethodAddBoundedUntil(Method currentMethod, ComplexKernelComponent parent, SamplerBoolean property, CLVariable propertyVar)
	{
		CLVariable time = currentMethod.getArg("time");
		SamplerBoundedUntilDisc prop = (SamplerBoundedUntilDisc) property;
		/**
		 * if(time > upper_bound)
		 */
		IfElse ifElse = new IfElse(createBasicExpression(time.getSource(), Operator.GE, fromString(prop.getUpperBound())));
		/**
		 * if(right_side == true) -> true
		 * else -> false
		 */
		IfElse rhsCheck = createPropertyCondition(propertyVar, false, prop.getRightSide().toString(), true);
		createPropertyCondition(rhsCheck, propertyVar, false, null, false);
		ifElse.addExpression(rhsCheck);
		/**
		 * Else -> check RHS and LHS
		 */
		ifElse.addElse();
		/**
		 * if(right_side == true) -> true
		 * else if(left_side == false) -> false
		 */
		IfElse betweenBounds = createPropertyCondition(propertyVar, false, prop.getRightSide().toString(), true);
		if (!(prop.getLeftSide() instanceof ExpressionLiteral)) {
			createPropertyCondition(betweenBounds, propertyVar, true, prop.getLeftSide().toString(), false);
		}
		ifElse.addExpression(1, betweenBounds);
		parent.addExpression(ifElse);
	}

	@Override
	protected int mainMethodRandomsPerIteration()
	{
		return 1;
	}

	protected void mainMethodCallBothUpdates(ComplexKernelComponent parent)
	{
		//selection
		CLVariable selection = new CLVariable(new StdVariableType(StdType.FLOAT), "selection");
		Expression sum = createBasicExpression(varSelectionSize.getSource(), Operator.ADD, varSynSelectionSize.getSource());
		addParentheses(sum);

		Expression rndNumber = new Expression(String.format("%s%%%d", varPathLength.getSource().toString(), config.prngType.numbersPerRandomize()));
		selection.setInitValue(config.prngType.getRandomUnifFloat(rndNumber));
		parent.addExpression(selection.getDefinition());
		parent.addExpression(new Expression(
				"if(globalID<5)printf(\"%d %d %d %d %d %f\\n\",globalID,selectionSize,selectionSynSize,synchState_serve.size,synchState_serve1.size,selection);"));
		Expression condition = createBasicExpression(selection.getSource(), Operator.LT,
		//nonSyn/(syn+nonSyn)
				createBasicExpression(varSelectionSize.cast("float"), Operator.DIV, sum));
		IfElse ifElse = new IfElse(condition);
		/**
		 * if(selection < selectionSize/sum)
		 * callNonsynUpdate(..)
		 */
		Method update = helperMethods.get(KernelMethods.PERFORM_UPDATE);
		ifElse.addExpression(update.callMethod(
		//stateVector
				varStateVector.convertToPointer(),
				//non-synchronized guards tab
				varGuardsTab,
				//select 
				selection,
				//numberOfSynchs
				new RValue(sum)));
		/**
		 * else
		 * callSynUpdate()
		 */
		//TODO: add case for one
		ifElse.addElse();
		CLVariable counter = new CLVariable(new StdVariableType(0, synCommands.length), "synSelection");
		counter.setInitValue(StdVariableType.initialize(0));
		CLVariable synSum = new CLVariable(new StdVariableType(0, maximalNumberOfSynchsUpdates), "synSum");
		synSum.setInitValue(varSelectionSize);
		ifElse.addExpression(1, counter.getDefinition());
		ifElse.addExpression(1, synSum.getDefinition());
		ForLoop loop = new ForLoop(counter, 0, synCommands.length);
		Switch _switch = new Switch(counter);
		for (int i = 0; i < synCommands.length; ++i) {
			CLVariable currentSize = varSynchronizedStates[i].accessField("size");
			_switch.addCase(fromString(i));
			_switch.addCommand(i, createBasicExpression(synSum.getSource(), Operator.ADD_AUGM,
			// synSum += synchState__label.size;
					currentSize.getSource()));
		}
		loop.addExpression(_switch);
		IfElse checkSelection = new IfElse(createBasicExpression(selection.getSource(), Operator.LT,
		//probability < synSum/sum
				createBasicExpression(synSum.cast("float"), Operator.DIV, sum)));
		_switch = new Switch(counter);
		Expression probUpdate = createBasicExpression(selection.getSource(), Operator.MUL, sum);
		probUpdate = createBasicExpression(probUpdate, Operator.SUB, synSum.getSource());
		for (int i = 0; i < synCommands.length; ++i) {
			CLVariable currentSize = varSynchronizedStates[i].accessField("size");
			_switch.addCase(fromString(i));
			_switch.addCommand(i, createBasicExpression(synSum.getSource(), Operator.SUB_AUGM,
			// synSum += synchState__label.size;
					currentSize.getSource()));
			_switch.addCommand(i, createAssignment(selection, probUpdate));
			_switch.addCommand(i, createBasicExpression(selection.getSource(), Operator.DIV_AUGM, currentSize.getSource()));
		}
		checkSelection.addExpression(_switch);
		checkSelection.addExpression("break;\n");
		loop.addExpression(checkSelection);
		ifElse.addExpression(1, loop);
		_switch = new Switch(counter);
		for (int i = 0; i < synCommands.length; ++i) {
			_switch.addCase(fromString(i));
			_switch.addCommand(i, synchronizedUpdates.get(i).callMethod(
			//&stateVector
					varStateVector.convertToPointer(),
					//&synchState__label
					varSynchronizedStates[i].convertToPointer(),
					//probability
					selection));
		}
		ifElse.addExpression(1, _switch);

		parent.addExpression(ifElse);
		parent.addExpression(new Expression(
				"if(globalID<5)printf(\"%d %d %d %d\\n\",globalID,stateVector.__STATE_VECTOR_q,stateVector.__STATE_VECTOR_s,stateVector.__STATE_VECTOR_s2);"));
	}

	protected void mainMethodCallSynUpdate(ComplexKernelComponent parent)
	{
		CLVariable selection = new CLVariable(new StdVariableType(StdType.FLOAT), "selection");
		Expression rndNumber = new Expression(String.format("%s%%%d", varPathLength.getSource().toString(), config.prngType.numbersPerRandomize()));
		selection.setInitValue(config.prngType.getRandomUnifFloat(rndNumber));
		parent.addExpression(selection.getDefinition());
		parent.addExpression(new Expression("if(globalID<5)printf(\"selection %d %f\\n\",globalID,selection);"));
		CLVariable counter = new CLVariable(new StdVariableType(0, synCommands.length), "synSelection");
		counter.setInitValue(StdVariableType.initialize(0));
		CLVariable synSum = new CLVariable(new StdVariableType(0, maximalNumberOfSynchsUpdates), "synSum");
		synSum.setInitValue(StdVariableType.initialize(0));
		parent.addExpression(counter.getDefinition());
		parent.addExpression(synSum.getDefinition());
		ForLoop loop = new ForLoop(counter, 0, synCommands.length);
		Switch _switch = new Switch(counter);
		for (int i = 0; i < synCommands.length; ++i) {
			CLVariable currentSize = varSynchronizedStates[i].accessField("size");
			_switch.addCase(fromString(i));
			_switch.addCommand(i, createBasicExpression(synSum.getSource(), Operator.ADD_AUGM,
			// synSum += synchState__label.size;
					currentSize.getSource()));
		}
		loop.addExpression(_switch);
		IfElse checkSelection = new IfElse(createBasicExpression(selection.getSource(), Operator.LT,
		//probability < synSum/sum
				createBasicExpression(synSum.cast("float"), Operator.DIV, varSynSelectionSize.getSource())));
		_switch = new Switch(counter);
		Expression probUpdate = createBasicExpression(selection.getSource(), Operator.MUL, varSynSelectionSize.getSource());
		probUpdate = createBasicExpression(probUpdate, Operator.SUB, synSum.getSource());
		for (int i = 0; i < synCommands.length; ++i) {
			CLVariable currentSize = varSynchronizedStates[i].accessField("size");
			_switch.addCase(fromString(i));
			_switch.addCommand(i, createBasicExpression(synSum.getSource(), Operator.SUB_AUGM,
			// synSum += synchState__label.size;
					currentSize.getSource()));
			_switch.addCommand(i, createAssignment(selection, probUpdate));
			_switch.addCommand(i, createBasicExpression(selection.getSource(), Operator.DIV_AUGM, currentSize.getSource()));
		}
		checkSelection.addExpression(_switch);
		checkSelection.addExpression("break;\n");
		loop.addExpression(checkSelection);
		parent.addExpression(loop);
		_switch = new Switch(counter);
		for (int i = 0; i < synCommands.length; ++i) {
			_switch.addCase(fromString(i));
			_switch.addCommand(i, synchronizedUpdates.get(i).callMethod(
			//&stateVector
					varStateVector.convertToPointer(),
					//&synchState__label
					varSynchronizedStates[i].convertToPointer(),
					//probability
					selection));
		}
		parent.addExpression(_switch);
	}

	protected void mainMethodCallNonsynUpdate(ComplexKernelComponent parent)
	{
		Method update = helperMethods.get(KernelMethods.PERFORM_UPDATE);
		Expression rndNumber = new Expression(String.format("%s%%%d", varPathLength.getSource().toString(), config.prngType.numbersPerRandomize()));
		CLValue random = config.prngType.getRandomUnifFloat(rndNumber);
		parent.addExpression(update.callMethod(
		//stateVector
				varStateVector.convertToPointer(),
				//non-synchronized guards tab
				varGuardsTab,
				//random float [0,1]
				random,
				//number of commands
				varSelectionSize));
	}

	@Override
	protected void mainMethodFirstUpdateProperties(ComplexKernelComponent parent)
	{
		//in case of DTMC, there is nothing to do
	}

	@Override
	protected void mainMethodUpdateProperties(ComplexKernelComponent parent)
	{
		Expression call = null;
		if (timingProperty) {
			call = helperMethods.get(KernelMethods.UPDATE_PROPERTIES).callMethod(varStateVector.convertToPointer(), varPropertiesArray, varTime);
		} else {
			call = helperMethods.get(KernelMethods.UPDATE_PROPERTIES).callMethod(varStateVector.convertToPointer(), varPropertiesArray);
		}
		String source = call.getSource();
		IfElse ifElse = new IfElse(new Expression(source.substring(0, source.indexOf(';'))));
		//		ifElse.addExpression(
		//				0,
		//				new Expression(
		//						"if(get_global_id(0) < 10)printf(\"%f %f %f %d %d\\n\",time,updatedTime,selectionSize,stateVector.__STATE_VECTOR_q,properties[0].propertyState);\n"));
		ifElse.addExpression(0, new Expression("break;\n"));
		parent.addExpression(ifElse);
	}

	@Override
	protected void createSynchronizedStructures()
	{
		synchronizedStates = new HashMap<>();
		CLVariable size;// = new CLVariable("size",new StdVariableType(0, maximal)
		CLVariable array;
		long sum = 1, max = 0;
		int cmdNumber = 0;
		for (SynchronizedCommand cmd : synCommands) {
			StructureType type = new StructureType(String.format("SynState__%s", cmd.synchLabel));

			for (int i = 0; i < cmd.getModulesNum(); ++i) {
				cmdNumber = cmd.getCommandNumber(i);
				if (cmdNumber > max) {
					max = cmdNumber;
				}
			}
			sum = cmd.getMaxCommandsNum();
			maximalNumberOfSynchsUpdates += sum;
			size = new CLVariable(new StdVariableType(0, sum), "size");
			array = new CLVariable(new ArrayType(new StdVariableType(0, max), cmd.getModulesNum()), "moduleSize");
			type.addVariable(size);
			type.addVariable(array);
			synchronizedStates.put(cmd.synchLabel, type);
		}
	}

	@Override
	protected Method guardsSynCreateMethod(String label, int maxCommandsNumber)
	{
		Method currentMethod = new Method(label, new StdVariableType(0, maxCommandsNumber));
		return currentMethod;
	}

	@Override
	protected CLVariable guardsSynLabelVar(int maxCommandsNumber)
	{
		return new CLVariable(new StdVariableType(0, maxCommandsNumber), "labelSize");
	}

	@Override
	protected CLVariable guardsSynCurrentVar(int maxCommandsNumber)
	{
		return new CLVariable(new StdVariableType(0, maxCommandsNumber), "currentSize");
	}

	@Override
	protected void guardsSynAddGuard(ComplexKernelComponent parent, Command cmd, CLVariable size)
	{
		Expression guard = new Expression(convertPrismGuard(svVars, cmd.getGuard().toString()));
		parent.addExpression(createBasicExpression(size.getSource(), Operator.ADD_AUGM,
		//converted guard
				guard));
	}

	@Override
	protected void createUpdateMethodSyn()
	{
		synchronizedUpdates = new ArrayList<>();
		additionalMethods = new ArrayList<>();
		for (SynchronizedCommand cmd : synCommands) {

			/**
			 * create updateSynchronized__Label method
			 * takes three args:
			 * - state vector
			 * - module number
			 * - selected guard
			 * - pointer to probability(updates it)
			 */
			Method update = updateSynLabelMethod(cmd);
			additionalMethods.add(update);

			Method current = new Method(String.format("updateSyn__%s", cmd.synchLabel), new StdVariableType(StdType.VOID));
			//state vectior
			CLVariable stateVector = new CLVariable(varStateVector.getPointer(), "sv");
			//synchronized state
			CLVariable synState = new CLVariable(new PointerType(synchronizedStates.get(cmd.synchLabel)), "synState");
			CLVariable propability = new CLVariable(new StdVariableType(StdType.FLOAT), "prop");
			//current guard
			CLVariable guard = new CLVariable(new StdVariableType(0, cmd.getMaxCommandsNum()), "guard");
			guard.setInitValue(StdVariableType.initialize(0));
			//sv->size
			CLVariable saveSize = synState.accessField("size");
			//size for current module
			CLVariable totalSize = new CLVariable(new StdVariableType(0, cmd.getMaxCommandsNum()), "totalSize");
			totalSize.setInitValue(saveSize);
			try {
				current.addArg(stateVector);
				current.addArg(synState);
				current.addArg(propability);
				current.addLocalVar(guard);
				current.addLocalVar(totalSize);
				current.registerStateVector(stateVector);
			} catch (KernelException e) {
				throw new RuntimeException(e);
			}
			current.registerStateVector(stateVector);
			current.addExpression(new Expression("if(get_global_id(0)<5)printf(\"" + cmd.synchLabel + " %f\\n\",prop);"));
			Expression guardUpdate = null, probUpdate = null;
			CLVariable moduleSize = null;
			//for-each module
			for (int i = 0; i < cmd.getModulesNum(); ++i) {
				//moduleSize = fromString(cmd.getCommandNumber(i));
				moduleSize = synState.accessField("moduleSize").accessElement(fromString(i));
				/**
				 * compute current guard in update
				 */
				guardUpdate = functionCall("floor",
				//probability * module_size
						createBasicExpression(propability.getSource(), Operator.MUL, moduleSize.getSource()));
				current.addExpression(createAssignment(guard, guardUpdate));
				/**
				 * recompute probability to an [0,1) in selected guard
				 */
				probUpdate = createBasicExpression(
				//probability * module_size
						createBasicExpression(propability.getSource(), Operator.MUL, moduleSize.getSource()), Operator.SUB,
						//guard
						guard.getSource());
				current.addExpression(createAssignment(propability, probUpdate));
				//current.addExpression(new Expression("if(get_global_id(0)<5)printf(\"" + cmd.synchLabel + " %f %d %d\\n\",prop,guard,(*sv).__STATE_VECTOR_q);"));
				/**
				 * call selected update
				 */
				current.addExpression(update.callMethod(stateVector, StdVariableType.initialize(i), guard, propability.convertToPointer()));

				//current.addExpression(new Expression("if(get_global_id(0)<5)printf(\"" + cmd.synchLabel + " %f %d %d\\n\",prop,guard,(*sv).__STATE_VECTOR_q);"));
				/**
				 * totalSize /= 1 is useless
				 */
				current.addExpression(createBasicExpression(totalSize.getSource(), Operator.DIV_AUGM, moduleSize.getSource()));
			}
			synchronizedUpdates.add(current);
		}
	}

	@Override
	protected Method updateSynLabelMethod(SynchronizedCommand synCmd)
	{
		Method current = new Method(String.format("updateSynchronized__%s", synCmd.synchLabel),
		//don't return anything
				new StdVariableType(StdType.VOID));
		CLVariable stateVector = new CLVariable(varStateVector.getPointer(), "sv");
		//selected module
		CLVariable module = new CLVariable(new StdVariableType(StdType.UINT8), "module");
		CLVariable guard = new CLVariable(new StdVariableType(StdType.UINT8), "guard");
		CLVariable probabilityPtr = new CLVariable(new PointerType(new StdVariableType(StdType.FLOAT)), "prob");
		CLVariable probability = probabilityPtr.dereference();
		try {
			current.addArg(stateVector);
			current.addArg(module);
			current.addArg(guard);
			current.addArg(probabilityPtr);
			current.registerStateVector(stateVector);
		} catch (KernelException e) {
			throw new RuntimeException(e);
		}
		Switch _switch = new Switch(module);
		Update update = null;
		Rate rate = null;
		Command cmd = null;
		//for-each module
		for (int i = 0; i < synCmd.getModulesNum(); ++i) {
			Switch internalSwitch = new Switch(guard);

			//for-each command
			for (int j = 0; j < synCmd.getCommandNumber(i); ++j) {
				cmd = synCmd.getCommand(i, j);
				update = cmd.getUpdate();
				rate = new Rate(update.getRate(0));

				internalSwitch.addCase(fromString(j));
				//when update is in form prob:action + prob:action + ...
				if (update.getActionsNumber() > 1) {
					IfElse ifElse = new IfElse(createBasicExpression(probability.getSource(), Operator.LT, fromString(convertPrismRate(svVars, rate))));
					if (!update.isActionTrue(0)) {
						ifElse.addExpression(0, updateSynProbabilityRecompute(probability, null, rate));
						ifElse.addExpression(0, convertPrismAction(update.getAction(0)));
					}
					for (int k = 1; k < update.getActionsNumber(); ++k) {
						Rate previous = new Rate(rate);
						rate.addRate(update.getRate(k));
						ifElse.addElif(createBasicExpression(probability.getSource(), Operator.LT, fromString(convertPrismRate(svVars, rate))));
						ifElse.addExpression(k, updateSynProbabilityRecompute(probability, previous, update.getRate(k)));
						if (!update.isActionTrue(k)) {
							ifElse.addExpression(k, convertPrismAction(update.getAction(k)));
						}
					}
					internalSwitch.addCommand(j, ifElse);
				} else {
					if (!update.isActionTrue(0)) {
						internalSwitch.addCommand(j, convertPrismAction(update.getAction(0)));
						//no recomputation necessary!
					}
				}
			}

			_switch.addCase(fromString(i));
			_switch.addCommand(i, internalSwitch);
		}
		current.addExpression(_switch);
		return current;
	}

	private Expression updateSynProbabilityRecompute(CLVariable probability, Rate before, Rate current)
	{
		Expression compute = null;
		if (before != null) {
			compute = createBasicExpression(probability.getSource(), Operator.SUB,
			//probability - sum of rates before
					fromString(convertPrismRate(svVars, before)));
		} else {
			compute = probability.getSource();
		}
		addParentheses(compute);
		return createAssignment(probability, createBasicExpression(compute, Operator.DIV,
		//divide by current interval
				fromString(convertPrismRate(svVars, current))));
	}
}