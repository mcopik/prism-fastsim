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
import static simulator.opencl.kernel.expression.ExpressionGenerator.convertPrismGuard;
import static simulator.opencl.kernel.expression.ExpressionGenerator.convertPrismRate;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createAssignment;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createBinaryExpression;
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import prism.PrismLangException;
import simulator.opencl.RuntimeConfig;
import simulator.opencl.automaton.AbstractAutomaton;
import simulator.opencl.automaton.ParsTreeModifier;
import simulator.opencl.automaton.PrismVariable;
import simulator.opencl.automaton.command.Command;
import simulator.opencl.automaton.command.CommandInterface;
import simulator.opencl.automaton.command.SynchronizedCommand;
import simulator.opencl.automaton.update.Action;
import simulator.opencl.automaton.update.Rate;
import simulator.opencl.automaton.update.Update;
import simulator.opencl.kernel.StateVector.Translations;
import simulator.opencl.kernel.expression.ComplexKernelComponent;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.ExpressionGenerator;
import simulator.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.opencl.kernel.expression.ForLoop;
import simulator.opencl.kernel.expression.IfElse;
import simulator.opencl.kernel.expression.KernelComponent;
import simulator.opencl.kernel.expression.KernelMethod;
import simulator.opencl.kernel.expression.Method;
import simulator.opencl.kernel.expression.Switch;
import simulator.opencl.kernel.memory.ArrayType;
import simulator.opencl.kernel.memory.CLValue;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.CLVariable.Location;
import simulator.opencl.kernel.memory.PointerType;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.opencl.kernel.memory.StdVariableType.StdType;
import simulator.opencl.kernel.memory.StructureType;
import simulator.opencl.kernel.memory.VariableTypeInterface;
import simulator.sampler.SamplerBoolean;
import simulator.sampler.SamplerDouble;

public abstract class KernelGenerator
{
	//protected CLVariable varTime = null;
	//TODO: move to parent
	public VariableTypeInterface varTimeType = null;
	//protected CLVariable varSelectionSize = null;
	//protected CLVariable varStateVector = null;
	protected CLVariable varPathLength = null;
	//protected CLVariable varSynSelectionSize = null;
	protected CLVariable varGuardsTab = null;
	//protected CLVariable[] varSynchronizedStates = null;
	
	public enum LocalVar
	{
		/**
		 * PRISM model variables
		 */
		STATE_VECTOR,
		/**
		 * Time of entering state
		 */
		TIME,
		/**
		 * Time of leaving state
		 */
		UPDATED_TIME,
		/**
		 * Size of unsynchronized transitions.
		 * DTMC: unsigned integer, count of active transitions
		 * CTMC: float, sum of rates for all active transitions
		 */
		UNSYNCHRONIZED_SIZE,
		/**
		 * Size of synchronized transitions.
		 * DTMC: unsigned integer, count of active transitions
		 * CTMC: float, sum of rates for all active transitions
		 */
		SYNCHRONIZED_SIZE,
		/**
		 * Count of all transitions.
		 * May be required 
		 */
		TRANSITIONS_COUNTER
	}

	protected final static EnumMap<LocalVar, String> LOCAL_VARIABLES_NAMES;
	static {
		EnumMap<LocalVar, String> names = new EnumMap<>(LocalVar.class);
		names.put(LocalVar.STATE_VECTOR, "stateVector");
		names.put(LocalVar.UNSYNCHRONIZED_SIZE, "selectionSize");
		names.put(LocalVar.SYNCHRONIZED_SIZE, "selectionSynSize");
		names.put(LocalVar.TRANSITIONS_COUNTER, "transitionsCount");
		LOCAL_VARIABLES_NAMES = names;
	}
	protected EnumMap<LocalVar, CLVariable> localVars = null;
	
