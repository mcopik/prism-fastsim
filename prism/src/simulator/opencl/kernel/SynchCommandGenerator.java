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

import static simulator.opencl.kernel.expression.ExpressionGenerator.addParentheses;
import static simulator.opencl.kernel.expression.ExpressionGenerator.convertPrismRate;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createAssignment;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createBinaryExpression;
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import simulator.opencl.automaton.AbstractAutomaton.AutomatonType;
import simulator.opencl.automaton.command.Command;
import simulator.opencl.automaton.command.SynchronizedCommand;
import simulator.opencl.automaton.update.Rate;
import simulator.opencl.automaton.update.Update;
import simulator.opencl.kernel.KernelGenerator.LocalVar;
import simulator.opencl.kernel.expression.ComplexKernelComponent;
import simulator.opencl.kernel.expression.Expression;
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
import simulator.opencl.kernel.memory.StructureType;
import simulator.opencl.kernel.memory.VariableTypeInterface;
import simulator.opencl.kernel.memory.StdVariableType.StdType;

public abstract class SynchCommandGenerator extends AbstractGenerator implements KernelComponentGenerator
{
	
	/**
	 * Synchronized commands from PRISM model.
	 */
	protected SynchronizedCommand[] synCommands = null;
	
	/**
	 * Local variable in kernel - array of structures for
	 * synchronized states.
	 */
	protected CLVariable[] kernelSynchronizedStates = null;
	
	/**
	 * Structures saving state of each synchronized label.
	 * Includes:
	 * bool guards[] - save evaluation of guards for each method
	 * float/int size - sum of rates/count of all generated commands
	 * float/int moduleSizes[] - sum of rates/count for each module
	 */
	protected Map<String, StructureType> synchronizedStates = null;

	/**
	 * List of synchronized guard check methods, one for each label.
	 * 
	 * DTMC:
	 * Return value is number of concurrent transitions.
	 * int checkGuardsSyn(StateVector * sv, SynCmdState ** tab);
	 * CTMC:
	 * Return value is rates sum of transitions in race condition.
	 * float checkGuardsSyn(StateVector * sv, SynCmdState ** tab);
	 */
	protected List<Method> synchronizedGuards = null;

	/**
	 * List of synchronized update methods, one for each label.
	 */
	protected List<Method> synchronizedUpdates = null;
	
	/**
	 * List of additional methods for synchronized update.
	 */
	protected List<Method> updateHelpers = null;
	
	/**
	 * Additional declarations in update method.
	 * Includes type for saved variables.
	 */
	protected List<KernelComponent> updateDefinitions = new ArrayList<>();
	
	public SynchCommandGenerator(KernelGenerator generator)
	{
		super(generator, generator.getSynchCommands().length > 0);
		
		if(!isGeneratorActive()) {
			return;
		}
		
		synCommands = generator.getSynchCommands();
		
		//each synchronized command requires separate C structure
		createSynchronizedStructures();
	}
	
	public static SynchCommandGenerator createGenerator(KernelGenerator generator) throws KernelException
	{
		if(generator.getModel().getType() == AutomatonType.CTMC) {
			return new SynchCommandGeneratorCTMC(generator);
		} else {
			return new SynchCommandGeneratorDTMC(generator);
		}
	}

	@Override
	public Collection<? extends KernelComponent> getDefinitions()
	{
		if(activeGenerator) {
			List<KernelComponent> list = new ArrayList<>();
			for (StructureType type : synchronizedStates.values()) {
				list.add(type.getDefinition());
			}
			return list;
		}
		return Collections.emptyList();
	}
	
	@Override
	public Collection<Method> getMethods()
	{
		if (activeGenerator) {
			List<Method> methods = new ArrayList<>();
			methods.addAll(synchronizedGuards);
			methods.addAll(synchronizedUpdates);
			return methods;
		}
		return Collections.emptyList();
	}
	
