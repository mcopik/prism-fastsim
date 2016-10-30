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
import static simulator.opencl.kernel.expression.ExpressionGenerator.createAssignment;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createBinaryExpression;
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;
import static simulator.opencl.kernel.expression.ExpressionGenerator.functionCall;
import static simulator.opencl.kernel.expression.ExpressionGenerator.postIncrement;

import java.util.LinkedHashMap;
import java.util.List;

import prism.Preconditions;
import prism.PrismLangException;
import simulator.opencl.RuntimeConfig;
import simulator.opencl.automaton.AbstractAutomaton;
import simulator.opencl.automaton.command.Command;
import simulator.opencl.automaton.command.SynchronizedCommand;
import simulator.opencl.kernel.expression.ComplexKernelComponent;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.ExpressionGenerator;
import simulator.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.opencl.kernel.expression.ForLoop;
import simulator.opencl.kernel.expression.IfElse;
import simulator.opencl.kernel.expression.Method;
import simulator.opencl.kernel.memory.ArrayType;
import simulator.opencl.kernel.memory.CLValue;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.RValue;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.opencl.kernel.memory.StdVariableType.StdType;
import simulator.opencl.kernel.memory.StructureType;
import simulator.opencl.kernel.memory.VariableTypeInterface;
import simulator.sampler.SamplerBoolean;
import simulator.sampler.SamplerDouble;

public class KernelGeneratorDTMC extends KernelGenerator
{
	/**
	 * Maximal (theoretical) number of generated updates, every every synchronized update.
	 * Used to detect variable size to contain number of updates.
	 */
	protected int maximalNumberOfSynchsUpdates = 0;

	/**
	 * Constructor for DTMC kernel generator.
	 * @param model
	 * @param properties
	 * @param rewardProperties
	 * @param config
	 * @throws PrismLangException 
	 */
	public KernelGeneratorDTMC(AbstractAutomaton model, List<SamplerBoolean> properties, List<SamplerDouble> rewardProperties, RuntimeConfig config)
			throws KernelException, PrismLangException
	{
		super(model, properties, rewardProperties, config);
	}

	@Override
	protected VariableTypeInterface timeVariableType()
	{
		return new StdVariableType(0, config.maxPathLength);
	}
	
