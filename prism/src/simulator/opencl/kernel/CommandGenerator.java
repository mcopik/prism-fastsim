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
import static simulator.opencl.kernel.expression.ExpressionGenerator.createBinaryExpression;
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import simulator.opencl.automaton.AbstractAutomaton.AutomatonType;
import simulator.opencl.automaton.command.Command;
import simulator.opencl.automaton.update.Action;
import simulator.opencl.automaton.update.Rate;
import simulator.opencl.automaton.update.Update;
import simulator.opencl.kernel.expression.ComplexKernelComponent;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.IfElse;
import simulator.opencl.kernel.expression.KernelComponent;
import simulator.opencl.kernel.expression.Method;
import simulator.opencl.kernel.expression.Switch;
import simulator.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.opencl.kernel.memory.ArrayType;
import simulator.opencl.kernel.memory.CLValue;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.PointerType;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.opencl.kernel.memory.StructureType;
import simulator.opencl.kernel.memory.StdVariableType.StdType;

public abstract class CommandGenerator implements KernelComponentGenerator
{

	/**
	 * Main generator.
	 */
	protected KernelGenerator generator = null;
	
	/**
	 * Model commands.
	 */
	protected Command[] commands = null;
	
	/**
	 * True iff there are non-synchronized commands.
	 */
	protected boolean activeGenerator = false;
	
	/**
	 * Local kernel variable
	 */
	protected CLVariable kernelGuardsTab = null;

	/**
	 * DTMC:
	 * Return value is number of concurrent transitions.
	 * int checkGuards(StateVector * sv, bool * guardsTab);
	 * CTMC:
	 * a) transition count is not required
	 * 	float checkGuards(StateVector * sv, bool * guardsTab);
	 * 	return value is sum of rates of transitions in race condition.
	 * b) transition count requested
	 * 	int checkGuards(StateVector * sv, bool * guardsTab, float * sum);
	 *  return value is number of concurrent transitions, pointer 'sum'
	 *  stores sum of rates of all transitions
	 */
	protected Method checkGuards = null;
	protected static final String CHECK_GUARDS_NAME = "checkNonsynGuards";

	public enum CheckGuardsVar
	{
		/**
		 * Arg: char * guardsTab
		 */
		GUARDS_TAB,
		/**
		 * Var: int counter
		 */
		COUNTER
	}
	protected final static EnumMap<CheckGuardsVar, String> FUNCTION_VARIABLES_NAMES;
	static {
		EnumMap<CheckGuardsVar, String> names = new EnumMap<>(CheckGuardsVar.class);
		names.put(CheckGuardsVar.GUARDS_TAB, "guardsTab");
		names.put(CheckGuardsVar.COUNTER, "counter");
		FUNCTION_VARIABLES_NAMES = names;
	}
	protected EnumMap<CheckGuardsVar, CLVariable> checkGuardsVars = new EnumMap<>(CheckGuardsVar.class);
	
	/**
	 * DTMC:
	 * void performUpdate(StateVector * sv, bool * guardsTab, float sumSelection, int allTransitions);
	 * CTMC:
	 * void performUpdate(StateVector * sv,  bool * guardsTab, float sumSelection);
	 */
	protected Method updateFunction = null;
	protected static final String UPDATE_GUARDS_NAME = "updateNonsynGuards";
	
	public CommandGenerator(KernelGenerator generator) throws KernelException
	{
		this.generator = generator;
		
		commands = generator.getCommands();
		activeGenerator = commands != null;

		kernelGuardsTab = new CLVariable(
				new ArrayType(new StdVariableType(0, commands.length), commands.length),
				"guardsTab"
				);
	}
	
	public static CommandGenerator createGenerator(KernelGenerator generator) throws KernelException
	{
		if(generator.getModel().getType() == AutomatonType.CTMC) {
			return new CommandGeneratorCTMC(generator);
		} else {
			return new CommandGeneratorDTMC(generator);
		}
	}


	@Override
	public Collection<? extends KernelComponent> getDefinitions()
	{
		return Collections.emptyList();
	}
	
	@Override
	public Collection<Method> getMethods()
	{
		if (activeGenerator) {
			List<Method> methods = new ArrayList<>();
			methods.add(checkGuards);
			methods.add(updateFunction);
			return methods;
		}
		return Collections.emptyList();
	}
	
	public boolean isActive()
	{
		return activeGenerator;
	}
	
	/**
	 * @return boolean array marking active guards
	 */
	public Collection<CLVariable> getLocalVars()
	{
		if(activeGenerator) {
			return Collections.singletonList(kernelGuardsTab);
		}
		return Collections.emptyList();
	}
	