	/**
	 * @return boolean array marking active guards
	 */
	public Collection<CLVariable> getLocalVars()
	{
		if(activeGenerator) {
			int counter = 0;
			for (Map.Entry<String, StructureType> types : synchronizedStates.entrySet()) {
				kernelSynchronizedStates[counter++ ] = new CLVariable(types.getValue(),
				//synchState_label
						String.format("synchState_%s", types.getKey()));
			}
			return Arrays.asList(kernelSynchronizedStates);
		}
		return Collections.emptyList();
	}

	/**
	 * Create structures for synchronized commands.
	 * Generated structure types are different for DTMC and CTMC.
	 */
	protected abstract void createSynchronizedStructures();

	/**
	 * For DTMC this is an integer holding values between [0, maximal count of transitions)
	 * For CTMC this is a floating-point number.
	 * @return
	 */
	public abstract VariableTypeInterface kernelUpdateSizeType();

	/**
	 * synchronizedSize = 0;
	 * For each synchronization label:
	 * a) no transition counting:
	 * guard_label(&sv, synchState_label);
	 * b) transition counting)
	 * counter += guard_label(&sv, synchState_label);
	 * 
	 * synchronizedSize += synchState_label[0];
	 * @return
	 */
	public KernelComponent kernelCallGuardCheck()
	{
		if(activeGenerator) {
			ExpressionList code = new ExpressionList();
			
			code.addExpression(createAssignment(
					generator.kernelGetLocalVar(LocalVar.SYNCHRONIZED_SIZE), fromString(0))
					);
			
			for (int i = 0; i < synCommands.length; ++i) {
				// call guard.
				Expression callMethod = synchronizedGuards.get(i).callMethod(
				//&stateVector
						generator.kernelGetLocalVar(LocalVar.STATE_VECTOR).convertToPointer(),
						//synchState
						kernelSynchronizedStates[i].convertToPointer());
				CLVariable transactionCounter = generator.kernelGetLocalVar(LocalVar.TRANSITIONS_COUNTER);
				// transactionCounter += synGuards();
				if(transactionCounter != null) {
					code.addExpression( createBinaryExpression(
							transactionCounter.getSource(),
							Operator.ADD_AUGM,
							callMethod
							) );
				} 
				// otherwise just synGuards()
				else {
					code.addExpression(callMethod);
				}
				// sum sizes of all transitions
				code.addExpression(createBinaryExpression(
						generator.kernelGetLocalVar(LocalVar.SYNCHRONIZED_SIZE).getSource(),
						Operator.ADD_AUGM,
						kernelSynchronizedStates[i].accessField("size").getSource()
						));
			}
		}
		return new ExpressionList();
	}