	@Override
	protected void createSynchronizedStructures()
	{
		synchronizedStates = new LinkedHashMap<>();
		CLVariable size = null, array = null, guards = null;
		int sum = 1, max = 0;
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
		CLVariable varTime = new CLVariable(varTimeType, "time");
		varTime.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(varTime);
		localVars.put(LocalVar.TIME, varTime);
		//number of transitions
		if(hasNonSynchronized) {
			varSelectionSize = new CLVariable(new StdVariableType(0, model.commandsNumber()), "selectionSize");
			varSelectionSize.setInitValue(StdVariableType.initialize(0));
			currentMethod.addLocalVar(varSelectionSize);
		}
		//number of synchronized transitions
		if (hasSynchronized) {
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
		//don't need to do anything!
	}

	@Override
	protected void mainMethodUpdateTimeAfter(Method currentMethod, ComplexKernelComponent parent)
	{
		parent.addExpression(addComma(postIncrement(currentMethod.getLocalVar("time"))));
	}

	@Override
	protected int mainMethodRandomsPerIteration()
	{
		return 1;
	}

	@Override
	protected void mainMethodCallNonsynUpdateImpl(ComplexKernelComponent parent, CLValue... args)
	{
		if (args.length == 0) {
			Expression rndNumber = new Expression(String.format("%s%%%d", varPathLength.getSource().toString(), config.prngType.numbersPerRandomize()));
			CLValue random = config.prngType.getRandomUnifFloat(rndNumber);
			parent.addExpression(mainMethodCallNonsynUpdateImpl(random, varSelectionSize));
		} else if (args.length == 2) {
			parent.addExpression(mainMethodCallNonsynUpdateImpl(args[0], args[1]));
		} else {
			throw new RuntimeException("Illegal number of parameters for mainMethodCallNonsynUpdateImpl @ KernelGeneratorDTMC, required 0 or 2!");
		}
	}

	/**
	 * Generate direct call non-synchronized update method.
	 * stateVector, guardsTab, random, selectionSize
	 * @param rnd
	 * @param sum
	 * @return method call expression
	 */
	private Expression mainMethodCallNonsynUpdateImpl(CLValue rnd, CLValue sum)
	{
		Method update = helperMethods.get(KernelMethods.PERFORM_UPDATE);
		Expression call = update.callMethod(
		//stateVector
				localVars.get(LocalVar.STATE_VECTOR).convertToPointer(),
				//non-synchronized guards tab
				varGuardsTab,
				//random float [0,1]
				rnd,
				//number of commands
				sum);
		return loopDetector.kernelCallUpdate(call);
	}

	@Override
	protected CLVariable mainMethodBothUpdatesSumVar()
	{
		return new CLVariable(new StdVariableType(0, maximalNumberOfSynchsUpdates), "synSum");
	}

	@Override
	protected IfElse mainMethodBothUpdatesCondition(CLVariable selection)
	{
		Expression sum = createBinaryExpression(varSelectionSize.getSource(), Operator.ADD, varSynSelectionSize.getSource());
		addParentheses(sum);
		Expression condition = createBinaryExpression(selection.getSource(), Operator.LT,
		//nonSyn/(syn+nonSyn)
				createBinaryExpression(varSelectionSize.cast("float"), Operator.DIV, sum));
		IfElse ifElse = new IfElse(condition);
		/**
		 * if(selection < selectionSize/sum)
		 * callNonsynUpdate(..)
		 */
		mainMethodCallNonsynUpdate(ifElse, selection, new RValue(sum));
		return ifElse;
	}

	@Override
	protected Expression mainMethodSynUpdateCondition(CLVariable selection, CLVariable synSum, Expression sum)
	{
		return createBinaryExpression(selection.getSource(), Operator.LT,
		//probability < synSum/sum
				createBinaryExpression(synSum.cast("float"), Operator.DIV, sum));
	}

	@Override
	protected void mainMethodSynRecomputeSelection(ComplexKernelComponent parent, CLVariable selection, CLVariable synSum, Expression sum,
			CLVariable currentLabelSize)
	{
		//selection*sum - synSum
		Expression probUpdate = createBinaryExpression(selection.getSource(), Operator.MUL, sum);
		probUpdate = createBinaryExpression(probUpdate, Operator.SUB, synSum.getSource());
		/**
		 * Takes value from [synSum/sum,synSum/sum+currentLabelSize/sum] to an interval [0,1]
		 * selection = (selection*sum -synSum)/currentLabelSize
		 */
		parent.addExpression(createAssignment(selection, probUpdate));
		parent.addExpression(createBinaryExpression(selection.getSource(), Operator.DIV_AUGM, currentLabelSize.getSource()));
	}

	@Override
	protected CLVariable mainMethodSelectionVar(Expression selectionSize)
	{
		CLVariable selection = new CLVariable(new StdVariableType(StdType.FLOAT), "selection");
		Expression rndNumber = new Expression(String.format("%s%%%d", varPathLength.getSource().toString(),
		//pathLength%2 for Random123
				config.prngType.numbersPerRandomize()));
		selection.setInitValue(config.prngType.getRandomUnifFloat(fromString(rndNumber)));
		return selection;
	}

	/*********************************
	 * NON-SYNCHRONIZED GUARDS CHECK
	 ********************************/

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
	protected void guardsMethodCreateCondition(Method currentMethod, int position, Expression guard)
	{
		CLVariable guardsTab = currentMethod.getArg("guardsTab");
		Preconditions.checkNotNull(guardsTab, "");
		CLVariable counter = currentMethod.getLocalVar("counter");
		Preconditions.checkNotNull(counter, "");

		CLVariable tabPos = guardsTab.accessElement(ExpressionGenerator.postIncrement(counter));
		IfElse ifElse = new IfElse(guard);
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

	/*********************************
	 * NON-SYNCHRONIZED UPDATE
	 ********************************/

	@Override
	protected void updateMethodPerformSelection(Method currentMethod) throws KernelException
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

	/*********************************
	 * SYNCHRONIZED GUARDS CHECK
	 ********************************/

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
	protected void guardsSynAddGuard(ComplexKernelComponent parent, CLVariable guardArray, Command cmd, CLVariable size)
	{
		Expression guard = convertPrismGuard(svPtrTranslations, cmd.getGuard());
		parent.addExpression(createAssignment(guardArray, guard));
		parent.addExpression(createBinaryExpression(size.getSource(), Operator.ADD_AUGM,
		//converted guard
				guardArray.getSource()));
	}

	/*********************************
	 * SYNCHRONIZED UPDATE
	 ********************************/

	@Override
	protected void updateSynAdditionalVars(Method parent, SynchronizedCommand cmd)
	{

	}

	@Override
	protected void updateSynBeforeUpdateLabel(Method parent, SynchronizedCommand cmd, int moduleNumber, CLVariable guardsTab, CLVariable guard,
			CLVariable moduleSize, CLVariable totalSize, CLVariable probability)
	{
		/**
		 * compute current guard in update
		 */
		Expression guardUpdate = functionCall("floor",
		//probability * module_size
				createBinaryExpression(probability.getSource(), Operator.MUL, moduleSize.getSource()));
		parent.addExpression(createAssignment(guard, guardUpdate));
		/**
		* recompute probability to an [0,1) in selected guard
		*/
		Expression probUpdate = createBinaryExpression(
		//probability * module_size
				createBinaryExpression(probability.getSource(), Operator.MUL, moduleSize.getSource()), Operator.SUB,
				//guard
				guard.getSource());
		parent.addExpression(createAssignment(probability, probUpdate));
	}

	@Override
	protected void updateSynAfterUpdateLabel(ComplexKernelComponent parent, CLVariable guard, CLVariable moduleSize, CLVariable totalSize,
			CLVariable probability)
	{
		/**
		 * totalSize /= 1 is useless
		 */
		parent.addExpression(createBinaryExpression(totalSize.getSource(), Operator.DIV_AUGM, moduleSize.getSource()));
	}

	@Override
	protected CLVariable updateSynLabelMethodGuardCounter(SynchronizedCommand cmd)
	{
		CLVariable guardCounter = new CLVariable(new StdVariableType(-1, cmd.getCmdsNum()), "guardCounter");
		guardCounter.setInitValue(StdVariableType.initialize(-1));
		return guardCounter;
	}

	@Override
	protected CLVariable updateSynLabelMethodGuardSelection(SynchronizedCommand cmd, CLVariable guard)
	{
		CLVariable guardCounter = new CLVariable(new StdVariableType(0, cmd.getCmdsNum()), "guardSelection");
		guardCounter.setInitValue(StdVariableType.initialize(0));
		return guardCounter;
	}

	@Override
	protected void updateSynLabelMethodSelectGuard(Method currentMethod, ComplexKernelComponent parent, CLVariable guardSelection, CLVariable guardCounter,
			int moduleOffset)
	{
		CLVariable guardsTab = currentMethod.getArg("guards");
		CLVariable guard = currentMethod.getArg("guard");
		/**
		 * guardSelection = -1;
		 * guardCounter = 0;
		 * for(;;guardCounter++) {
		 * 	guardSelection += guards[moduleOffset+guardCounter];
		 * 	if(guardsSelection == guard)
		 * 		break;
		 * }
		 * switch(guardCounter)...
		 */
		ForLoop guardSelectionLoop = new ForLoop(guardSelection, false);
		CLVariable guardsAccess = guardsTab.accessElement(
		// moduleOffset(constant!) + guardCounter
				createBinaryExpression(fromString(moduleOffset), Operator.ADD, guardSelection.getSource()));
		guardSelectionLoop.addExpression(createBinaryExpression(guardCounter.getSource(),
		//guardSelection += guards[moduleOffset + guardCounter];
				Operator.ADD_AUGM, guardsAccess.getSource()));
		IfElse ifElseGuardLoop = new IfElse(createBinaryExpression(guardCounter.getSource(),
		//guardSelection == guard
				Operator.EQ, guard.getSource()));
		ifElseGuardLoop.addExpression("break\n");
		guardSelectionLoop.addExpression(ifElseGuardLoop);
		parent.addExpression(guardSelectionLoop);
	}
}
