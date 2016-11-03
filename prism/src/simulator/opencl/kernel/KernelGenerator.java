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
	protected CLVariable[] varSynchronizedStates = null;
	
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
		CHECK_GUARDS_SYN(1),
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
	protected StructureType stateVectorType = null;

	protected Map<String, StructureType> synchronizedStates = new LinkedHashMap<>();
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
	 * List of synchronized guard check methods, one for each label.
	 */
	protected List<Method> synchronizedGuards = null;
	/**
	 * List of synchronized update methods, one for each label.
	 */
	protected List<Method> synchronizedUpdates = null;
	/**
	 * List of additional methods.
	 */
	protected List<Method> additionalMethods = null;

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
	 * True when model contains synchronized commands.
	 */
	protected boolean hasSynchronized = false;
	
	/**
	 * Helper class to generate code for unsynchronized commands.
	 */
	protected CommandGenerator cmdGenerator = null;

	/**
	 * True when model contains 'normal' commands.
	 */
	//protected boolean hasNonSynchronized = false;

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
	Map<String, String> svPtrTranslations = new HashMap<>();

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

		stateVectorType = StateVector.importStateVector(model);
		additionalDeclarations.add(stateVectorType.getDefinition());
		// create translations from model variable to StateVector structure, accessed by a pointer
		CLVariable sv = new CLVariable(new PointerType(stateVectorType), "sv");
		StateVector.createTranslations(sv, stateVectorType, svPtrTranslations);
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
		if (synSize != 0) {
			synCommands = new SynchronizedCommand[synSize];
			hasSynchronized = true;
		}
		if (size - synSize != 0) {
			commands = new Command[size - synSize];
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
		additionalDeclarations.addAll(cmdGenerator.getDefinitions());

		// property and synchronized structure definitions
		if (hasSynchronized) {
			createSynchronizedStructures();
		}
		
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
	 * Create structures for synchronized commands.
	 * Generated structure types are different for DTMC and CTMC.
	 */
	protected abstract void createSynchronizedStructures();

	/**
	 * Return declarations manually specified earlier and synchronization structures definitions.
	 * @return additional global declarations
	 */
	public List<KernelComponent> getAdditionalDeclarations()
	{
		if (synchronizedStates != null) {
			for (StructureType type : synchronizedStates.values()) {
				additionalDeclarations.add(type.getDefinition());
			}
		}
		return additionalDeclarations;
	}

	/**
	 * @return all helper methods used in kernel
	 */
	public Collection<Method> getHelperMethods()
	{
		List<Method> ret = new ArrayList<>();
		ret.addAll(helperMethods.values());
		if (synchronizedGuards != null) {
			ret.addAll(synchronizedGuards);
		}
		if (synchronizedUpdates != null) {
			ret.addAll(synchronizedUpdates);
		}
		if (additionalMethods != null) {
			ret.addAll(additionalMethods);
		}
		ret.addAll(cmdGenerator.getMethods());
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
		CLVariable varStateVector = new CLVariable(stateVectorType,
				LOCAL_VARIABLES_NAMES.get(LocalVar.STATE_VECTOR));
		varStateVector.setInitValue(
				StateVector.initStateVector(model, stateVectorType, config.initialState)
				);
		currentMethod.addLocalVar(varStateVector);
		localVars.put(LocalVar.STATE_VECTOR, varStateVector);

		//property results
		currentMethod.addLocalVar( propertyGenerator.getLocalVars() );
		//reward structures and results
		currentMethod.addLocalVar( rewardGenerator.getLocalVars() );
		//non-synchronized commands vars
		currentMethod.addLocalVar( cmdGenerator.getLocalVars() );
		
		//synchronized state
		if (hasSynchronized) {
			varSynchronizedStates = new CLVariable[synchronizedStates.size()];
			int counter = 0;
			for (Map.Entry<String, StructureType> types : synchronizedStates.entrySet()) {
				varSynchronizedStates[counter] = new CLVariable(types.getValue(),
				//synchState_label
						String.format("synchState_%s", types.getKey()));
				currentMethod.addLocalVar(varSynchronizedStates[counter++]);
			}
		}
		//pathLength
		varPathLength = new CLVariable(new StdVariableType(StdType.UINT32), "pathLength");
		currentMethod.addLocalVar(varPathLength);
		//flag for loop detection
		currentMethod.addLocalVar(loopDetector.getLocalVars());
		//additional local variables, mainly selectionSize. depends on DTMC/CTMC
		mainMethodDefineLocalVars(currentMethod);

		/**
		 * Create helpers method.
		 */
		//if (hasNonSynchronized) {
			//helperMethods.put(KernelMethods.CHECK_GUARDS, createNonsynGuardsMethod());
			//helperMethods.put(KernelMethods.PERFORM_UPDATE, createNonsynUpdate());
		//}
		if (hasSynchronized) {
			createSynGuardsMethod();
			createUpdateMethodSyn();
		}

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
		propertyGenerator.kernelFirstUpdateProperties(currentMethod);
		
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
		if (hasSynchronized) {
			loop.addExpression(createAssignment(kernelGetLocalVar(LocalVar.SYNCHRONIZED_SIZE), fromString(0)));
			
			CLVariable transitionCount = kernelGetLocalVar(LocalVar.TRANSITIONS_COUNTER);
			if(transitionCount != null) {
				loop.addExpression(createAssignment(transitionCount, fromString(0)));
			}
			
			for (int i = 0; i < synCommands.length; ++i) {
				// call guard.
				Expression callMethod = synchronizedGuards.get(i).callMethod(
				//&stateVector
						localVars.get(LocalVar.STATE_VECTOR).convertToPointer(),
						//synchState
						varSynchronizedStates[i].convertToPointer());
				CLVariable transactionCounter = kernelGetLocalVar(LocalVar.TRANSITIONS_COUNTER);
				// transactionCounter += synGuards();
				if(transactionCounter != null) {
					loop.addExpression( createBinaryExpression(
							transactionCounter.getSource(),
							Operator.ADD_AUGM,
							callMethod
							) );
				} 
				// otherwise just synGuards()
				else {
					loop.addExpression(callMethod);
				}
				// sum sizes of all transitions
				loop.addExpression(createBinaryExpression(kernelGetLocalVar(LocalVar.SYNCHRONIZED_SIZE).getSource(),
						Operator.ADD_AUGM, varSynchronizedStates[i].accessField("size").getSource()));
			}
		}
		
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
		if (cmdGenerator.isActive() && hasSynchronized) {
			mainMethodCallBothUpdates(loop);
		}
		/**
		 * only synchronized updates
		 */
		else if (hasSynchronized) {
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
	 * b) call the non-synch update method, which is different in CTMC and DTMD,
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
		mainMethodCallSynUpdate(ifElse, selection, synSum, sum);
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
		mainMethodCallSynUpdate(parent, selection, synSum, varSynSelectionSize.getSource());
	}

	/**
	 * Performs selection between synchronized commands and adds call to selected synchronized update.
	 * @param parent
	 * @param selection
	 * @param synSum
	 * @param sum
	 */
	protected void mainMethodCallSynUpdate(ComplexKernelComponent parent, CLVariable selection, CLVariable synSum, Expression sum)
	{
		CLVariable varStateVector = localVars.get(LocalVar.STATE_VECTOR);

		if (synCommands.length > 1) {
			/**
			 * Loop counter, over all labels
			 */
			CLVariable counter = new CLVariable(new StdVariableType(0, synCommands.length), "synSelection");
			counter.setInitValue(StdVariableType.initialize(0));
			parent.addExpression(counter.getDefinition());
			//loop over synchronized commands
			ForLoop loop = new ForLoop(counter, 0, synCommands.length);
			Switch _switch = new Switch(counter);

			for (int i = 0; i < synCommands.length; ++i) {
				CLVariable currentSize = varSynchronizedStates[i].accessField("size");
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
				CLVariable currentSize = varSynchronizedStates[i].accessField("size");
				_switch.addExpression(i, createBinaryExpression(synSum.getSource(), Operator.SUB_AUGM,
				// synSum -= synchState__label.size;
						currentSize.getSource()));
				/**
				 * Recompute probability/rate
				 */
				mainMethodSynRecomputeSelection(_switch, selection, synSum, sum, currentSize);
			}
			checkSelection.addExpression(_switch);
			checkSelection.addExpression("break;\n");
			loop.addExpression(checkSelection);
			parent.addExpression(loop);

			/**
			 * Counter shows selected label, so we can call the update.
			 */
			_switch = new Switch(counter);
			for (int i = 0; i < synCommands.length; ++i) {
				_switch.addCase(fromString(i));

				/**
				 * Before a synchronized update, compute transition rewards.
				 */
				_switch.addExpression(i, rewardGenerator.kernelBeforeUpdate(varStateVector, synCommands[i]));

				Expression call = synchronizedUpdates.get(i).callMethod(
				//&stateVector
						varStateVector.convertToPointer(),
						//&synchState__label
						varSynchronizedStates[i].convertToPointer(),
						//probability
						selection);
				_switch.addExpression(i, loopDetector.kernelCallUpdate(call));
			}
			parent.addExpression(_switch);
		} else {
			CLVariable currentSize = varSynchronizedStates[0].accessField("size");
			/**
			 * Recompute probability/rate
			 */
			mainMethodSynRecomputeSelection(parent, selection, synSum, sum, currentSize);
			Expression call = synchronizedUpdates.get(0).callMethod(
			//&stateVector
					varStateVector.convertToPointer(),
					//&synchState__label
					varSynchronizedStates[0].convertToPointer(),
					//probability
					selection);
			/**
			 * Before a synchronized update, compute transition rewards.
			 */
			parent.addExpression(rewardGenerator.kernelBeforeUpdate(varStateVector, synCommands[0]));
			parent.addExpression( loopDetector.kernelCallUpdate(call) );
		}
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
	protected abstract void mainMethodSynRecomputeSelection(ComplexKernelComponent parent, CLVariable selection, CLVariable synSum, Expression sum,
			CLVariable currentLabelSize);

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
	protected void createSynGuardsMethod()
	{
		if (!hasSynchronized) {
			return;
		}

		synchronizedGuards = new ArrayList<>();
		for (SynchronizedCommand cmd : synCommands) {
			//synchronized state
			CLVariable synState = new CLVariable(new PointerType(synchronizedStates.get(cmd.synchLabel)), "synState");
			int max = cmd.getMaxCommandsNum();
			Method current = guardsSynCreateMethod(String.format("guardCheckSyn__%s", cmd.synchLabel), max);
			CLVariable stateVector = new CLVariable(localVars.get(LocalVar.STATE_VECTOR).getPointer(), "sv");
			//size for whole label
			CLVariable labelSize = guardsSynLabelVar(max);
			labelSize.setInitValue(StdVariableType.initialize(0));
			//size for current module
			CLVariable currentSize = guardsSynCurrentVar(max);
			currentSize.setInitValue(StdVariableType.initialize(0));
			CLVariable saveSize = synState.accessField("moduleSize");
			CLVariable guardsTab = synState.accessField("guards");

			try {
				current.addArg(stateVector);
				current.addArg(synState);
				current.addLocalVar(labelSize);
				current.addLocalVar(currentSize);
				current.addLocalVar( guardsSynLocalVars(
						cmd.getModulesNum(), cmd.getCmdsNum(), cmd.getMaxCommandsNum()
								));
			} catch (KernelException e) {
				throw new RuntimeException(e);
			}

			int guardCounter = 0;
			//first module
			for (int i = 0; i < cmd.getCommandNumber(0); ++i) {
				guardsSynAddGuard(current, guardsTab.accessElement(fromString(guardCounter++)),
				//guardsTab[counter] = evaluate(guard)
						cmd.getCommand(0, i), currentSize);
			}
			current.addExpression(createAssignment(saveSize.accessElement(fromString(0)), currentSize));
			current.addExpression(createAssignment(labelSize, currentSize));
			current.addExpression(guardsSynAfterModule(0));
			//rest
			for (int i = 1; i < cmd.getModulesNum(); ++i) {
				IfElse ifElse = new IfElse(createBinaryExpression(labelSize.getSource(), Operator.NE, fromString(0)));
				ifElse.addExpression(createAssignment(currentSize, fromString(0)));
				ifElse.addExpression(guardsSynBeforeModule(i));
				for (int j = 0; j < cmd.getCommandNumber(i); ++j) {
					guardsSynAddGuard(ifElse, guardsTab.accessElement(fromString(guardCounter++)),
					//guardsTab[counter] = evaluate(guard)
							cmd.getCommand(i, j), currentSize);
				}
				ifElse.addExpression(createBinaryExpression(labelSize.getSource(),
				// cmds_for_label *= cmds_for_module;
						Operator.MUL_AUGM, currentSize.getSource()));
				ifElse.addExpression(createAssignment(saveSize.accessElement(fromString(i)), currentSize));
				ifElse.addExpression(guardsSynAfterModule(i));
				current.addExpression(ifElse);
			}
			saveSize = synState.accessField("size");
			current.addExpression(createAssignment(saveSize, labelSize));
			guardsSynReturn(current);
			synchronizedGuards.add(current);
		}
	}

	/**
	 * @param label
	 * @param maxCommandsNumber
	 * @return method returning float(CTMC) or an integer(DTMC) - label size
	 */
	protected abstract Method guardsSynCreateMethod(String label, int maxCommandsNumber);

	/**
	 * Additional local variables.
	 * @param cmdsCount
	 * @param maxCmdsCount
	 * @return none for DTMC, CTMC may add transition counters
	 */
	protected abstract Collection<CLVariable> guardsSynLocalVars(int moduleCount, int cmdsCount, int maxCmdsCount);
	
	/**
	 * @param maxCommandsNumber
	 * @return helper variable for label size - float/integer
	 */
	protected abstract CLVariable guardsSynLabelVar(int maxCommandsNumber);

	/**
	 * @param maxCommandsNumber
	 * @return helper variable for size of current module - float/integer
	 */
	protected abstract CLVariable guardsSynCurrentVar(int maxCommandsNumber);

	/**
	 * Only for CTMC with loop detection
	 * @param module
	 * @return code injected before starting checking a module
	 */
	protected abstract KernelComponent guardsSynBeforeModule(int module);
	
	/**
	 * Only for CTMC with loop detection
	 * @param module
	 * @return code injected after finishing checking a module
	 */
	protected abstract KernelComponent guardsSynAfterModule(int module);

	/**
	 * Only for CTMC with loop detection
	 * @param method
	 * @return code injected after finishing checking a module
	 */
	protected abstract void guardsSynReturn(Method method);
	
	/**
	 * Mark command index in guardsArray and sum rates/counts (simpler implementation for DTMC - 
	 * just add guard tab value, whether it is 0 or 1; in CTMC, rate is added only in one case).
	 * @param parent
	 * @param guardArray
	 * @param cmd
	 * @param size
	 */
	protected abstract void guardsSynAddGuard(ComplexKernelComponent parent, CLVariable guardArray, Command cmd, CLVariable size);

	/*********************************
	 * SYNCHRONIZED UPDATE
	 ********************************/

	/**
	 * Create helper method - update of the state vector with a synchronized command. 
	 * Main method recomputes probabilities, selects guards and calls additional update function
	 * for each module.
	 */
	protected void createUpdateMethodSyn()
	{
		synchronizedUpdates = new ArrayList<>();
		additionalMethods = new ArrayList<>();
		for (SynchronizedCommand cmd : synCommands) {

			Method current = new Method(String.format("updateSyn__%s", cmd.synchLabel),
					loopDetector.synUpdateFunctionReturnType()
					);
			//state vector
			CLVariable stateVector = new CLVariable(localVars.get(LocalVar.STATE_VECTOR).getPointer(), "sv");
			//synchronized state
			CLVariable synState = new CLVariable(new PointerType(synchronizedStates.get(cmd.synchLabel)), "synState");
			CLVariable propability = new CLVariable(new StdVariableType(StdType.FLOAT), "prop");
			//current guard
			CLVariable guard = new CLVariable(new StdVariableType(0, cmd.getMaxCommandsNum()), "guard");
			guard.setInitValue(StdVariableType.initialize(0));
			//sv->size
			CLVariable saveSize = synState.accessField("size");
			//sv->guards
			CLVariable guardsTab = synState.accessField("guards");
			//size for current module
			CLVariable totalSize = new CLVariable(saveSize.varType, "totalSize");
			totalSize.setInitValue(saveSize);
			
			/**
			 * Obtain variables required to save, create a structure (when necessary),
			 * initialize it with StateVector values.
			 */
			Set<PrismVariable> varsToSave = cmd.variablesCopiedBeforeUpdate();
			CLVariable savedVarsInstance = null;
			StructureType savedVarsType = null;
			if (!varsToSave.isEmpty()) {
				savedVarsType = new StructureType(String.format("SAVE_VARIABLES_SYNCHR_%s", cmd.synchLabel));
				for (PrismVariable var : varsToSave) {
					CLVariable structureVar = new CLVariable(new StdVariableType(var), StateVector.translateSVField(var.name));
					savedVarsType.addVariable(structureVar);
				}
				//add to global declarations
				additionalDeclarations.add(savedVarsType.getDefinition());

				savedVarsInstance = new CLVariable(savedVarsType, "oldSV");
				CLValue[] init = new CLValue[varsToSave.size()];
				int i = 0;
				for (PrismVariable var : varsToSave) {
					init[i++] = stateVector.accessField(StateVector.translateSVField(var.name));
				}
				savedVarsInstance.setInitValue(savedVarsType.initializeStdStructure(init));
			}

			try {
				current.addArg(stateVector);
				current.addArg(synState);
				current.addArg(propability);
				current.addLocalVar(guard);
				current.addLocalVar(totalSize);
				current.addLocalVar( loopDetector.synUpdateFunctionLocalVars() );

				if (savedVarsInstance != null) {
					current.addLocalVar(savedVarsInstance);
				}

				updateSynAdditionalVars(current, cmd);
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
			Method update = updateSynLabelMethod(cmd, savedVarsType);
			additionalMethods.add(update);

			for (int i = 0; i < cmd.getModulesNum(); ++i) {

				moduleSize = synState.accessField("moduleSize").accessElement(fromString(i));
				updateSynBeforeUpdateLabel(current, cmd, i, guardsTab, guard, moduleSize, totalSize, propability);
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
				current.addExpression( loopDetector.synUpdateCallUpdate(callUpdate) );

				updateSynAfterUpdateLabel(current, guard, moduleSize, totalSize, propability);
			}

			loopDetector.synUpdateFunctionReturn(current);
			synchronizedUpdates.add(current);
		}
	}

	/**
	 * Only CTMC uses additional variables for sum (when there are two or more guards in one of modules).
	 * @param parent
	 * @param cmd
	 * @throws KernelException
	 */
	protected abstract void updateSynAdditionalVars(Method parent, SynchronizedCommand cmd) throws KernelException;

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
	protected abstract void updateSynBeforeUpdateLabel(Method parent, SynchronizedCommand cmd, int moduleNumber, CLVariable guardsTab, CLVariable guard,
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
	protected abstract void updateSynAfterUpdateLabel(ComplexKernelComponent parent, CLVariable guard, CLVariable moduleSize, CLVariable totalSize,
			CLVariable probability);

	/**
	 * Method takes as an argument SV, module number, guard selection and probability, performs direct update of stateVector
	 * @param synCmd
	 * @param savedVariables
	 * @return 'direct' update method
	 */
	protected Method updateSynLabelMethod(SynchronizedCommand synCmd, StructureType savedVariables)
	{
		Method current = new Method(String.format("updateSynchronized__%s", synCmd.synchLabel),
				loopDetector.synLabelUpdateFunctionReturnType());
		CLVariable stateVector = new CLVariable(localVars.get(LocalVar.STATE_VECTOR).getPointer(), "sv");
		//guardsTab
		CLVariable guardsTab = new CLVariable(new PointerType(new StdVariableType(StdType.BOOL)), "guards");
		//selected module
		CLVariable module = new CLVariable(new StdVariableType(StdType.UINT8), "module");
		CLVariable guard = new CLVariable(new StdVariableType(StdType.UINT8), "guard");
		CLVariable probabilityPtr = new CLVariable(new PointerType(new StdVariableType(StdType.FLOAT)), "prob");
		// saved values - optional argument
		CLVariable oldSV = null;
		if (savedVariables != null) {
			oldSV = new CLVariable(new PointerType(savedVariables), "oldSV");
		}

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
			current.addLocalVar( loopDetector.synLabelUpdateFunctionLocalVars() );
		} catch (KernelException e) {
			throw new RuntimeException(e);
		}

		Switch _switch = new Switch(module);
		Update update = null;
		Rate rate = null;
		Command cmd = null;
		int moduleOffset = 0;
		CLVariable guardSelection = updateSynLabelMethodGuardSelection(synCmd, guard);
		CLVariable guardCounter = updateSynLabelMethodGuardCounter(synCmd);
		//no variable for DTMC
		if (guardCounter != null) {
			current.addExpression(guardCounter.getDefinition());
		}
		current.addExpression(guardSelection.getDefinition());

		// Create translations variable -> savedStructure.variable
		// Provide alternative access to state vector variable (instead of regular structure)
		// TODO: refactor this by moving to StateVector
		Map<String, CLVariable> savedTranslations = null;
		if (oldSV != null) {
			savedTranslations = new HashMap<>();

			for (CLVariable var : savedVariables.getFields()) {
				String name = var.varName.substring(StateVector.STATE_VECTOR_PREFIX.length());
				CLVariable second = oldSV.accessField(var.varName);
				savedTranslations.put(name, second);
			}
		}
		// variables saved in single update
		Map<String, CLVariable> varsSaved = new HashMap<>();

		//for-each module
		for (int i = 0; i < synCmd.getModulesNum(); ++i) {
			_switch.addCase(fromString(i));
			_switch.setConditionNumber(i);
			updateSynLabelMethodSelectGuard(current, _switch, guardSelection, guardCounter, moduleOffset);

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
					
					internalSwitch.addExpression(j, loopDetector.synLabelUpdateFunctionMultipleActions());
					
					IfElse ifElse = new IfElse(createBinaryExpression(probability.getSource(), Operator.LT,
							fromString(convertPrismRate(svPtrTranslations, savedTranslations, rate))));
					if (!update.isActionTrue(0)) {
						ifElse.addExpression(0, updateSynLabelMethodProbabilityRecompute(probability, null, rate, savedTranslations));

						StateVector.addSavedVariables(stateVector, ifElse, 0, update.getAction(0), savedTranslations, varsSaved);
						// make temporary copy, we may ovewrite some variables
						Map<String, CLVariable> newSavedTranslations;
						if (savedTranslations != null) {
							newSavedTranslations = new HashMap<>(savedTranslations);
						} else {
							newSavedTranslations = new HashMap<>();
						}
						newSavedTranslations.putAll(varsSaved);

						ifElse.addExpression(0, loopDetector.synLabelUpdateFunctionConvertAction(
								stateVector, update.getAction(0), actionsCount, svPtrTranslations, newSavedTranslations
								));
					}
					for (int k = 1; k < update.getActionsNumber(); ++k) {
						Rate previous = new Rate(rate);
						rate.addRate(update.getRate(k));
						ifElse.addElif(createBinaryExpression(probability.getSource(), Operator.LT,
								fromString(convertPrismRate(svPtrTranslations, savedTranslations, rate))));
						ifElse.addExpression(k, updateSynLabelMethodProbabilityRecompute(probability, previous, update.getRate(k), savedTranslations));

						StateVector.addSavedVariables(stateVector, ifElse, k, update.getAction(k), savedTranslations, varsSaved);
						// make temporary copy, we may ovewrite some variables
						Map<String, CLVariable> newSavedTranslations;
						if (savedTranslations != null) {
							newSavedTranslations = new HashMap<>(savedTranslations);
						} else {
							newSavedTranslations = new HashMap<>();
						}
						newSavedTranslations.putAll(varsSaved);

						if (!update.isActionTrue(k)) {
							ifElse.addExpression(k, loopDetector.synLabelUpdateFunctionConvertAction(
									stateVector, update.getAction(k), actionsCount, svPtrTranslations, newSavedTranslations
									));
						}
					}
					internalSwitch.addExpression(j, ifElse);
				} else {
					if (!update.isActionTrue(0)) {

						StateVector.addSavedVariables(stateVector, internalSwitch, j, update.getAction(0), savedTranslations, varsSaved);
						// make temporary copy, we may ovewrite some variables
						Map<String, CLVariable> newSavedTranslations;
						if (savedTranslations != null) {
							newSavedTranslations = new HashMap<>(savedTranslations);
						} else {
							newSavedTranslations = new HashMap<>();
						}
						newSavedTranslations.putAll(varsSaved);
			
						internalSwitch.addExpression(j, loopDetector.synLabelUpdateFunctionConvertAction(
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
		loopDetector.synLabelUpdateFunctionReturn(current);
		return current;
	}

	/**
	 * @param cmd
	 * @param guard
	 * @return DTMC: increased (later) integer for guard selection, decreased for CTMC
	 */
	protected abstract CLVariable updateSynLabelMethodGuardSelection(SynchronizedCommand cmd, CLVariable guard);

	/**
	 * @param cmd
	 * @return an integer for DTMC, none for CTMC
	 */
	protected abstract CLVariable updateSynLabelMethodGuardCounter(SynchronizedCommand cmd);

	/**
	 * CTMC: nothing to do
	 * DTMC: go through the whole guardsTab to find n-th active guard
	 * @param currentMethod
	 * @param parent
	 * @param guardSelection
	 * @param guardCounter
	 * @param moduleOffset
	 */
	protected abstract void updateSynLabelMethodSelectGuard(Method currentMethod, ComplexKernelComponent parent, CLVariable guardSelection,
			CLVariable guardCounter, int moduleOffset);

	/**
	 * @param probability
	 * @param before
	 * @param current
	 * @return expression recomputing probability before going to an action
	 */
	protected Expression updateSynLabelMethodProbabilityRecompute(CLVariable probability, Rate before, Rate current, Map<String, CLVariable> savedVariables)
	{
		Expression compute = null;
		if (before != null) {
			compute = createBinaryExpression(probability.getSource(), Operator.SUB,
			//probability - sum of rates before
					fromString(convertPrismRate(svPtrTranslations, savedVariables, before)));
		} else {
			compute = probability.getSource();
		}
		addParentheses(compute);
		return createAssignment(probability, createBinaryExpression(compute, Operator.DIV,
		//divide by current interval
				fromString(convertPrismRate(svPtrTranslations, savedVariables, current))));
	}

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
	
	/**
	 * @return state vector structure type
	 */
	public StructureType getSVType()
	{
		return stateVectorType;
	}

	public AbstractAutomaton getModel()
	{
		return model;
	}

	/**
	 * Returns a map containing translations in form:
	 * FROM:
	 * PRISM variable name NAME
	 * TO:
	 * - access in a pointer to structure: (*sv).
	 * - field name begins with STATE_VECTOR_PREFIX
	 * - field name ends with NAME
	 * @return
	 */
	public Map<String, String> getSVPtrTranslations()
	{
		return svPtrTranslations;
	}

	/**
	 * TODO: temporary, remove
	 * @return
	 */
	public Map<String, String> getSVTranslations()
	{
		StructureType stateVectorType = getSVType();
		CLVariable stateVector = localVars.get(LocalVar.STATE_VECTOR);
		return StateVector.getSVTranslations(stateVector, stateVectorType);
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
		} else if (cmdGenerator.isActive() && hasSynchronized) {
			return createBinaryExpression(
					varSelectionSize.getSource(),
					Operator.ADD,
					varSynSelectionSize.getSource()
					);
		} else if (hasSynchronized) {
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
	
	public Command[] getCommands()
	{
		return commands;
	}
	
	/**
	 * @return visitor object to apply for PRISM code
	 */
	public ParsTreeModifier getTreeVisitor()
	{
		return treeVisitor;
	}

}