	/**
	 * DTMC:
	 * non-synchronized_size += checkGuards(&stateVector, guardsTab);
	 * CTMC:
	 * a) transition count is not required
	 *  non-synchronized_size += checkGuards(&stateVector, guardsTab);
	 * b) transition count requested
	 *  transition_count += checkGuards(&stateVector, guardsTab, &non-synchronized_size);
	 * @param transactionCounter
	 * @return
	 */
	public abstract KernelComponent kernelCallGuardCheck() throws KernelException;
	
	/**
	 * DTMC:
	 * update(&stateVector, guardsTab, random);
	 * CTMC:
	 * update(&stateVector, guardsTab, random, selectionSize);
	 * @param transactionCounter
	 * @return
	 */
	public abstract Expression kernelCallUpdate(CLValue rnd, CLValue sum) throws KernelException;
	
	/*********************************
	 * GUARDS CHECK
	 ********************************/
	/**
	 * Create method for guards verification in non-synchronized updates.
	 * Method will just go through all guards and write numbers of successfully evaluated guards
	 * at consecutive positions at guardsTab.
	 * 
	 * DTMC:
	 * int f(StateVector * sv, char * guardsTab)
	 * {
	 * 	int counter = 0;
	 *  Now for each guard:
	 *  if(guard(sv)) {
	 *  	guardsTab[counter++] = cmd_id;
	 *  }
	 *  
	 *  return counter;
	 * }
	 * 
	 * CTMC, without transition counting
	 * float f(StateVector * sv, char * guardsTab)
	 * {
	 * 	int counter = 0;
	 *  float sum = 0.0f;
	 *  Now for each guard:
	 *  if(guard(sv)) {
	 *  	guardsTab[counter++] = cmd_id;
	 *  	sum += cmd_rates;
	 *  }
	 *  
	 *  return sum;
	 * }
	 * 
	 * CTMC, with transition counting
	 * float f(StateVector * sv, char * guardsTab, float * sum)
	 * {
	 * 	int counter = 0;
	 *  Now for each guard:
	 *  if(guard(sv)) {
	 *  	guardsTab[counter++] = cmd_id;
	 *  	(*sum) += cmd_rates;
	 *  }
	 *  
	 *  return counter;
	 * }
	 * 
	 * @return number of active guards (DTMC) / rate sum (CTMC)
	 * @throws KernelException
	 */
	protected Method createGuardsMethod() throws KernelException
	{
		Method currentMethod = guardsMethodCreateSignature();
		/**
		 * Args
		 */
		//StateVector * sv
		CLVariable sv = new CLVariable(new PointerType( generator.getSVType() ), "sv");
		currentMethod.addArg(sv);
		//bool * guardsTab
		CLVariable guards = new CLVariable(
				new PointerType(new StdVariableType(0, commands.length)),
				FUNCTION_VARIABLES_NAMES.get(CheckGuardsVar.GUARDS_TAB)
				);
		currentMethod.addArg(guards);
		checkGuardsVars.put(CheckGuardsVar.GUARDS_TAB, guards);
		currentMethod.addArg( guardsMethodAddArgs() );
		
		/**
		 * Local vars
		 */
		currentMethod.addLocalVar( guardsMethodCreateLocalVars() );
		//counter
		CLVariable counter = new CLVariable(
				new StdVariableType(0, commands.length),
				FUNCTION_VARIABLES_NAMES.get(CheckGuardsVar.COUNTER)
				);
		counter.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(counter);
		checkGuardsVars.put(CheckGuardsVar.COUNTER, counter);

		for (int i = 0; i < commands.length; ++i) {
			guardsMethodCreateCondition(
					currentMethod,
					i,
					convertPrismGuard(generator.getSVPtrTranslations(), commands[i].getGuard())
					);
		}

		guardsMethodReturnValue(currentMethod);
		return currentMethod;
	}

	/**
	 * @return only for CTMC pointer to sum when counter is returned
	 */
	protected abstract Collection<CLVariable> guardsMethodAddArgs();
	
	/**
	 * @return method returning integer for DTMC, float for CTMC
	 */
	protected abstract Method guardsMethodCreateSignature();

	/**
	 * Additional float for rate sum at CTMC, none at DTMC (both use an integer for array counting)
	 * @throws KernelException
	 */
	protected abstract Collection<CLVariable> guardsMethodCreateLocalVars();

	/**
	 * For both automata evaluate guard, for CTMC additionally add rate to returned sum.
	 * @param currentMethod
	 * @param position
	 * @param guard
	 */
	protected abstract void guardsMethodCreateCondition(Method currentMethod, int position, Expression guard);

	/**
	 * Returns counter of evaluated guards (integer) for DTMC or sum of rates (float) for CTMC.
	 * @param currentMethod
	 */
	protected abstract void guardsMethodReturnValue(Method currentMethod);


	/*********************************	
	 * NON-SYNCHRONIZED UPDATE
	 ********************************/