	/**
	 * Performs selection between synchronized commands and calls selected synchronized update.
	 * @param parent
	 * @param selection
	 * @param synSum
	 * @param sum
	 */
	public Collection<KernelComponent> kernelCallUpdate(CLVariable selection, CLVariable synSum, Expression sum)
	{
		if(activeGenerator) {
			CLVariable varStateVector = generator.kernelGetLocalVar(LocalVar.STATE_VECTOR);
			List<KernelComponent> code = new ArrayList<>();

			if (synCommands.length > 1) {
				/**
				 * Loop counter, over all labels
				 */
				CLVariable counter = new CLVariable(new StdVariableType(0, synCommands.length), "synSelection");
				counter.setInitValue(StdVariableType.initialize(0));
				code.add(counter.getDefinition());
				//loop over synchronized commands
				ForLoop loop = new ForLoop(counter, 0, synCommands.length);
				Switch _switch = new Switch(counter);
	
				for (int i = 0; i < synCommands.length; ++i) {
					CLVariable currentSize = kernelSynchronizedStates[i].accessField("size");
					_switch.addCase(fromString(i));
					_switch.addExpression(i, createBinaryExpression(synSum.getSource(), Operator.ADD_AUGM,
					// synSum += synchState__label.size;
							currentSize.getSource()));
				}
				loop.addExpression(_switch);
	
				/**
				 * Check whether we have found proper label.
				 */
				IfElse checkSelection = new IfElse(mainMethodSynUpdateCondition(selection, synSum, sum));
	
				/**
				 * If yes, then counter shows us the label.
				 * For each one, recompute probability/rate
				 */
				_switch = new Switch(counter);
				for (int i = 0; i < synCommands.length; ++i) {
					_switch.addCase(fromString(i));
					_switch.setConditionNumber(i);
					/**
					 * Remove current size, added in loop.
					 */
					CLVariable currentSize = kernelSynchronizedStates[i].accessField("size");
					_switch.addExpression(i, createBinaryExpression(synSum.getSource(), Operator.SUB_AUGM,
					// synSum -= synchState__label.size;
							currentSize.getSource()));
					/**
					 * Recompute probability/rate
					 */
					_switch.addExpression(
							kernelCallUpdateRecomputeSelection(selection, synSum, sum, currentSize)
							);
				}
				checkSelection.addExpression(_switch);
				checkSelection.addExpression("break;\n");
				loop.addExpression(checkSelection);
				code.add(loop);
	
				/**
				 * Counter shows selected label, so we can call the update.
				 */
				_switch = new Switch(counter);
				for (int i = 0; i < synCommands.length; ++i) {
					_switch.addCase(fromString(i));
	
					/**
					 * Before a synchronized update, compute transition rewards.
					 */
					_switch.addExpression(i, generator.getRewardGenerator().kernelBeforeUpdate(varStateVector, synCommands[i]));
	
					Expression call = synchronizedUpdates.get(i).callMethod(
					//&stateVector
							varStateVector.convertToPointer(),
							//&synchState__label
							kernelSynchronizedStates[i].convertToPointer(),
							//probability
							selection);
					_switch.addExpression(i, generator.getLoopDetector().kernelCallUpdate(call));
				}
				code.add(_switch);
			} else {
				CLVariable currentSize = kernelSynchronizedStates[0].accessField("size");
				/**
				 * Recompute probability/rate
				 */
				code.add(
						kernelCallUpdateRecomputeSelection(selection, synSum, sum, currentSize)
						);
				Expression call = synchronizedUpdates.get(0).callMethod(
				//&stateVector
						varStateVector.convertToPointer(),
						//&synchState__label
						kernelSynchronizedStates[0].convertToPointer(),
						//probability
						selection);
				/**
				 * Before a synchronized update, compute transition rewards.
				 */
				code.add( generator.getRewardGenerator().kernelBeforeUpdate(varStateVector, synCommands[0]) );
				code.add( generator.getLoopDetector().kernelCallUpdate(call) );
			}
			return code;
		}
		return Collections.emptyList();
	}

	/**
	 * Create condition which evaluates to true for selected non-sychronized update.
	 * For DTMC, involves floating-point division
	 * @param selection
	 * @param synSum
	 * @param sum
	 * @return boolean expression
	 */
	protected abstract Expression mainMethodSynUpdateCondition(CLVariable selection, CLVariable synSum, Expression sum);
	
	/**
	 * Modify current selection to fit in the interval beginning from 0 - values are not in [0, synSum) and
	 * non-synchronized update has been selection.
	 * @param parent
	 * @param selection
	 * @param synSum
	 * @param sum
	 * @param currentLabelSize size of current synchronized update; used only for DTMC
	 */
	protected abstract KernelComponent kernelCallUpdateRecomputeSelection(CLVariable selection, CLVariable synSum, Expression sum,
			CLVariable currentLabelSize);
	
	/*********************************
	 * SYNCHRONIZED GUARDS CHECK
	 ********************************/