	protected enum KernelMethods {
		/**
		 * DTMC:
		 * Return value is number of concurrent transitions.
		 * int checkGuards(StateVector * sv, bool * guardsTab);
		 * CTMC:
		 * Return value is rates sum of transitions in race condition.
		 * float checkGuards(StateVector * sv, bool * guardsTab);
		 */
		//CHECK_GUARDS(0),
		/**
		 * DTMC:
		 * Return value is number of concurrent transitions.
		 * int checkGuardsSyn(StateVector * sv, SynCmdState ** tab);
		 * CTMC:
		 * Return value is rates sum of transitions in race condition.
		 * float checkGuardsSyn(StateVector * sv, SynCmdState * tab);
		 */
		//CHECK_GUARDS_SYN(1),
		/**
		 * DTMC:
		 * void performUpdate(StateVector * sv, bool * guardsTab, float sumSelection, int allTransitions);
		 * CTMC:
		 * void performUpdate(StateVector * sv,  bool * guardsTab,float sumSelection);
		 */
		//PERFORM_UPDATE(2),
		/**
		 * DTMC:
		 * void performUpdateSyn(StateVector * sv, int updateSelection,SynCmdState * tab);
		 * CTMC:
		 * void performUpdateSyn(StateVector * sv, float sumSelection,SynCmdState * tab);
		 */
		PERFORM_UPDATE_SYN(3);
		public final int indice;

		private KernelMethods(int indice)
		{
			this.indice = indice;
		}

		public final static int SIZE = KernelMethods.values().length;
	}

	/**
	 * struct StateVector {
	 * 	each_variable;
	 * }
	 */
	protected StateVector stateVector = null;

	protected StructureType synCmdState = null;
	protected AbstractAutomaton model = null;
	protected RuntimeConfig config = null;
	protected Command commands[] = null;
	protected SynchronizedCommand synCommands[] = null;
	
	/**
	 * Generate source code for property verification.
	 */
	protected ProbPropertyGenerator propertyGenerator = null;

	/**
	 * Additional declarations in kernel - structure types for StateVector,
	 * synchronized updates, saved variables etc.
	 */
	protected List<KernelComponent> additionalDeclarations = new ArrayList<>();

	/**
	 * Methods:
	 * - non-synchronized guards check & update
	 * - synchronized guards check & update
	 * - property verification
	 * - reward update
	 */
	protected EnumMap<KernelMethods, Method> helperMethods = new EnumMap<>(KernelMethods.class);

	/**
	 * Main kernel method.
	 */
	protected KernelMethod mainMethod = null;

	/**
	 * Probabilistic properties for this kernel.
	 */
	protected List<SamplerBoolean> properties = null;
	
	/**
	 * Reward properties for this kernel.
	 */
	protected List<SamplerDouble> rewardProperties = null;

	/**
	 * Helper class used to generate code for computation of rewards and evaluation of their properties.
	 */
	protected RewardGenerator rewardGenerator = null;

	/**
	 * Pseudo-random number generator type.
	 */
	protected PRNGType prngType = null;
	
	/**
	 * Helper class to generate code for unsynchronized commands.
	 */
	protected CommandGenerator cmdGenerator = null;

	/**
	 * Helper class to generate code for synchronized commands.
	 */
	protected SynchCommandGenerator synCmdGenerator = null;

	/**
	 * TreeVisitor instance, used for parsing of properties (model parsing has been already done in Automaton class)
	 */
	protected ParsTreeModifier treeVisitor = new ParsTreeModifier();
	
	/**
	 * Generates code for loop detection.
	 */
	protected LoopDetector loopDetector = null;

	/**
	 * Contains already prepared access formulas to a pointer to StateVector.
	 * All helper methods use a pointer to StateVector.
	 * Example:
	 * variable -> (*sv).STATE_VECTOR_PREFIX_variable
	 */
	//StateVector.Translations svPtrTranslations = new HashMap<>();