	/**
	 * @return method for non-synchronized update of state vector
	 * @throws KernelException
	 */
	protected Method createUpdate() throws KernelException
	{
		LoopDetector loopDetector = generator.getLoopDetector();
		Method currentMethod = new Method(UPDATE_GUARDS_NAME, loopDetector.updateFunctionReturnType());
		//StateVector * sv
		CLVariable sv = new CLVariable(new PointerType( generator.getSVType() ), "sv");
		currentMethod.addArg(sv);
		//bool * guardsTab
		CLVariable guards = new CLVariable(new PointerType(new StdVariableType(0, commands.length)), "guardsTab");
		currentMethod.addArg(guards);
		//float sum
		CLVariable selectionSum = new CLVariable(new StdVariableType(StdType.FLOAT), "selectionSum");
		selectionSum.setInitValue(StdVariableType.initialize(0.0f));
		currentMethod.addArg(selectionSum);
		// selected command
		CLVariable selection = new CLVariable(new StdVariableType(0, commands.length), "selection");
		selection.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(selection);
		// loop detection vars
		currentMethod.addLocalVar(loopDetector.updateFunctionLocalVars());

		/**
		 * Performs tasks depending on automata type
		 */
		updateMethodAdditionalArgs(currentMethod);
		updateMethodLocalVars(currentMethod);
		updateMethodPerformSelection(currentMethod);

		Map<String, String> svPtrTranslations = generator.getSVPtrTranslations();
		CLVariable guardsTabSelection = guards.accessElement(selection.getSource());
		Switch _switch = new Switch(guardsTabSelection.getSource());
		int switchCounter = 0;

		for (int i = 0; i < commands.length; ++i) {
			Update update = commands[i].getUpdate();
			Rate rate = new Rate(update.getRate(0));
			Action action;
			// variables saved in this action 
			Map<String, CLVariable> savedVariables = new HashMap<>();

			// if there is more than one action possible, then create a conditional to choose between them
			// for one action, it's unnecessary
			int actionsCount = update.getActionsNumber();
			if (actionsCount > 1) {

				_switch.addCase(new Expression(Integer.toString(i)));
				_switch.addExpression(switchCounter, loopDetector.updateFunctionMultipleActions());
				
				IfElse ifElse = new IfElse(createBinaryExpression(selectionSum.getSource(), Operator.LT,
						fromString(convertPrismRate(svPtrTranslations, null, rate))));
				//first one goes to 'if'
				if (!update.isActionTrue(0)) {
					action = update.getAction(0);
					StateVector.addSavedVariables(sv, ifElse, 0, action, null, savedVariables);
					ifElse.addExpression(0,
							loopDetector.updateFunctionConvertAction(sv, action, actionsCount, svPtrTranslations, savedVariables)
							);
				}
				// next actions go to 'else if'
				for (int j = 1; j < update.getActionsNumber(); ++j) {
					// else if (selection <= sum)
					rate.addRate(update.getRate(j));
					ifElse.addElif(createBinaryExpression(selectionSum.getSource(), Operator.LT, fromString(convertPrismRate(svPtrTranslations, null, rate))));

					if (!update.isActionTrue(j)) {
						action = update.getAction(j);
						StateVector.addSavedVariables(sv, ifElse, 0, action, null, savedVariables);
						ifElse.addExpression(j,
								loopDetector.updateFunctionConvertAction(sv, action, actionsCount, svPtrTranslations, savedVariables)
								);
					}
				}
				_switch.addExpression(switchCounter++, ifElse);
			} else {
				// only one action, directly add the code to switch
				if (!update.isActionTrue(0)) {
					_switch.addCase(new Expression(Integer.toString(i)));
					action = update.getAction(0);
					StateVector.addSavedVariables(sv, _switch, switchCounter, action, null, savedVariables);
					_switch.addExpression(switchCounter++,
							loopDetector.updateFunctionConvertAction(sv, action, actionsCount, svPtrTranslations, savedVariables)
							);
				}
			}
		}
		currentMethod.addExpression(_switch);

		loopDetector.updateFunctionReturn(currentMethod);

		return currentMethod;
	}

	/**
	 * Add code choosing a action
	 * DTMC: all updates are chosen with the same probability - one need to subtract probability of previous updates
	 *  and scale it to [0,1)
	 * CTMC: updates have different probability, one need to walk through all actions and sum rates,
	 * until the selection is reached
	 * @param currentMethod
	 * @throws KernelException
	 */
	protected abstract void updateMethodPerformSelection(Method currentMethod) throws KernelException;

	/**
	 * DTMC: number of commands
	 * CTMC: no additional arg
	 * @param currentMethod
	 * @throws KernelException
	 */
	protected abstract void updateMethodAdditionalArgs(Method currentMethod) throws KernelException;

	/**
	 * CTMC: float sum, float newSum and initialize selection with zero
	 * DTMC: no additional variable, initialize selection with selectionSum * number of commands
	 * @param currentMethod
	 * @throws KernelException
	 */
	protected abstract void updateMethodLocalVars(Method currentMethod) throws KernelException;
}