	/**
	 * Create helper method - evaluation of synchronized guards.
	 * Algorithm:
	 * 1) For every synchronization label, create separate method
	 * 2) For every module, evaluate guards and remember current rate/active guards count
	 * 2a) If for some module all guards are marked zero, then skip all other commands
	 * 2b) If not, then multiply sizes of all modules and set it as 'label size'
	 */
	protected void createGuardsMethod()
	{
		synchronizedGuards = new ArrayList<>();
		for (SynchronizedCommand cmd : synCommands) {
			//synchronized state
			CLVariable synState = new CLVariable(new PointerType(synchronizedStates.get(cmd.synchLabel)), "synState");
			int max = cmd.getMaxCommandsNum();
			Method current = guardsCreateMethod(String.format("guardCheckSyn__%s", cmd.synchLabel), max);
			CLVariable stateVector = new CLVariable(generator.kernelGetLocalVar(LocalVar.STATE_VECTOR).getPointer(), "sv");
			//size for whole label
			CLVariable labelSize = guardsLabelVar(max);
			labelSize.setInitValue(StdVariableType.initialize(0));
			//size for current module
			CLVariable currentSize = guardsCurrentVar(max);
			currentSize.setInitValue(StdVariableType.initialize(0));
			CLVariable saveSize = synState.accessField("moduleSize");
			CLVariable guardsTab = synState.accessField("guards");
			
			StateVector.Translations translations = this.stateVector.createTranslations(stateVector);

			try {
				current.addArg(stateVector);
				current.addArg(synState);
				current.addLocalVar(labelSize);
				current.addLocalVar(currentSize);
				current.addLocalVar( guardsLocalVars(
						cmd.getModulesNum(), cmd.getCmdsNum(), cmd.getMaxCommandsNum()
								));
			} catch (KernelException e) {
				throw new RuntimeException(e);
			}

			int guardCounter = 0;
			//first module
			for (int i = 0; i < cmd.getCommandNumber(0); ++i) {
				guardsAddGuard(current, translations, guardsTab.accessElement(fromString(guardCounter++)),
				//guardsTab[counter] = evaluate(guard)
						cmd.getCommand(0, i), currentSize);
			}
			current.addExpression(createAssignment(saveSize.accessElement(fromString(0)), currentSize));
			current.addExpression(createAssignment(labelSize, currentSize));
			current.addExpression(guardsAfterModule(0));
			//rest
			for (int i = 1; i < cmd.getModulesNum(); ++i) {
				IfElse ifElse = new IfElse(createBinaryExpression(labelSize.getSource(), Operator.NE, fromString(0)));
				ifElse.addExpression(createAssignment(currentSize, fromString(0)));
				ifElse.addExpression(guardsBeforeModule(i));
				for (int j = 0; j < cmd.getCommandNumber(i); ++j) {
					guardsAddGuard(ifElse, translations, guardsTab.accessElement(fromString(guardCounter++)),
					//guardsTab[counter] = evaluate(guard)
							cmd.getCommand(i, j), currentSize);
				}
				ifElse.addExpression(createBinaryExpression(labelSize.getSource(),
				// cmds_for_label *= cmds_for_module;
						Operator.MUL_AUGM, currentSize.getSource()));
				ifElse.addExpression(createAssignment(saveSize.accessElement(fromString(i)), currentSize));
				ifElse.addExpression(guardsAfterModule(i));
				current.addExpression(ifElse);
			}
			saveSize = synState.accessField("size");
			current.addExpression(createAssignment(saveSize, labelSize));
			guardsReturn(current);
			synchronizedGuards.add(current);
		}
	}

	/**
	 * @param label
	 * @param maxCommandsNumber
	 * @return method returning float(CTMC) or an integer(DTMC) - label size
	 */
	protected abstract Method guardsCreateMethod(String label, int maxCommandsNumber);

	/**
	 * Additional local variables.
	 * @param cmdsCount
	 * @param maxCmdsCount
	 * @return none for DTMC, CTMC may add transition counters
	 */
	protected abstract Collection<CLVariable> guardsLocalVars(int moduleCount, int cmdsCount, int maxCmdsCount);
	
	/**
	 * @param maxCommandsNumber
	 * @return helper variable for label size - float/integer
	 */
	protected abstract CLVariable guardsLabelVar(int maxCommandsNumber);