	/**
	 * Constructor.
	 * @param model
	 * @param properties
	 * @param rewardProperties
	 * @param config
	 * @throws PrismLangException 
	 */
	public KernelGenerator(AbstractAutomaton model, List<SamplerBoolean> properties, List<SamplerDouble> rewardProperties, RuntimeConfig config)
			throws KernelException, PrismLangException
	{
		this.model = model;
		this.config = config;
		this.prngType = config.prngType;
		this.properties = properties;
		this.localVars = new EnumMap<>(LocalVar.class);

		stateVector = new StateVector(model);
		//VariableTypeInterface stateVectorType = stateVector.getType();
		additionalDeclarations.add(stateVector.getType().getDefinition());
		// create translations from model variable to StateVector structure, accessed by a pointer
		//CLVariable sv = new CLVariable(new PointerType(stateVectorType), "sv");
		//StateVector.createTranslations(sv, stateVectorType, svPtrTranslations);
		varTimeType = timeVariableType();
		int synSize = model.synchCmdsNumber();
		int size = model.commandsNumber();

		this.propertyGenerator = ProbPropertyGenerator.createGenerator(this, model.getType());
		additionalDeclarations.addAll(propertyGenerator.getDefinitions());

		this.rewardProperties = rewardProperties;
		this.rewardGenerator = RewardGenerator.createGenerator(this, model.getType());
		additionalDeclarations.addAll(rewardGenerator.getDefinitions());

		// loop detector
		this.loopDetector = new LoopDetector(this, propertyGenerator, rewardGenerator);
		
		// import commands
		synCommands = new SynchronizedCommand[synSize];
		if (synSize != 0) {
			//hasSynchronized = true;
		}
		commands = new Command[size - synSize];
		if (size - synSize != 0) {
			//hasNonSynchronized = true;
		}

		int normalCounter = 0, synCounter = 0;
		for (int i = 0; i < size; ++i) {
			CommandInterface cmd = model.getCommand(i);
			if (!cmd.isSynchronized()) {
				commands[normalCounter++] = (Command) cmd;
			} else {
				synCommands[synCounter++] = (SynchronizedCommand) cmd;
			}
		}
		
		cmdGenerator = CommandGenerator.createGenerator(this);
		synCmdGenerator = SynchCommandGenerator.createGenerator(this);
		additionalDeclarations.addAll(cmdGenerator.getDefinitions());
		additionalDeclarations.addAll(synCmdGenerator.getDefinitions());
		// PRNG definitions
		if (prngType.getAdditionalDefinitions() != null) {
			additionalDeclarations.addAll(prngType.getAdditionalDefinitions());
		}
	}
	
	/**
	 * @return type (integer/floating-point) of variable keeping current time
	 */
	protected abstract VariableTypeInterface timeVariableType();

	/**
	 * Return declarations manually specified earlier and synchronization structures definitions.
	 * @return additional global declarations
	 */
	public List<KernelComponent> getAdditionalDeclarations()
	{
		return additionalDeclarations;
	}

	/**
	 * @return all helper methods used in kernel
	 */
	public Collection<Method> getHelperMethods()
	{
		List<Method> ret = new ArrayList<>();
		ret.addAll(helperMethods.values());
		ret.addAll(cmdGenerator.getMethods());
		ret.addAll(synCmdGenerator.getMethods());
		ret.addAll(propertyGenerator.getMethods());
		ret.addAll(rewardGenerator.getMethods());
		return ret;
	}

	
	/*********************************
	 * MAIN METHOD
	 * @throws PrismLangException, KernelException 
	 ********************************/
	public Method createMainMethod() throws KernelException, PrismLangException
	{
		Method currentMethod = new KernelMethod();
		currentMethod.addInclude(prngType.getIncludes());

		/**
		 * Main method arguments.
		 */

		//ARG 0: prng input
		currentMethod.addArg(prngType.getAdditionalInput());
		//ARG 1: number of simulations in this iteration
		CLVariable numberOfSimulations = new CLVariable(new StdVariableType(StdType.UINT32), "numberOfSimulations");
		currentMethod.addArg(numberOfSimulations);
		//ARG 2: sample number for PRNG
		CLVariable sampleNumber = new CLVariable(new StdVariableType(StdType.UINT32), "sampleNumber");
		currentMethod.addArg(sampleNumber);
		//ARG 3: offset in access into global array of results
		CLVariable resultsOffset = new CLVariable(new StdVariableType(StdType.UINT32), "resultsOffset");
		currentMethod.addArg(resultsOffset);
		//ARG 4: offset in access into global array of path
		CLVariable pathOffset = new CLVariable(new StdVariableType(StdType.UINT32), "pathOffset");
		currentMethod.addArg(pathOffset);
		//ARG 5: path lengths buffer
		CLVariable pathLengths = new CLVariable(new PointerType(new StdVariableType(StdType.UINT32)), "pathLengths");
		pathLengths.memLocation = Location.GLOBAL;
		currentMethod.addArg(pathLengths);
		//ARG 6..N: property results
		currentMethod.addArg(propertyGenerator.getKernelArgs());
		//ARG N+1...M: reward property results
		currentMethod.addArg(rewardGenerator.getKernelArgs());

		/**
		 * Local variables.
		 */

		//global ID of thread
		CLVariable globalID = new CLVariable(new StdVariableType(StdType.UINT32), "globalID");
		globalID.setInitValue(ExpressionGenerator.assignGlobalID());
		currentMethod.addLocalVar(globalID);

		//state vector for model
		CLVariable varStateVector = new CLVariable(stateVector.getType(),
				LOCAL_VARIABLES_NAMES.get(LocalVar.STATE_VECTOR));
		varStateVector.setInitValue(
				stateVector.initStateVector(config.initialState)
				);
		currentMethod.addLocalVar(varStateVector);
		localVars.put(LocalVar.STATE_VECTOR, varStateVector);

		//property results
		currentMethod.addLocalVar( propertyGenerator.getLocalVars() );
		//reward structures and results
		currentMethod.addLocalVar( rewardGenerator.getLocalVars() );

		//non-synchronized commands vars
		currentMethod.addLocalVar( cmdGenerator.getLocalVars() );
		//number of transitions
		if( cmdGenerator.isGeneratorActive() ) {
			CLVariable varSelectionSize = new CLVariable(
					cmdGenerator.kernelUpdateSizeType(),
					LOCAL_VARIABLES_NAMES.get(LocalVar.UNSYNCHRONIZED_SIZE)
					);
			varSelectionSize.setInitValue(StdVariableType.initialize(0));
			currentMethod.addLocalVar(varSelectionSize);
			localVars.put(LocalVar.UNSYNCHRONIZED_SIZE, varSelectionSize);
		}
		
		//synchronized commands vars
		currentMethod.addLocalVar( synCmdGenerator.getLocalVars() );
		if ( synCmdGenerator.isGeneratorActive() ) {
			//number of transitions
			CLVariable varSynSelectionSize = new CLVariable(
					synCmdGenerator.kernelUpdateSizeType(),
					LOCAL_VARIABLES_NAMES.get(LocalVar.SYNCHRONIZED_SIZE)
					);
			varSynSelectionSize.setInitValue(StdVariableType.initialize(0));
			currentMethod.addLocalVar(varSynSelectionSize);
			localVars.put(LocalVar.SYNCHRONIZED_SIZE, varSynSelectionSize);
		}

		//pathLength
		varPathLength = new CLVariable(new StdVariableType(StdType.UINT32), "pathLength");
		currentMethod.addLocalVar(varPathLength);
		//flag for loop detection
		currentMethod.addLocalVar(loopDetector.getLocalVars());
		//additional local variables, mainly selectionSize. depends on DTMC/CTMC
		mainMethodDefineLocalVars(currentMethod);
		
		StateVector.Translations translations = stateVector.createTranslations(varStateVector);

		/**
		 * Reject samples with globalID greater than numberOfSimulations
		 * Necessary in every kernel, because number of OpenCL kernel launches will be aligned
		 * (and almost always greater than number of ordered samples, buffer sizes etc).
		 */
		IfElse sampleNumberCheck = new IfElse(createBinaryExpression(globalID.getName(), Operator.GT, numberOfSimulations.getName()));
		sampleNumberCheck.addExpression("return;");
		currentMethod.addExpression(sampleNumberCheck);

		/**
		 * initialize generator
		 */
		currentMethod.addExpression(prngType.initializeGenerator());

		/**
		 * Initial check of properties, before making any computations.
		 */
		propertyGenerator.kernelFirstUpdateProperties(currentMethod, translations);
		
		/**
		 * Compute state rewards for the initial state.
		 */
		currentMethod.addExpression(rewardGenerator.kernelAfterUpdate(varStateVector));

		/**
		 * Main processing loop.
		 */
		ForLoop loop = new ForLoop(varPathLength, (long) 0, config.maxPathLength);

		/**
		 * Check how much numbers are generated with each randomize().
		 * If 1, then we do not need any earlier call - we will randomize variable when we need them.
		 */
		if (prngType.numbersPerRandomize() > 1) {
			//random only when it is necessary
			if (prngType.numbersPerRandomize() != mainMethodRandomsPerIteration()) {
				int iterationsPerRandomize = prngType.numbersPerRandomize() / mainMethodRandomsPerIteration();
				Expression condition = new Expression(String.format("%s %% %d", varPathLength.varName, iterationsPerRandomize));
				IfElse ifElse = new IfElse(createBinaryExpression(condition, Operator.EQ, fromString(0)));
				ifElse.addExpression(prngType.randomize());
				loop.addExpression(ifElse);
			}
			//each randomize will give randoms enough for one iteration
			else {
				loop.addExpression(prngType.randomize());
			}
		}

		/**
		 * check which guards are active
		 */
		loop.addExpression(cmdGenerator.kernelCallGuardCheck());
		/**
		 * If unsynchronized is not active and we count transitions,
		 * the counter has to be reseted before calling synchronized.
		 */
		CLVariable transitionCount = kernelGetLocalVar(LocalVar.TRANSITIONS_COUNTER);
		if(!cmdGenerator.isGeneratorActive() && transitionCount != null) {
			loop.addExpression(createAssignment(transitionCount, fromString(0)));
		}
		loop.addExpression(synCmdGenerator.kernelCallGuardCheck());
		
		/**
		 * update time -> in case of CTMC and bounded until we need two time values:
		 * 1) entering state
		 * 2) leaving state
		 * so current time is updated in After method()
		 * other cases: compute current time in After method(), 
		 * because the time is updated *after* making a transition
		 */
		mainMethodUpdateTimeBefore(currentMethod, loop);
		
		/**
		 * if all properties and reward properties are known, then we can end iterating
		 */
		Expression propertyCall = null;
		if (propertyGenerator.isGeneratorActive() && rewardGenerator.isGeneratorActive()) {
			propertyCall = createBinaryExpression(
					propertyGenerator.kernelUpdateProperties(),
					Operator.LAND, 
					rewardGenerator.kernelUpdateProperties(varStateVector, localVars.get(LocalVar.TIME))
					);
		} else if (rewardGenerator.isGeneratorActive()) {
			propertyCall = rewardGenerator.kernelUpdateProperties(varStateVector, localVars.get(LocalVar.TIME));
		} else {
			propertyCall = propertyGenerator.kernelUpdateProperties();
		}
		IfElse ifElse = new IfElse(propertyCall);
		ifElse.addExpression(0, new Expression("break;\n"));
		loop.addExpression(ifElse);

		/**
		 * Deadlock when number of possible choices is 0.
		 */
		IfElse deadlockState = new IfElse(kernelDeadlockExpression());
		deadlockState.addExpression(new Expression("break;\n"));
		loop.addExpression(deadlockState);

		/**
		 * call update method; 
		 * most complex case - both nonsyn and synchronized updates
		 */
		if (cmdGenerator.isGeneratorActive() && synCmdGenerator.isGeneratorActive()) {
			mainMethodCallBothUpdates(loop);
		}
		/**
		 * only synchronized updates
		 */
		else if (synCmdGenerator.isGeneratorActive()) {
			mainMethodCallSynUpdate(loop);
		}
		/**
		 * only nonsyn updates
		 */
		else {
			mainMethodCallNonsynUpdate(loop);
		}
		
		/**
		 * Necessary recomputations after state update.
		 * For CTMC may require current and new time which means that we have to
		 * do it before updating current time.
		 */
		loop.addExpression(rewardGenerator.kernelAfterUpdate(varStateVector));
		
		/**
		 * For CTMC&bounded until -> update current time.
		 */
		mainMethodUpdateTimeAfter(currentMethod, loop);

		/**
		 * Loop detection procedure - end computations in case of a loop.
		 */
		loopDetector.kernelLoopDetection(loop);
		currentMethod.addExpression(loop);

		/**
		 * Write results.
		 */
		//sampleNumber + globalID
		Expression position = createBinaryExpression(globalID.getSource(), Operator.ADD, pathOffset.getSource());
		//TODO: path length; add 1 because we start at zero
		//Expression pathLength = createBinaryExpression(pathLengths.accessElement(position).getSource(), Operator.ADD, fromString(1));
		//currentMethod.addExpression(createAssignment(pathLength.getSource(), varPathLength));
		CLVariable pathLength = pathLengths.accessElement(position);
		currentMethod.addExpression(createAssignment(pathLength, varPathLength));
		position = createBinaryExpression(globalID.getSource(), Operator.ADD, resultsOffset.getSource());

		propertyGenerator.kernelWriteOutput(currentMethod, position);
		rewardGenerator.kernelWriteOutput(currentMethod, position);

		// deinitialize PRNG
		currentMethod.addExpression(prngType.deinitializeGenerator());

		return currentMethod;
	}