	/**
	 * @param maxCommandsNumber
	 * @return helper variable for size of current module - float/integer
	 */
	protected abstract CLVariable guardsCurrentVar(int maxCommandsNumber);

	/**
	 * Only for CTMC with loop detection
	 * @param module
	 * @return code injected before starting checking a module
	 */
	protected abstract KernelComponent guardsBeforeModule(int module);
	
	/**
	 * Only for CTMC with loop detection
	 * @param module
	 * @return code injected after finishing checking a module
	 */
	protected abstract KernelComponent guardsAfterModule(int module);

	/**
	 * Only for CTMC with loop detection
	 * @param method
	 * @return code injected after finishing checking a module
	 */
	protected abstract void guardsReturn(Method method);
	
	/**
	 * Mark command index in guardsArray and sum rates/counts (simpler implementation for DTMC - 
	 * just add guard tab value, whether it is 0 or 1; in CTMC, rate is added only in one case).
	 * @param parent
	 * @param svTranslations
	 * @param guardArray
	 * @param cmd
	 * @param size
	 */
	protected abstract void guardsAddGuard(ComplexKernelComponent parent, StateVector.Translations svTranslations, CLVariable guardArray, Command cmd, CLVariable size);

	/*********************************
	 * SYNCHRONIZED UPDATE
	 ********************************/
	
	/**
	 * Create helper method - update of the state vector with a synchronized command. 
	 * Main method recomputes probabilities, selects guards and calls additional update function
	 * for each module.
	 * 
	 */
	protected void createUpdateFunction()
	{
		synchronizedUpdates = new ArrayList<>();
		updateDefinitions = new ArrayList<>();

		for (SynchronizedCommand cmd : synCommands) {

			Method current = new Method(String.format("updateSyn__%s", cmd.synchLabel),
					generator.getLoopDetector().synUpdateFunctionReturnType()
					);
			//state vector
			CLVariable stateVector = new CLVariable(generator.kernelGetLocalVar(LocalVar.STATE_VECTOR).getPointer(), "sv");
			//synchronized state
			CLVariable synState = new CLVariable(new PointerType(synchronizedStates.get(cmd.synchLabel)), "synState");
			CLVariable propability = new CLVariable(new StdVariableType(StdType.FLOAT), "prop");
			//current guard
			CLVariable guard = new CLVariable(new StdVariableType(0, cmd.getMaxCommandsNum()), "guard");
			guard.setInitValue(StdVariableType.initialize(0));
			//synState->size
			CLVariable saveSize = synState.accessField("size");
			//synState->guards
			CLVariable guardsTab = synState.accessField("guards");
			//size for current module
			CLVariable totalSize = new CLVariable(saveSize.varType, "totalSize");
			totalSize.setInitValue(saveSize);
			
			/**
			 * Obtain variables required to save, create a structure (when necessary),
			 * initialize it with StateVector values.
			 */
			SavedVariables savedVarsType = new SavedVariables(
					cmd.variablesCopiedBeforeUpdate(),
					cmd.synchLabel
					);
			//add to global declarations
			updateDefinitions.add(savedVarsType.getDefinition());

			StateVector.Translations translations = this.stateVector.createTranslations(stateVector);
			CLVariable savedVarsInstance = savedVarsType.createInstance(stateVector, "oldSV");
			try {
				current.addArg(stateVector);
				current.addArg(synState);
				current.addArg(propability);
				current.addLocalVar(guard);
				current.addLocalVar(totalSize);
				current.addLocalVar( generator.getLoopDetector().synUpdateFunctionLocalVars() );

				if (savedVarsInstance != null)
					current.addLocalVar( savedVarsInstance );

				updateAdditionalVars(current, cmd);
			} catch (KernelException e) {
				throw new RuntimeException(e);
			}

			CLVariable moduleSize = null;
			//			ForLoop loop = new ForLoop("loopCounter", 0, cmd.getModulesNum());
			//			CLVariable loopCounter = loop.getLoopCounter();
			//for-each module
			//TODO: check optimizing without loop unrolling?

			/**
			 * create updateSynchronized__Label method
			 * takes three args:
			 * - state vector
			 * - module number
			 * - selected guard
			 * - pointer to probability(updates it)
			 * - optional: saved values of state vector
			 */
			Method update = updateLabelFunction(cmd, savedVarsType);
			updateHelpers.add(update);

			for (int i = 0; i < cmd.getModulesNum(); ++i) {

				moduleSize = synState.accessField("moduleSize").accessElement(fromString(i));
				updateBeforeUpdateLabel(current, translations, cmd, i, guardsTab, guard, moduleSize, totalSize, propability);

				/**
				 * call selected update
				 */
				Expression callUpdate = null;
				if (savedVarsInstance != null) {
					callUpdate = update.callMethod(stateVector, guardsTab, StdVariableType.initialize(i), guard, propability.convertToPointer(),
							savedVarsInstance.convertToPointer());
				} else {
					callUpdate = update.callMethod(stateVector, guardsTab, StdVariableType.initialize(i), guard, propability.convertToPointer());
				}
				current.addExpression( generator.getLoopDetector().synUpdateCallUpdate(callUpdate) );

				updateAfterUpdateLabel(current, guard, moduleSize, totalSize, propability);
			}

			generator.getLoopDetector().synUpdateFunctionReturn(current);
			synchronizedUpdates.add(current);
		}
	}