	/**
	 * @return two random numbers are required for CTMC (path and time selection) and one for DTMC (path)
	 */
	protected abstract int mainMethodRandomsPerIteration();

	/**
	 * Create additional local variables.
	 * For DTMC, time and selectionSize is an integer.
	 * For CTMC, time and selectionSize is a float. Also adds updatedTime and 
	 * @param currentMethod
	 * @throws KernelException
	 */
	protected abstract void mainMethodDefineLocalVars(Method currentMethod) throws KernelException;
	
	/**
	 * Generate a call to the non-synchronized update method.
	 * There are two major steps:
	 * a) call a method for transition rewards update, if it is needed
	 * b) call the non-synch update method, which is different in CTMC and DTMC,
	 * implemented by mainMethodCallNonsynUpdateImpl
	 * @param parent
	 * @param args used to specify additional parameters which are already computed (random, sum)
	 * if they are not provided, use the default implementation
	 */
	protected void mainMethodCallNonsynUpdate(ComplexKernelComponent parent, CLValue... args) throws KernelException
	{
		/**
		 * If there are some reward properties, add a 
		 */
		parent.addExpression(rewardGenerator.kernelBeforeUpdate(localVars.get(LocalVar.STATE_VECTOR)));
		mainMethodCallNonsynUpdateImpl(parent, args);
	}