	/**
	 * Only CTMC uses additional variables for sum (when there are two or more guards in one of modules).
	 * @param parent
	 * @param cmd
	 * @throws KernelException
	 */
	protected abstract void updateAdditionalVars(Method parent, SynchronizedCommand cmd) throws KernelException;

	/**
	 * Computations and scaling before calling direct update method for i-th module.
	 * For CTMC, includes dividing total size by module size and computation of probability.
	 * Moreover, if there are more commands for this module, then one need to loop through them, sum
	 * rates and select guard.
	 * 
	 * DTMC: one need just to directly compute guard and scale probability.
	 * @param parent
	 * @param cmd
	 * @param moduleNumber
	 * @param guardsTab
	 * @param guard
	 * @param moduleSize
	 * @param totalSize
	 * @param probability
	 */
	protected abstract void updateBeforeUpdateLabel(Method parent, StateVector.Translations translations,
			SynchronizedCommand cmd, int moduleNumber, CLVariable guardsTab, CLVariable guard,
			CLVariable moduleSize, CLVariable totalSize, CLVariable probability);

	/**
	 * Computations after calling direct update method for i-th module.
	 * DTMC: divide total size by module size (number of commands in next modules).
	 * CTMC: multiply probability, to scale it back to total size
	 * @param parent
	 * @param guard
	 * @param moduleSize
	 * @param totalSize
	 * @param probability
	 */
	protected abstract void updateAfterUpdateLabel(ComplexKernelComponent parent, CLVariable guard, CLVariable moduleSize, CLVariable totalSize,
			CLVariable probability);

	/**
	 * Method takes as an argument SV, module number, guard selection and probability, performs direct update of stateVector
	 * @param synCmd
	 * @param savedVariables
	 * @return 'direct' update method
	 */
	protected Method updateLabelFunction(SynchronizedCommand synCmd, SavedVariables savedVariables)
	{
		Method current = new Method(String.format("updateSynchronized__%s", synCmd.synchLabel),
				generator.getLoopDetector().synLabelUpdateFunctionReturnType());
		CLVariable stateVector = new CLVariable(generator.kernelGetLocalVar(LocalVar.STATE_VECTOR).getPointer(), "sv");
		//guardsTab
		CLVariable guardsTab = new CLVariable(new PointerType(new StdVariableType(StdType.BOOL)), "guards");
		//selected module
		CLVariable module = new CLVariable(new StdVariableType(StdType.UINT8), "module");
		CLVariable guard = new CLVariable(new StdVariableType(StdType.UINT8), "guard");
		CLVariable probabilityPtr = new CLVariable(new PointerType(new StdVariableType(StdType.FLOAT)), "prob");
		// saved values - optional argument
		CLVariable oldSV = savedVariables.createPointer("oldSV");

		CLVariable probability = probabilityPtr.dereference();
		try {
			current.addArg(stateVector);
			current.addArg(guardsTab);
			current.addArg(module);
			current.addArg(guard);
			current.addArg(probabilityPtr);
			if (oldSV != null) {
				current.addArg(oldSV);
			}
			current.addLocalVar( generator.getLoopDetector().synLabelUpdateFunctionLocalVars() );
		} catch (KernelException e) {
			throw new RuntimeException(e);
		}

		Switch _switch = new Switch(module);
		Update update = null;
		Rate rate = null;
		Command cmd = null;
		int moduleOffset = 0;
		CLVariable guardSelection = updateLabelMethodGuardSelection(synCmd, guard);
		CLVariable guardCounter = updateLabelMethodGuardCounter(synCmd);
		//no variable for DTMC
		if (guardCounter != null) {
			current.addExpression(guardCounter.getDefinition());
		}
		current.addExpression(guardSelection.getDefinition());

		// Create translations variable -> savedStructure.variable
		// Provide alternative access to state vector variable (instead of regular structure)
		// Map<String, CLVariable> savedTranslations = null;
		SavedVariables.Translations savedTranslations = savedVariables.createTranslations(oldSV);
		SavedVariables.Translations localCopy = savedTranslations.copy();
		// variables saved in single update
		StateVector.Translations svPtrTranslations = this.stateVector.createTranslations(stateVector);
		
		//for-each module
		for (int i = 0; i < synCmd.getModulesNum(); ++i) {
			_switch.addCase(fromString(i));
			_switch.setConditionNumber(i);
			updateLabelMethodSelectGuard(current, _switch, guardSelection, guardCounter, moduleOffset);

			Switch internalSwitch = new Switch(guardSelection);
			//for-each command
			for (int j = 0; j < synCmd.getCommandNumber(i); ++j) {
				cmd = synCmd.getCommand(i, j);
				update = cmd.getUpdate();
				rate = new Rate(update.getRate(0));

				internalSwitch.addCase(fromString(j));
				//when update is in form prob:action + prob:action + ...
				int actionsCount = update.getActionsNumber();
				if (actionsCount > 1) {
					
					internalSwitch.addExpression(j, generator.getLoopDetector().synLabelUpdateFunctionMultipleActions());
					
					IfElse ifElse = new IfElse(createBinaryExpression(probability.getSource(), Operator.LT,
							fromString(convertPrismRate(svPtrTranslations, savedTranslations, rate))));
					if (!update.isActionTrue(0)) {
						ifElse.addExpression(0, updateLabelMethodProbabilityRecompute(probability, null, rate, svPtrTranslations,
								savedTranslations));

						//StateVector.addSavedVariables(stateVector, ifElse, 0, update.getAction(0), savedTranslations, varsSaved);
						//SavedVariables.Translations savedTranslations = savedVariables.createTranslations()
						// make temporary copy, we may overwrite some variables
						// TODO: is the statement above true?
						//Map<String, CLVariable> newSavedTranslations;
//						if (savedTranslations != null) {
//							newSavedTranslations = new HashMap<>(savedTranslations);
//						} else {
//							newSavedTranslations = new HashMap<>();
//						}
//						newSavedTranslations.putAll(varsSaved);
						localCopy.clear();
						Collection<CLVariable> localVars = SavedVariables.createTranslations(
								stateVector,
								update.getAction(0),
								savedTranslations,
								localCopy
								);
						for(CLVariable localVar : localVars) {
							ifElse.addExpression(0, localVar.getDefinition());
						}
						// make temporary copy, we may overwrite some variables
						// TODO: is the statement above true?
						SavedVariables.Translations newSavedTranslations = savedTranslations.copy();

						ifElse.addExpression(0, generator.getLoopDetector().synLabelUpdateFunctionConvertAction(
								stateVector, update.getAction(0), actionsCount, svPtrTranslations, newSavedTranslations
								));
					}
					for (int k = 1; k < update.getActionsNumber(); ++k) {
						Rate previous = new Rate(rate);
						rate.addRate(update.getRate(k));
						ifElse.addElif(createBinaryExpression(probability.getSource(), Operator.LT,
								fromString(convertPrismRate(svPtrTranslations, savedTranslations, rate))));
						ifElse.addExpression(k, updateLabelMethodProbabilityRecompute(probability, previous, update.getRate(k),
								svPtrTranslations, savedTranslations));

						localCopy.clear();
						Collection<CLVariable> localVars = SavedVariables.createTranslations(
								stateVector,
								update.getAction(k),
								savedTranslations,
								localCopy
								);
						for(CLVariable localVar : localVars) {
							ifElse.addExpression(k, localVar.getDefinition());
						}
						SavedVariables.Translations newSavedTranslations = savedTranslations.copy();

						if (!update.isActionTrue(k)) {
							ifElse.addExpression(k, generator.getLoopDetector().synLabelUpdateFunctionConvertAction(
									stateVector, update.getAction(k), actionsCount, svPtrTranslations, newSavedTranslations
									));
						}
					}
					internalSwitch.addExpression(j, ifElse);
				} else {
					if (!update.isActionTrue(0)) {

						localCopy.clear();
						Collection<CLVariable> localVars = SavedVariables.createTranslations(
								stateVector,
								update.getAction(0),
								savedTranslations,
								localCopy
								);
						for(CLVariable localVar : localVars) {
							internalSwitch.addExpression(j, localVar.getDefinition());
						}
						SavedVariables.Translations newSavedTranslations = savedTranslations.copy();
			
						internalSwitch.addExpression(j, generator.getLoopDetector().synLabelUpdateFunctionConvertAction(
								stateVector, update.getAction(0), actionsCount, svPtrTranslations, newSavedTranslations
								));
						//no recomputation necessary!
					}
				}
			}
			moduleOffset += synCmd.getCommandNumber(i);
			_switch.addExpression(i, internalSwitch);
		}
		current.addExpression(_switch);
		generator.getLoopDetector().synLabelUpdateFunctionReturn(current);
		return current;
	}