	/**
	 * Create the call expression for both updates: non-synchronized and synchronized.
	 * @param parent
	 * @throws KernelException 
	 */
	protected void mainMethodCallBothUpdates(ComplexKernelComponent parent) throws KernelException
	{
		CLVariable varSelectionSize = kernelGetLocalVar(LocalVar.UNSYNCHRONIZED_SIZE);
		CLVariable varSynSelectionSize = kernelGetLocalVar(LocalVar.SYNCHRONIZED_SIZE);
		//selection
		Expression sum = createBinaryExpression(varSelectionSize.getSource(), Operator.ADD, varSynSelectionSize.getSource());
		addParentheses(sum);
		CLVariable selection = mainMethodSelectionVar(sum);
		parent.addExpression(selection.getDefinition());
		IfElse ifElse = mainMethodBothUpdatesCondition(selection);
		/**
		 * else
		 * callSynUpdate()
		 */
		ifElse.addElse();
		//write everything to else
		ifElse.setConditionNumber(1);
		CLVariable synSum = mainMethodBothUpdatesSumVar();
		//start checking values from the low limit of non-syn size
		synSum.setInitValue(varSelectionSize);
		ifElse.addExpression(synSum.getDefinition());
		ifElse.addExpression( synCmdGenerator.kernelCallUpdate(selection, synSum, sum) );
		parent.addExpression(ifElse);
	}

	/**
	 * Create call to synchronized update - define necessary variables and call next method.
	 * @param parent
	 */
	protected void mainMethodCallSynUpdate(ComplexKernelComponent parent)
	{
		CLVariable varSynSelectionSize = kernelGetLocalVar(LocalVar.SYNCHRONIZED_SIZE);
		CLVariable selection = mainMethodSelectionVar(varSynSelectionSize.getSource());
		CLVariable synSum = mainMethodBothUpdatesSumVar();
		synSum.setInitValue(StdVariableType.initialize(0));
		parent.addExpression(selection.getDefinition());
		parent.addExpression(synSum.getDefinition());
		parent.addExpression( synCmdGenerator.kernelCallUpdate(selection, synSum, varSynSelectionSize.getSource()) );
	}

	/**
	 * @return variable to sum update sizes - float for CTMC, integer for DTMC
	 */
	protected abstract CLVariable mainMethodBothUpdatesSumVar();

	/**
	 * Create conditional for selection between non-synchronized and synchronized condition.
	 * Put call to non-synchronized update in first condition.
	 * @param selection
	 * @return if-else with completed 'if' case
	 * @throws KernelException 
	 */
	protected abstract IfElse mainMethodBothUpdatesCondition(CLVariable selection) throws KernelException;

	/**
	 * Creates randomized selection. For DTMC it's a [0,1) float, for CTMC - float in range of selection size (sum of rates).
	 * CTMC involves also different selection of random variable (two randoms per iteration, not one).
	 * @param selectionSize
	 * @return proper randomized variable containing selected update
	 */
	protected abstract CLVariable mainMethodSelectionVar(Expression selectionSize);

	/**
	 * Generate call to non-synchronized update. Different arguments for DTMC (additional variable - selectionSize).
	 * @param parent
	 * @param args specify here additional arguments which are already computed (randomized number, sum of active cmds)
	 */
	protected abstract void mainMethodCallNonsynUpdateImpl(ComplexKernelComponent parent, CLValue... args) throws KernelException;