	/**
	 * @param cmd
	 * @param guard
	 * @return DTMC: increased (later) integer for guard selection, decreased for CTMC
	 */
	protected abstract CLVariable updateLabelMethodGuardSelection(SynchronizedCommand cmd, CLVariable guard);

	/**
	 * @param cmd
	 * @return an integer for DTMC, none for CTMC
	 */
	protected abstract CLVariable updateLabelMethodGuardCounter(SynchronizedCommand cmd);

	/**
	 * CTMC: nothing to do
	 * DTMC: go through the whole guardsTab to find n-th active guard
	 * @param currentMethod
	 * @param parent
	 * @param guardSelection
	 * @param guardCounter
	 * @param moduleOffset
	 */
	protected abstract void updateLabelMethodSelectGuard(Method currentMethod, ComplexKernelComponent parent, CLVariable guardSelection,
			CLVariable guardCounter, int moduleOffset);

	/**
	 * @param probability
	 * @param before
	 * @param current
	 * @return expression recomputing probability before going to an action
	 */
	protected Expression updateLabelMethodProbabilityRecompute(CLVariable probability, Rate before, Rate current,
			StateVector.Translations svTranslations,
			SavedVariables.Translations savedVariables)
	{
		Expression compute = null;
		if (before != null) {
			compute = createBinaryExpression(probability.getSource(), Operator.SUB,
			//probability - sum of rates before
					fromString(convertPrismRate(svTranslations, savedVariables, before)));
		} else {
			compute = probability.getSource();
		}
		addParentheses(compute);
		return createAssignment(probability, createBinaryExpression(compute, Operator.DIV,
		//divide by current interval
				fromString(convertPrismRate(svTranslations, savedVariables, current))));
	}
}