	/**
	 * DTMC: increment time (previous time is obvious)
	 * CTMC: generate updatedTime, assign to time for non-timed properties
	 * @param currentMethod
	 * @param parent
	 */
	protected abstract void mainMethodUpdateTimeBefore(Method currentMethod, ComplexKernelComponent parent);

	/**
	 * DTMC: don't do anything
	 * CTMC: for timing properties - write updateTimed value to time (after processing properties)
	 * @param currentMethod
	 * @param parent
	 */
	protected abstract void mainMethodUpdateTimeAfter(Method currentMethod, ComplexKernelComponent parent);

	/*********************************
	 * OTHER METHODS
	 ********************************/

	public RuntimeConfig getRuntimeConfig()
	{
		return config;
	}

	public List<SamplerBoolean> getProbProperties()
	{
		return properties;
	}
	
	public List<SamplerDouble> getRewardProperties()
	{
		return rewardProperties;
	}

	public AbstractAutomaton getModel()
	{
		return model;
	}
	
	public StateVector getSV()
	{
		return stateVector;
	}
	
	/**
	 * Get local var declared in main kernel method.
	 * @param var
	 * @return
	 */
	public CLVariable kernelGetLocalVar(LocalVar var)
	{
		return localVars.get(var);
	}
	
	/**
	 * Currently only usage is allowed and advised:
	 * LoopDetector needs to define counter for all transitions for CTMCs.
	 * Put variable in local vars set.
	 * @param var
	 * @param clVar
	 * @return
	 */
	public CLVariable kernelCreateTransitionCounter()
	{
		CLVariable varTransCounter = new CLVariable(
				new StdVariableType(0,model.commandsNumber() + model.synchCmdsNumber()),
				LOCAL_VARIABLES_NAMES.get(LocalVar.TRANSITIONS_COUNTER));
		varTransCounter.setInitValue(StdVariableType.initialize(0));
		localVars.put(LocalVar.TRANSITIONS_COUNTER, varTransCounter);
		return varTransCounter;
	}
	
	/**
	 * @return simply return a boolean expression with value marking
	 * if a loop has been detected
	 */
	public Expression kernelLoopExpression()
	{
		return loopDetector.kernelLoopExpression();
	}
	
	/**
	 * @return integer expression with number of active updates 
	 */
	public Expression kernelActiveUpdates()
	{
		CLVariable varSelectionSize = kernelGetLocalVar(LocalVar.UNSYNCHRONIZED_SIZE);
		CLVariable varSynSelectionSize = kernelGetLocalVar(LocalVar.SYNCHRONIZED_SIZE);
		CLVariable varTransitioncounter = kernelGetLocalVar(LocalVar.TRANSITIONS_COUNTER);
		if (varTransitioncounter != null) {
			return varTransitioncounter.getSource();
		} else if (cmdGenerator.isGeneratorActive() &&
				synCmdGenerator.isGeneratorActive() ) {
			return createBinaryExpression(
					varSelectionSize.getSource(),
					Operator.ADD,
					varSynSelectionSize.getSource()
					);
		} else if ( synCmdGenerator.isGeneratorActive() ) {
			return varSynSelectionSize.getSource();
		} else {
			return varSelectionSize.getSource();
		}
	}
	
	/**
	 * Depending on existence of labels, compares count of:
	 * - synchronized + unsynchronized
	 * - unsynchronized
	 * - synchronized
	 * active labels to zero.
	 * @return
	 */
	public Expression kernelDeadlockExpression()
	{
		return createBinaryExpression(
				kernelActiveUpdates(),
				ExpressionGenerator.Operator.EQ, fromString(0));
	}
	
	/**
	 * @return loop detector instance
	 */
	public LoopDetector getLoopDetector()
	{
		return loopDetector;
	}
	
	/**
	 * @return reward generator instance
	 */
	public RewardGenerator getRewardGenerator()
	{
		return rewardGenerator;
	}
	
	public Command[] getCommands()
	{
		return commands;
	}
	
	public SynchronizedCommand[] getSynchCommands()
	{
		return synCommands;
	}
	
	/**
	 * @return visitor object to apply for PRISM code
	 */
	public ParsTreeModifier getTreeVisitor()
	{
		return treeVisitor;
	}

}
