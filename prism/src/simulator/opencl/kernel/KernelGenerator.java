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
import static simulator.opencl.kernel.expression.ExpressionGenerator.convertPrismAction;
import static simulator.opencl.kernel.expression.ExpressionGenerator.convertPrismGuard;
import static simulator.opencl.kernel.expression.ExpressionGenerator.convertPrismProperty;
import static simulator.opencl.kernel.expression.ExpressionGenerator.convertPrismRate;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createAssignment;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createBinaryExpression;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createNegation;
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import parser.ast.ExpressionLiteral;
import prism.PrismLangException;
import simulator.opencl.RuntimeConfig;
import simulator.opencl.automaton.AbstractAutomaton;
import simulator.opencl.automaton.AbstractAutomaton.StateVector;
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
import simulator.sampler.Sampler;
import simulator.sampler.SamplerBoolean;
import simulator.sampler.SamplerBoundedUntilCont;
import simulator.sampler.SamplerBoundedUntilDisc;
import simulator.sampler.SamplerDouble;
import simulator.sampler.SamplerNext;
import simulator.sampler.SamplerRewardCumulCont;
import simulator.sampler.SamplerRewardCumulDisc;
import simulator.sampler.SamplerRewardInstCont;
import simulator.sampler.SamplerRewardInstDisc;
import simulator.sampler.SamplerRewardReach;
import simulator.sampler.SamplerUntil;

public abstract class KernelGenerator
{
	protected CLVariable varTime = null;
	protected CLVariable varSelectionSize = null;
	protected CLVariable varStateVector = null;
	protected CLVariable varPathLength = null;
	protected CLVariable varLoopDetection = null;
	protected CLVariable varSynSelectionSize = null;
	protected CLVariable varGuardsTab = null;
	protected CLVariable[] varSynchronizedStates = null;
	protected CLVariable varPropertiesArray = null;

	protected enum KernelMethods {
		/**
		 * DTMC:
		 * Return value is number of concurrent transitions.
		 * int checkGuards(StateVector * sv, bool * guardsTab);
		 * CTMC:
		 * Return value is rates sum of transitions in race condition.
		 * float checkGuards(StateVector * sv, bool * guardsTab);
		 */
		CHECK_GUARDS(0),
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
		PERFORM_UPDATE(2),
		/**
		 * DTMC:
		 * void performUpdateSyn(StateVector * sv, int updateSelection,SynCmdState * tab);
		 * CTMC:
		 * void performUpdateSyn(StateVector * sv, float sumSelection,SynCmdState * tab);
		 */
		PERFORM_UPDATE_SYN(3),
		/**
		 * Return value determines is we can stop simulation(we know all values).
		 * DTMC:
		 * bool updateProperties(StateVector * sv,PropertyState * prop);
		 * OR
		 * bool updateProperties(StateVector * sv,PropertyState * prop,int time);
		 * 
		 * CTMC:
		 * bool updateProperties(StateVector * sv,PropertyState * prop);
		 * OR
		 * bool updateProperties(StateVector * sv,PropertyState * prop,float time, float updated_time);
		 */
		UPDATE_PROPERTIES(4);
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
	public final static StructureType PROPERTY_STATE_STRUCTURE;
	static {
		StructureType type = new StructureType("PropertyState");
		type.addVariable(new CLVariable(new StdVariableType(StdType.BOOL), "propertyState"));
		type.addVariable(new CLVariable(new StdVariableType(StdType.BOOL), "valueKnown"));
		PROPERTY_STATE_STRUCTURE = type;
	}

	/**
	 * Types of rewards.
	 */
	protected enum RewardTypes {
		REACHABILITY,
		CUMULATIVE,
		INSTANTANEOUS
	}
	/**
	 * Reward required to save in structure.
	 */
	protected final EnumMap<RewardTypes, String[]> REWARD_REQUIRED_VARIABLES;
	
	
	/**
	 * StateVector field prefix.
	 */
	protected final static String STATE_VECTOR_PREFIX = "__STATE_VECTOR_";

	/**
	 * Prefix of variable used to save value of StateVector field.
	 */
	protected final static String SAVED_VARIABLE_PREFIX = "SAVED_VARIABLE__";

	/**
	 * Name of structure field corresponding to total cumulative reward.
	 */
	protected final static String REWARD_STRUCTURE_VAR_CUMULATIVE_TOTAL = "cumulativeTotalReward";
	
	/**
	 * Name of structure field corresponding to current state reward.
	 */
	protected final static String REWARD_STRUCTURE_VAR_CURRENT_STATE = "currentStateReward";
	
	/**
	 * Name of structure field corresponding to previous state reward.
	 */
	protected final static String REWARD_STRUCTURE_VAR_PREVIOUS_STATE = "prevStateReward";
	
	/**
	 * Name of structure field corresponding to previous transitions reward (previous update).
	 */
	protected final static String REWARD_STRUCTURE_VAR_PREVIOUS_TRANSITION = "prevStateReward";
	
	protected Map<String, StructureType> synchronizedStates = new LinkedHashMap<>();
	protected StructureType synCmdState = null;
	protected AbstractAutomaton model = null;
	protected RuntimeConfig config = null;
	protected Command commands[] = null;
	protected SynchronizedCommand synCommands[] = null;
	
	/**
	 * Samplers for PCTL/CSL properties.
	 */
	protected List<SamplerBoolean> properties = null;
	
	/**
	 * Samplers for rewards.
	 */
	protected List<SamplerDouble> rewardProperties = null;
	
	/**
	 * Structures for rewards, indexed by PRISM rewardStructureIndex.
	 */
	protected Map<Integer, StructureType> rewardStructures = null;
	
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
	 * Pseudo-random number generator type.
	 */
	protected PRNGType prngType = null;
	
	/**
	 * True when model contains synchronized commands.
	 */
	protected boolean hasSynchronized = false;
	
	/**
	 * True when model contains 'normal' commands.
	 */
	protected boolean hasNonSynchronized = false;
	
	/**
	 * True when one of processed properties has timing constraints.
	 */
	protected boolean timingProperty = false;

	/**
	 * TreeVisitor instance, used for parsing of properties (model parsing has been already done in Automaton class)
	 */
	protected ParsTreeModifier treeVisitor = new ParsTreeModifier();

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
	 * @param config
	 */
	public KernelGenerator(AbstractAutomaton model, List<Sampler> properties, RuntimeConfig config) throws KernelException
	{
		this.model = model;
		this.config = config;
		this.prngType = config.prngType;
		REWARD_REQUIRED_VARIABLES = initializeRewardRequiredVars();
		
		
		// Separate properties
		for(Sampler property : properties) {
			if( property instanceof SamplerBoolean ) {
				if( this.properties == null ) {
					this.properties = new ArrayList<>();
				}
				this.properties.add((SamplerBoolean) property);
			} else {
				if( this.rewardProperties == null ) {
					this.rewardProperties = new ArrayList<>();
					this.rewardStructures = new TreeMap<>();
				}
				this.rewardProperties.add((SamplerDouble) property);
			}
		}
		
		importStateVector();
		createRewardStructures();
		int synSize = model.synchCmdsNumber();
		int size = model.commandsNumber();
		
		// import commands
		if (synSize != 0) {
			synCommands = new SynchronizedCommand[synSize];
			hasSynchronized = true;
		}
		if (size - synSize != 0) {
			commands = new Command[size - synSize];
			hasNonSynchronized = true;
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
		
		// check if at least one of properties has time constraint
		for (Sampler sampler : properties) {
			if (sampler instanceof SamplerBoundedUntilCont || sampler instanceof SamplerBoundedUntilDisc) {
				timingProperty = true;
				break;
			}
		}

		// create translations from model variable to StateVector structure, accessed by a pointer
		CLVariable sv = new CLVariable(new PointerType(stateVectorType), "sv");
		for (CLVariable var : stateVectorType.getFields()) {
			String name = var.varName.substring(STATE_VECTOR_PREFIX.length());
			CLVariable second = sv.accessField(var.varName);
			svPtrTranslations.put(name, second.varName);
		}

		// property and synchronized structure definitions
		if (hasSynchronized) {
			createSynchronizedStructures();
		}
		additionalDeclarations.add(PROPERTY_STATE_STRUCTURE.getDefinition());
		for(Map.Entry<Integer, StructureType> rewardStruct : rewardStructures.entrySet()) {
			additionalDeclarations.add( rewardStruct.getValue().getDefinition() );
		}
		
		// PRNG definitions
		if (prngType.getAdditionalDefinitions() != null) {
			additionalDeclarations.addAll(prngType.getAdditionalDefinitions());
		}
	}

	/**
	 * Create structures for synchronized commands.
	 * Generated structure types are different for DTMC and CTMC.
	 */
	protected abstract void createSynchronizedStructures();

	/**
	 * @return state vector structure type
	 */
	public StructureType getSVType()
	{
		return stateVectorType;
	}

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
		return ret;
	}

	/**
	 * Create StateVector structure type from model's state vector.
	 */
	protected void importStateVector()
	{
		StateVector sv = model.getStateVector();
		stateVectorType = new StructureType("StateVector");
		PrismVariable[] vars = sv.getVars();
		for (int i = 0; i < vars.length; ++i) {
			CLVariable var = new CLVariable(new StdVariableType(vars[i]), translateSVField(vars[i].name));
			stateVectorType.addVariable(var);
		}
		additionalDeclarations.add(stateVectorType.getDefinition());
	}

	/**
	 * @return type of variable containing rewards
	 */
	protected StdVariableType rewardVarsType()
	{
		if( this.config.rewardVariableType == RuntimeConfig.RewardVariableType.FLOAT ) {
			return new StdVariableType(StdType.FLOAT);
		} else {
			return new StdVariableType(StdType.DOUBLE);
		}
	}
	
	/**
	 * Import all reward samplers and create C structures to contain required information in kernel launch.
	 * 
	 * For every reward structure:
	 * - reward reachability - total cumulative reward
	 * - instanteous reward DTMC - current state reward
	 * - instanteous reward CTMC - current and previous state reward
	 * - cumulative DTMC - total cumulative reward
	 * - cumulative CTMC - total cumulative reward, previous state & transition reward, current state reward
	 */
	protected EnumMap<RewardTypes,String[]> initializeRewardRequiredVars() throws KernelException
	{	
		EnumMap<RewardTypes,String[]> map = new EnumMap<>( RewardTypes.class );
		
		// reachability - common for both CTMC and DTMC
		map.put(RewardTypes.REACHABILITY, new String[]{ REWARD_STRUCTURE_VAR_CUMULATIVE_TOTAL} );
		initializeRewardRequiredVarsCumulative(map);
		initializeRewardRequiredVarsInstantaneous(map);
		
		return map;
	}
	
	/**
	 * DTMC:
	 * - total cumulative reward (state + transition)
	 * 
	 * CTMC:
	 * - total cumulative reward (state + transition)
	 * - previous state and transition reward
	 * - current state reward
	 * @param property
	 */
	protected abstract void initializeRewardRequiredVarsCumulative(EnumMap<RewardTypes,String[]> map);
	
	/**
	 * DTMC:
	 * - current state reward
	 * 
	 * CTMC:
	 * - current and previous state reward
	 * @param property
	 */
	protected abstract void initializeRewardRequiredVarsInstantaneous(EnumMap<RewardTypes,String[]> map);
	
	/**
	 * Initialize state vector from initial state declared in model or provided by user.  
	 * @return structure initialization value
	 */
	protected CLValue initStateVector()
	{
		StateVector sv = model.getStateVector();
		Integer[] init = new Integer[sv.size()];
		if (config.initialState == null) {
			PrismVariable[] vars = sv.getVars();
			for (int i = 0; i < vars.length; ++i) {
				init[i] = vars[i].initValue;
			}
		} else {
			Object[] initVars = config.initialState.varValues;
			for (int i = 0; i < initVars.length; ++i) {
				if (initVars[i] instanceof Integer) {
					init[i] = (Integer) initVars[i];
				} else {
					init[i] = new Integer(((Boolean) initVars[i]) ? 1 : 0);
				}
			}
		}
		return stateVectorType.initializeStdStructure(init);
	}
	
	protected void createRewardStructures() throws KernelException
	{
		if( rewardProperties == null) {
			return;
		}

		int index = -1;
		boolean flags[] = null;
		String vars[] = null;
		
		for(SamplerDouble rewardProperty : rewardProperties) {
			if( rewardProperty instanceof SamplerRewardReach ) {
				
				index = ((SamplerRewardReach) rewardProperty).getRewardIndex();
				vars = REWARD_REQUIRED_VARIABLES.get( RewardTypes.REACHABILITY );
				
			} else if( rewardProperty instanceof SamplerRewardCumulCont) {
				
				index = ((SamplerRewardCumulCont) rewardProperty).getRewardIndex();
				vars = REWARD_REQUIRED_VARIABLES.get( RewardTypes.CUMULATIVE );
				
			} else if( rewardProperty instanceof SamplerRewardCumulDisc) {

				index = ((SamplerRewardCumulDisc) rewardProperty).getRewardIndex();
				vars = REWARD_REQUIRED_VARIABLES.get( RewardTypes.CUMULATIVE );
				
			} else if( rewardProperty instanceof SamplerRewardInstCont) {
				
				index = ((SamplerRewardInstCont) rewardProperty).getRewardIndex();
				vars = REWARD_REQUIRED_VARIABLES.get( RewardTypes.INSTANTANEOUS );
				
			} else if( rewardProperty instanceof SamplerRewardInstDisc) {

				index = ((SamplerRewardInstDisc) rewardProperty).getRewardIndex();
				vars = REWARD_REQUIRED_VARIABLES.get( RewardTypes.INSTANTANEOUS );
				
			} else {
				throw new KernelException("Unknown type of reward property: " + rewardProperty.getClass().getName() + 
						" for property: " + rewardProperty.toString());
			}
			
			flags = new boolean[ vars.length ];
			Arrays.fill(flags, true);
			
			StructureType type = rewardStructures.get(index);
			
			if( type != null ) {
				for(int i = 0; i < vars.length; ++i) {
					flags[i] = type.containsField(vars[i]);
				}
			} else {
				type = new StructureType( String.format("REWARD_STRUCTURE_%d", index));
				rewardStructures.put(index, type);
			}
			
			for(int i = 0; i < vars.length; ++i) {
				if( flags[i] ) {
					type.addVariable( new CLVariable(rewardVarsType(), vars[i]));
				}
			}
		}
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
		CLVariable[] propertyResults = null;
		CLVariable[] rewardResults = null;
		if ( properties != null) {
			propertyResults = new CLVariable[properties.size()];
			for (int i = 0; i < propertyResults.length; ++i) {
				propertyResults[i] = new CLVariable(new PointerType(new StdVariableType(StdType.UINT8)),
				//propertyNumber
						String.format("property%d", i));
				propertyResults[i].memLocation = Location.GLOBAL;
				currentMethod.addArg(propertyResults[i]);
			}
		}
		
		if ( rewardProperties != null) {
			rewardResults = new CLVariable[rewardProperties.size()];
			for (int i = 0; i < rewardResults.length; ++i) {
				rewardResults[i] = new CLVariable(new PointerType( rewardVarsType() ),
				//propertyNumber
						String.format("rewardOutput_%d", i));
				rewardResults[i].memLocation = Location.GLOBAL;
				currentMethod.addArg(rewardResults[i]);
			}
		}
		
		/**
		 * Local variables.
		 */
		
		//global ID of thread
		CLVariable globalID = new CLVariable(new StdVariableType(StdType.UINT32), "globalID");
		globalID.setInitValue(ExpressionGenerator.assignGlobalID());
		currentMethod.addLocalVar(globalID);
		
		//state vector for model
		varStateVector = new CLVariable(stateVectorType, "stateVector");
		varStateVector.setInitValue(initStateVector());
		currentMethod.addLocalVar(varStateVector);

		//property results
		ArrayType propertiesArrayType = null;
		if ( properties != null ) {
			propertiesArrayType = new ArrayType(PROPERTY_STATE_STRUCTURE, properties.size());
			varPropertiesArray = new CLVariable(propertiesArrayType, "properties");
			currentMethod.addLocalVar(varPropertiesArray);
			CLValue initValues[] = new CLValue[properties.size()];
			CLValue initValue = PROPERTY_STATE_STRUCTURE.initializeStdStructure(new Number[] { 0, 0 });
			for (int i = 0; i < initValues.length; ++i) {
				initValues[i] = initValue;
			}
			varPropertiesArray.setInitValue(propertiesArrayType.initializeArray(initValues));
		}
		//reward results
		List<CLVariable> rewardVariables = null;
		if( rewardProperties != null ) {
			rewardVariables = new ArrayList<>();

			for (Map.Entry<Integer, StructureType> reward : rewardStructures.entrySet()) {
				CLVariable var = new CLVariable(reward.getValue(), String.format("reward_%d", reward.getKey()) );
				Float initValues[] = new Float[ reward.getValue().getNumberOfFields() ];
				Arrays.fill(initValues, 0.0f);
				CLValue initValue = reward.getValue().initializeStdStructure( initValues );
				var.setInitValue(initValue);
				rewardVariables.add( var );
				currentMethod.addLocalVar( var );
			}
		}
		
		//non-synchronized guard tab
		if (hasNonSynchronized) {
			varGuardsTab = new CLVariable(new ArrayType(new StdVariableType(0, commands.length), commands.length), "guardsTab");
			currentMethod.addLocalVar(varGuardsTab);
		}
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
		varLoopDetection = new CLVariable(new StdVariableType(StdType.BOOL), "loopDetection");
		varLoopDetection.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(varLoopDetection);
		//additional local variables, mainly selectionSize. depends on DTMC/CTMC
		mainMethodDefineLocalVars(currentMethod);

		/**
		 * Create helpers method.
		 */
		if (hasNonSynchronized) {
			helperMethods.put(KernelMethods.CHECK_GUARDS, createNonsynGuardsMethod());
			helperMethods.put(KernelMethods.PERFORM_UPDATE, createNonsynUpdate());
		}
		if (hasSynchronized) {
			createSynGuardsMethod();
			createUpdateMethodSyn();
		}
		
		if( properties != null) {
			helperMethods.put(KernelMethods.UPDATE_PROPERTIES, createPropertiesMethod());
		}
		
		/**
		 * Reject samples with globalID greater than numberOfSimulations
		 * Necessary in every kernel, because number of OpenCL kernel launches will be aligned
		 * (and almost always greater than number of ordered samples, buffer sizes etc).
		 */
		IfElse sampleNumberCheck = new IfElse( createBinaryExpression(globalID.getName(), Operator.GT, numberOfSimulations.getName()) );
		sampleNumberCheck.addExpression("return;");
		currentMethod.addExpression(sampleNumberCheck);
	
		/**
		 * initialize generator
		 */
		currentMethod.addExpression(prngType.initializeGenerator());
		
		/**
		 * Initial check of properties, before making any computations.
		 */
		mainMethodFirstUpdateProperties(currentMethod);
	
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
		if (hasNonSynchronized) {
			Expression callCheckGuards = helperMethods.get(KernelMethods.CHECK_GUARDS).callMethod(
			//(stateVector,guardsTab)
					varStateVector.convertToPointer(), varGuardsTab);
			loop.addExpression(createAssignment(varSelectionSize, callCheckGuards));
		}
		if (hasSynchronized) {
			loop.addExpression(createAssignment(varSynSelectionSize, fromString(0)));
			for (int i = 0; i < synCommands.length; ++i) {
				Expression callMethod = synchronizedGuards.get(i).callMethod(
				//&stateVector
						varStateVector.convertToPointer(),
						//synchState
						varSynchronizedStates[i].convertToPointer());
				loop.addExpression(createBinaryExpression(varSynSelectionSize.getSource(), Operator.ADD_AUGM, callMethod));
			}
		}
		
		/**
		 * update time -> in case of CTMC and bounded until we need two time values:
		 * 1) entering state
		 * 2) leaving state
		 * so current time is updated in After method()
		 * other cases: compute time in Before method() 
		 */
		mainMethodUpdateTimeBefore(currentMethod, loop);
		
		/**
		 * if all properties are known, then we can end iterating
		 */
		if( properties != null ) {
			mainMethodUpdateProperties(loop);
		}

		/**
		 * if(selectionSize + synSelectionSize == 0) -> deadlock, break
		 */
		Expression sum = null;
		if (varSynSelectionSize != null && varSelectionSize != null) {
			sum = createBinaryExpression(varSelectionSize.getSource(), Operator.ADD, varSynSelectionSize.getSource());
		} else if (varSynSelectionSize != null) {
			sum = varSynSelectionSize.getSource();
		} else {
			sum = varSelectionSize.getSource();
		}
		/**
		 * Deadlock when number of possible choices is 0.
		 */
		IfElse deadlockState = new IfElse(createBinaryExpression(sum, Operator.EQ, fromString(0)));
		deadlockState.addExpression(new Expression("break;\n"));
		loop.addExpression(deadlockState);
		
		/**
		 * call update method; 
		 * most complex case - both nonsyn and synchronized updates
		 */
		if (hasNonSynchronized && hasSynchronized) {
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
		 * For CTMC&bounded until -> update current time.
		 */
		mainMethodUpdateTimeAfter(currentMethod, loop);
		
		/**
		 * Loop detection procedure - end computations in case of a loop.
		 */
		mainMethodLoopDetection(loop);
		currentMethod.addExpression(loop);
		
		/**
		 * Write results.
		 */
		//sampleNumber + globalID
		Expression position = createBinaryExpression(globalID.getSource(), Operator.ADD, pathOffset.getSource());
		//path length
		CLVariable pathLength = pathLengths.accessElement(position);
		currentMethod.addExpression(createAssignment(pathLength, varPathLength));
		position = createBinaryExpression(globalID.getSource(), Operator.ADD, resultsOffset.getSource());
		//each property result
		if( properties != null ) {
			for (int i = 0; i < properties.size(); ++i) {
				CLVariable result = propertyResults[i].accessElement(position);
				CLVariable property = varPropertiesArray.accessElement(fromString(i)).accessField("propertyState");
				currentMethod.addExpression(createAssignment(result, property));
			}
		}
		
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
	 * Create the call expression for both updates: non-synchronized and synchronized.
	 * @param parent
	 */
	protected void mainMethodCallBothUpdates(ComplexKernelComponent parent)
	{
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
				Expression call = synchronizedUpdates.get(i).callMethod(
				//&stateVector
						varStateVector.convertToPointer(),
						//&synchState__label
						varSynchronizedStates[i].convertToPointer(),
						//probability
						selection);
				_switch.addExpression(i, timingProperty ? call : createAssignment(varLoopDetection, call));
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
			parent.addExpression(timingProperty ? call : createAssignment(varLoopDetection, call));
		}
	}

	/**
	 * Create conditional which stops computation when there was no change in values and there was only on update
	 * (so in the next iteration there will be only one update, which doesn't change anything etc)
	 * @param parent
	 */
	protected void mainMethodLoopDetection(ComplexKernelComponent parent)
	{
		//TODO: loop detection right now implemented only for non-timed properties
		if (!timingProperty) {
			// no change?
			Expression updateFlag = createBinaryExpression(varLoopDetection.getSource(), Operator.EQ, fromString("true"));

			// get update size from update call
			Expression updateSize = null;
			if (hasNonSynchronized && hasSynchronized) {
				updateSize = createBinaryExpression(varSelectionSize.getSource(), Operator.ADD, varSynSelectionSize.getSource());
			} else if (hasNonSynchronized) {
				updateSize = varSelectionSize.getSource();
			} else {
				updateSize = varSynSelectionSize.getSource();
			}

			// update size == 1
			updateSize = createBinaryExpression(updateSize, Operator.EQ, fromString("1"));
			IfElse loop = new IfElse(createBinaryExpression(updateFlag, Operator.LAND, updateSize));
			loop.setConditionNumber(0);
			if( properties != null ) {
				mainMethodUpdateProperties(loop);
			}
			loop.addExpression(new Expression("break;\n"));
			parent.addExpression(loop);
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
	 */
	protected abstract IfElse  mainMethodBothUpdatesCondition(CLVariable selection);

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
	 */
	protected abstract void mainMethodCallNonsynUpdate(ComplexKernelComponent parent);

	/**
	 * First property check, before even entering the loop - necessary only for CTMC.
	 * @param parent
	 */
	protected abstract void mainMethodFirstUpdateProperties(ComplexKernelComponent parent);

	/**
	 * Create call to property update method.
	 * @param currentMethod
	 */
	protected abstract void mainMethodUpdateProperties(ComplexKernelComponent currentMethod);

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
	 * NON-SYNCHRONIZED GUARDS CHECK
	 ********************************/
	/**
	 * Create method for guards verification in non-synchronized updates.
	 * Method will just go through all guards and write numbers of successfully evaluated guards
	 * at consecutive positions at guardsTab. 
	 * @return number of active guards (DTMC) / rate sum (CTMC)
	 * @throws KernelException
	 */
	protected Method createNonsynGuardsMethod() throws KernelException
	{
		if (!hasNonSynchronized) {
			return null;
		}
		
		Method currentMethod = guardsMethodCreateSignature();
		//StateVector * sv
		CLVariable sv = new CLVariable(varStateVector.getPointer(), "sv");
		currentMethod.addArg(sv);
		//bool * guardsTab
		CLVariable guards = new CLVariable(varGuardsTab.getPointer(), "guardsTab");
		currentMethod.addArg(guards);
		//counter
		CLVariable counter = new CLVariable(new StdVariableType(0, commands.length), "counter");
		counter.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(counter);
		guardsMethodCreateLocalVars(currentMethod);

		for (int i = 0; i < commands.length; ++i) {
			guardsMethodCreateCondition(currentMethod, i, convertPrismGuard(svPtrTranslations, commands[i].getGuard().toString()));
		}
		
		//TODO: disable writing last guard, should not change anything
		//signature last guard
		//CLVariable position = guards.varType.accessElement(guards, new Expression(counter.varName));
		//IfElse ifElse = new IfElse(createBasicExpression(counter.getSource(), Operator.NE, fromString(commands.length)));
		//ifElse.addExpression(0, createAssignment(position, fromString(commands.length)));
		//currentMethod.addExpression(ifElse);
		
		guardsMethodReturnValue(currentMethod);
		return currentMethod;
	}

	/**
	 * @return method returning integer for DTMC, float for CTMC
	 */
	protected abstract Method guardsMethodCreateSignature();

	/**
	 * Additional float for rate sum at CTMC, none at DTMC (both use an integer for array counting)
	 * @param currentMethod
	 * @throws KernelException
	 */
	protected abstract void guardsMethodCreateLocalVars(Method currentMethod) throws KernelException;

	/**
	 * For both automata evaluate guard, for CTMC additionally add rate to returned sum.
	 * @param currentMethod
	 * @param position
	 * @param guard
	 */
	protected abstract void guardsMethodCreateCondition(Method currentMethod, int position, String guard);

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
	protected Method createNonsynUpdate() throws KernelException
	{
		if (!hasNonSynchronized) {
			return null;
		}
		
		Method currentMethod = new Method("updateNonsynGuards", new StdVariableType(timingProperty ? StdType.VOID : StdType.BOOL));
		//StateVector * sv
		CLVariable sv = new CLVariable(varStateVector.getPointer(), "sv");
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
		//changeFlag
		CLVariable changeFlag = new CLVariable(new StdVariableType(StdType.BOOL), "changeFlag");
		changeFlag.setInitValue(StdVariableType.initialize(1));
		currentMethod.addLocalVar(changeFlag);
		//oldValue - used for loop detection
		CLVariable oldValue = new CLVariable(new StdVariableType(StdType.INT32), "oldValue");
		oldValue.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(oldValue);

		/**
		 * Performs tasks depending on automata type
		 */
		updateMethodAdditionalArgs(currentMethod);
		updateMethodLocalVars(currentMethod);
		updateMethodPerformSelection(currentMethod);
		
		CLVariable guardsTabSelection = varGuardsTab.accessElement(selection.getSource());
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
			if (update.getActionsNumber() > 1) {
				IfElse ifElse = new IfElse(createBinaryExpression(selectionSum.getSource(), Operator.LT, fromString(convertPrismRate(svPtrTranslations, rate))));
				//first one goes to 'if'
				if (!update.isActionTrue(0)) {
					action = update.getAction(0);
					addSavedVariables(sv, ifElse, 0, action, null, savedVariables);
					if (!timingProperty) {
						ifElse.addExpression(0, convertPrismAction(sv, action, svPtrTranslations, savedVariables, changeFlag, oldValue));
					} else {
						ifElse.addExpression(0, convertPrismAction(sv, action, svPtrTranslations, savedVariables));
					}
				}
				// next actions go to 'else if'
				for (int j = 1; j < update.getActionsNumber(); ++j) {
					// else if (selection <= sum)
					rate.addRate(update.getRate(j));
					ifElse.addElif(createBinaryExpression(selectionSum.getSource(), Operator.LT, fromString(convertPrismRate(svPtrTranslations, rate))));
					
					if (!update.isActionTrue(j)) {
						action = update.getAction(j);
						addSavedVariables(sv, ifElse, 0, action, null, savedVariables);
						if (!timingProperty) {
							ifElse.addExpression(j, convertPrismAction(sv, action, svPtrTranslations, savedVariables, changeFlag, oldValue));
						} else {
							ifElse.addExpression(j, convertPrismAction(sv, action, svPtrTranslations, savedVariables));
						}
					}
				}
				_switch.addCase(new Expression(Integer.toString(i)));
				_switch.addExpression(switchCounter++, ifElse);
			} else {
				// only one action, directly add the code to switch
				if (!update.isActionTrue(0)) {
					_switch.addCase(new Expression(Integer.toString(i)));
					action = update.getAction(0);
					addSavedVariables(sv, _switch, switchCounter, action, null, savedVariables);
					if (!timingProperty) {
						_switch.addExpression(switchCounter++, convertPrismAction(sv, action, svPtrTranslations, savedVariables, changeFlag, oldValue));
					} else {
						_switch.addExpression(switchCounter++, convertPrismAction(sv, action, svPtrTranslations, savedVariables));
					}
				}
			}
		}
		currentMethod.addExpression(_switch);
		
		// return change flag, indicating if the performed update changed the state vector
		if (!timingProperty) {
			currentMethod.addReturn(changeFlag);
		}
		
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

	/*********************************
	 * PROPERTY METHODS
	 ********************************/
	
	/**
	 * @return helper method checking all properties and returning true when all of them are verified
	 * @throws KernelException
	 * @throws PrismLangException 
	 */
	protected Method createPropertiesMethod() throws KernelException, PrismLangException
	{
		Method currentMethod = new Method("checkProperties", new StdVariableType(StdType.BOOL));
		/**
		 * Local variables and args.
		 */
		//StateVector * sv
		CLVariable sv = new CLVariable(varStateVector.getPointer(), "sv");
		currentMethod.addArg(sv);
		//PropertyState * property
		CLVariable propertyState = new CLVariable(new PointerType(PROPERTY_STATE_STRUCTURE), "propertyState");
		currentMethod.addArg(propertyState);
		propertiesMethodTimeArg(currentMethod);
		//uint counter
		CLVariable counter = new CLVariable(new StdVariableType(0, properties.size()), "counter");
		counter.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(counter);
		//bool allKnown - will be returned 
		CLVariable allKnown = new CLVariable(new StdVariableType(StdType.BOOL), "allKnown");
		allKnown.setInitValue(StdVariableType.initialize(1));
		currentMethod.addLocalVar(allKnown);

		/**
		 * For each property, add checking
		 */
		for (int i = 0; i < properties.size(); ++i) {
			Sampler property = properties.get(i);
			CLVariable currentProperty = propertyState.accessElement(counter.getSource());
			CLVariable valueKnown = currentProperty.accessField("valueKnown");

			IfElse ifElse = new IfElse(createNegation(valueKnown.getSource()));
			ifElse.addExpression(0, createAssignment(allKnown, fromString("false")));
			/**
			 * X state_formulae
			 * I don't think that this will be used in future.
			 */
			if (property instanceof SamplerNext) {
				propertiesMethodAddNext(ifElse, (SamplerNext) property, currentProperty);
			}
			/**
			 * state_formulae U state_formulae
			 */
			else if (property instanceof SamplerUntil) {
				propertiesMethodAddUntil(ifElse, (SamplerUntil) property, currentProperty);
			}
			/**
			 * state_formulae U[k1,k2] state_formulae
			 * Requires additional timing args.
			 */
			else {
				propertiesMethodAddBoundedUntil(currentMethod, ifElse, (SamplerBoolean) property, currentProperty);
			}
			ifElse.addExpression(0, createAssignment(allKnown, valueKnown));
			currentMethod.addExpression(ifElse);
		}
		currentMethod.addReturn(allKnown);
		return currentMethod;
	}

	/**
	 * Time argument, used when one have to verify timed property.
	 * DTMC: only current time (integer)
	 * CTMC: two floats - time and updated time
	 * @param currentMethod
	 * @throws KernelException
	 */
	protected abstract void propertiesMethodTimeArg(Method currentMethod) throws KernelException;

	/**
	 * Handle the timed 'until' operator - different implementations for automata.
	 * CTMC requires an additional check for the situation, when current time is between lower
	 * and upper bound.
	 * @param currentMethod
	 * @param parent
	 * @param property
	 * @param propertyVar
	 * @throws PrismLangException
	 */
	protected abstract void propertiesMethodAddBoundedUntil(Method currentMethod, ComplexKernelComponent parent, SamplerBoolean property, CLVariable propertyVar)
			throws PrismLangException;

	/**
	 * @param prop
	 * @return translate property: add parentheses, cast to float in division etc
	 * @throws PrismLangException
	 */
	protected parser.ast.Expression visitPropertyExpression(parser.ast.Expression prop) throws PrismLangException
	{
		return (parser.ast.Expression) prop.accept(treeVisitor);
	}

	/**
	 * Handle the 'next' operator - same for both CTMC/DTMC
	 * @param parent
	 * @param property
	 * @param propertyVar
	 * @throws PrismLangException
	 */
	protected void propertiesMethodAddNext(ComplexKernelComponent parent, SamplerNext property, CLVariable propertyVar) throws PrismLangException
	{
		String propertyString = visitPropertyExpression(property.getExpression()).toString();
		IfElse ifElse = createPropertyCondition(propertyVar, false, propertyString, true);
		createPropertyCondition(ifElse, propertyVar, false, null, false);
		parent.addExpression(ifElse);
	}

	/**
	 * Handle the 'until' non-timed operator - same for both DTMC and CTMC. 
	 * @param parent
	 * @param property
	 * @param propertyVar
	 * @throws PrismLangException
	 */
	protected void propertiesMethodAddUntil(ComplexKernelComponent parent, SamplerUntil property, CLVariable propertyVar) throws PrismLangException
	{
		String propertyStringRight = visitPropertyExpression(property.getRightSide()).toString();
		String propertyStringLeft = visitPropertyExpression(property.getLeftSide()).toString();
		IfElse ifElse = createPropertyCondition(propertyVar, false, propertyStringRight, true);
		
		/**
		 * in F/G it is true, no need to check
		 */
		if (!(property.getLeftSide() instanceof ExpressionLiteral)) {
			createPropertyCondition(ifElse, propertyVar, true, propertyStringLeft, false);
		}
		parent.addExpression(ifElse);
	}

	/**
	 * Creates IfElse for property.
	 * @param propertyVar
	 * @param negation
	 * @param condition
	 * @param propertyValue
	 * @return property verification in conditional - write results to property structure
	 */
	protected IfElse createPropertyCondition(CLVariable propertyVar, boolean negation, String condition, boolean propertyValue)
	{
		IfElse ifElse = null;
		if (!negation) {
			ifElse = new IfElse(convertPrismProperty(svPtrTranslations, condition));
		} else {
			ifElse = new IfElse(createNegation(convertPrismProperty(svPtrTranslations, condition)));
		}
		CLVariable valueKnown = propertyVar.accessField("valueKnown");
		CLVariable propertyState = propertyVar.accessField("propertyState");
		if (propertyValue) {
			ifElse.addExpression(0, createAssignment(propertyState, fromString("true")));
		} else {
			ifElse.addExpression(0, createAssignment(propertyState, fromString("false")));
		}
		ifElse.addExpression(0, createAssignment(valueKnown, fromString("true")));
		return ifElse;
	}

	/**
	 * Private helper method - update ifElse
	 * @param ifElse
	 * @param propertyVar
	 * @param negation
	 * @param condition
	 * @param propertyValue
	 */
	protected void createPropertyCondition(IfElse ifElse, CLVariable propertyVar, boolean negation, String condition, boolean propertyValue)
	{
		if (condition != null) {
			if (!negation) {
				ifElse.addElif(convertPrismProperty(svPtrTranslations, condition));
			} else {
				ifElse.addElif(createNegation(convertPrismProperty(svPtrTranslations, condition)));
			}
		} else {
			ifElse.addElse();
		}
		CLVariable valueKnown = propertyVar.accessField("valueKnown");
		CLVariable propertyState = propertyVar.accessField("propertyState");
		if (propertyValue) {
			ifElse.addExpression(ifElse.size() - 1, createAssignment(propertyState, fromString("true")));
		} else {
			ifElse.addExpression(ifElse.size() - 1, createAssignment(propertyState, fromString("false")));
		}
		ifElse.addExpression(ifElse.size() - 1, createAssignment(valueKnown, fromString("true")));
	}

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
			CLVariable stateVector = new CLVariable(varStateVector.getPointer(), "sv");
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
			//rest
			for (int i = 1; i < cmd.getModulesNum(); ++i) {
				IfElse ifElse = new IfElse(createBinaryExpression(labelSize.getSource(), Operator.NE, fromString(0)));
				ifElse.addExpression(createAssignment(currentSize, fromString(0)));
				for (int j = 0; j < cmd.getCommandNumber(i); ++j) {
					guardsSynAddGuard(ifElse, guardsTab.accessElement(fromString(guardCounter++)),
					//guardsTab[counter] = evaluate(guard)
							cmd.getCommand(i, j), currentSize);
				}
				ifElse.addExpression(createBinaryExpression(labelSize.getSource(),
				// cmds_for_label *= cmds_for_module;
						Operator.MUL_AUGM, currentSize.getSource()));
				ifElse.addExpression(createAssignment(saveSize.accessElement(fromString(i)), currentSize));
				current.addExpression(ifElse);
			}
			saveSize = synState.accessField("size");
			current.addExpression(createAssignment(saveSize, labelSize));
			current.addReturn(labelSize);
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

			Method current = new Method(String.format("updateSyn__%s", cmd.synchLabel), new StdVariableType(timingProperty ? StdType.VOID : StdType.BOOL));
			//state vector
			CLVariable stateVector = new CLVariable(varStateVector.getPointer(), "sv");
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
			//changeFlag - for loop detection
			CLVariable changeFlag = null;
			if (!timingProperty) {
				changeFlag = new CLVariable(new StdVariableType(StdType.BOOL), "changeFlag");
				changeFlag.setInitValue(StdVariableType.initialize(1));
			}

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
					CLVariable structureVar = new CLVariable(new StdVariableType(var), translateSVField(var.name));
					savedVarsType.addVariable(structureVar);
				}
				//add to global declarations
				additionalDeclarations.add(savedVarsType.getDefinition());

				savedVarsInstance = new CLVariable(savedVarsType, "oldSV");
				CLValue[] init = new CLValue[varsToSave.size()];
				int i = 0;
				for (PrismVariable var : varsToSave) {
					init[i++] = stateVector.accessField(translateSVField(var.name));
				}
				savedVarsInstance.setInitValue(savedVarsType.initializeStdStructure(init));
			}

			try {
				current.addArg(stateVector);
				current.addArg(synState);
				current.addArg(propability);
				current.addLocalVar(guard);
				current.addLocalVar(totalSize);
				if (!timingProperty) {
					current.addLocalVar(changeFlag);
				}

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
				if (timingProperty) {
					current.addExpression(callUpdate);
				} else {
					current.addExpression(createAssignment(changeFlag, createBinaryExpression(callUpdate, Operator.LAND, changeFlag.getSource())));
				}

				updateSynAfterUpdateLabel(current, guard, moduleSize, totalSize, propability);
			}

			if (!timingProperty) {
				current.addReturn(changeFlag);
			}
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
		//don't return anything
				new StdVariableType(timingProperty ? StdType.VOID : StdType.BOOL));
		CLVariable stateVector = new CLVariable(varStateVector.getPointer(), "sv");
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
		CLVariable newValue = null, changeFlag = null;
		if (!timingProperty) {
			newValue = new CLVariable(new StdVariableType(StdType.INT32), "newValue");
			newValue.setInitValue(StdVariableType.initialize(0));
			changeFlag = new CLVariable(new StdVariableType(StdType.BOOL), "changeFlag");
			changeFlag.setInitValue(StdVariableType.initialize(0));
		}
		try {
			current.addArg(stateVector);
			current.addArg(guardsTab);
			current.addArg(module);
			current.addArg(guard);
			current.addArg(probabilityPtr);
			if (oldSV != null) {
				current.addArg(oldSV);
			}
			if (!timingProperty) {
				current.addLocalVar(newValue);
				current.addLocalVar(changeFlag);
			}
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
		Map<String, CLVariable> savedTranslations = null;
		if (oldSV != null) {
			savedTranslations = new HashMap<>();
			
			for (CLVariable var : savedVariables.getFields()) {
				String name = var.varName.substring(STATE_VECTOR_PREFIX.length());
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
				if (update.getActionsNumber() > 1) {
					IfElse ifElse = new IfElse(createBinaryExpression(probability.getSource(), Operator.LT,
							fromString(convertPrismRate(svPtrTranslations, rate))));
					if (!update.isActionTrue(0)) {
						ifElse.addExpression(0, updateSynLabelMethodProbabilityRecompute(probability, null, rate));
						
						addSavedVariables(stateVector, ifElse, 0, update.getAction(0), savedTranslations, varsSaved);
						// make temporary copy, we may ovewrite some variables
						Map<String, CLVariable> newSavedTranslations;
						if( savedTranslations != null) {
							newSavedTranslations = new HashMap<>(savedTranslations);
						} else {
							newSavedTranslations = new HashMap<>();
						}
						newSavedTranslations.putAll( varsSaved );
						
						if (!timingProperty) {
							ifElse.addExpression(0,
									convertPrismAction(stateVector, update.getAction(0), svPtrTranslations, newSavedTranslations, changeFlag, newValue));
						} else {
							ifElse.addExpression(0, convertPrismAction(stateVector, update.getAction(0), svPtrTranslations, newSavedTranslations));
						}
					}
					for (int k = 1; k < update.getActionsNumber(); ++k) {
						Rate previous = new Rate(rate);
						rate.addRate(update.getRate(k));
						ifElse.addElif(createBinaryExpression(probability.getSource(), Operator.LT, fromString(convertPrismRate(svPtrTranslations, rate))));
						ifElse.addExpression(k, updateSynLabelMethodProbabilityRecompute(probability, previous, update.getRate(k)));
						
						addSavedVariables(stateVector, ifElse, k, update.getAction(k), savedTranslations, varsSaved);
						// make temporary copy, we may ovewrite some variables
						Map<String, CLVariable> newSavedTranslations;
						if( savedTranslations != null) {
							newSavedTranslations = new HashMap<>(savedTranslations);
						} else {
							newSavedTranslations = new HashMap<>();
						}
						newSavedTranslations.putAll( varsSaved );
						
						if (!update.isActionTrue(k)) {
							if (!timingProperty) {
								ifElse.addExpression(k,
										convertPrismAction(stateVector, update.getAction(k), svPtrTranslations, newSavedTranslations, changeFlag, newValue));
							} else {
								ifElse.addExpression(k, convertPrismAction(stateVector, update.getAction(k), svPtrTranslations, newSavedTranslations));
							}
						}
					}
					internalSwitch.addExpression(j, ifElse);
				} else {
					if (!update.isActionTrue(0)) {
						
						addSavedVariables(stateVector, internalSwitch, j, update.getAction(0), savedTranslations, varsSaved);
						// make temporary copy, we may ovewrite some variables
						Map<String, CLVariable> newSavedTranslations;
						if( savedTranslations != null) {
							newSavedTranslations = new HashMap<>(savedTranslations);
						} else {
							newSavedTranslations = new HashMap<>();
						}
						newSavedTranslations.putAll( varsSaved );
						
						if (!timingProperty) {
							internalSwitch.addExpression(j,
									convertPrismAction(stateVector, update.getAction(0), svPtrTranslations, newSavedTranslations, changeFlag, newValue));
						} else {
							internalSwitch.addExpression(j, convertPrismAction(stateVector, update.getAction(0), svPtrTranslations, newSavedTranslations));
						}
						//no recomputation necessary!
					}
				}
			}
			moduleOffset += synCmd.getCommandNumber(i);
			_switch.addExpression(i, internalSwitch);
		}
		current.addExpression(_switch);
		if (!timingProperty) {
			current.addReturn(changeFlag);
		}
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
	protected Expression updateSynLabelMethodProbabilityRecompute(CLVariable probability, Rate before, Rate current)
	{
		Expression compute = null;
		if (before != null) {
			compute = createBinaryExpression(probability.getSource(), Operator.SUB,
			//probability - sum of rates before
					fromString(convertPrismRate(svPtrTranslations, before)));
		} else {
			compute = probability.getSource();
		}
		addParentheses(compute);
		return createAssignment(probability, createBinaryExpression(compute, Operator.DIV,
		//divide by current interval
				fromString(convertPrismRate(svPtrTranslations, current))));
	}
	/*********************************
	 * OTHER METHODS
	 ********************************/

	/**
	 * @param varName variable name
	 * @return corresponding field in StateVector structure
	 */
	public String translateSVField(String varName)
	{
		return String.format("%s%s", STATE_VECTOR_PREFIX, varName);
	}

	/**
	 * @param varName variable name
	 * @return corresponding variable to "save" previous value from StateVector
	 */
	public String translateSavedVariable(String varName)
	{
		return String.format("%s%s", SAVED_VARIABLE_PREFIX, varName);
	}

	/**
	 * Create variables, which need to be save before action, and their declarations to proper IfElse condition.
	 * @param stateVector state vector instance in the method
	 * @param ifElse kernel component to put declaration
	 * @param conditionalNumber condition number in component
	 * @param action
	 * @param variableSources additional parameter (may be null, if not used) - for each variable, 
	 * give additional source (different than default which is state vector)
	 * @param savedVariables map to save results
	 */
	protected void addSavedVariables(CLVariable stateVector, IfElse ifElse, int conditionalNumber, 
			Action action, Map<String, CLVariable> variableSources, Map<String, CLVariable> savedVariables)
	{
		//clear previous adds
		savedVariables.clear();
		Set<PrismVariable> varsToSave = action.variablesCopiedBeforeUpdate();

		//for every saved variable, create a local variable in C
		for (PrismVariable var : varsToSave) {
			CLVariable savedVar = new CLVariable(new StdVariableType(var), translateSavedVariable(var.name));
			
			// are there any other sources of variables rather than original state vector?
			// this additional parameter is used in synchronized update, where one may want to
			// initialize 'saved' variable with the value in oldStateVector
			// (unnecessary usage of variables may happen, but OpenCL compiler should eliminate that)
			boolean flag = false;
			if( variableSources != null ) {
				CLVariable source = variableSources.get(var.name);
				if( source != null ) {
					flag = true;
					savedVar.setInitValue(source);
				}
			}
			
			// not using additional source - just initialize variable from state vector
			if(!flag) {
				savedVar.setInitValue(stateVector.accessField(translateSVField(var.name)));
			}
			ifElse.addExpression(conditionalNumber, savedVar.getDefinition());

			savedVariables.put(var.name, savedVar);
		}
	}

	/**
	 * Create variables, which need to be save before action, and put declarations in proper Switch condition.
	 * @param stateVector state vector instance in the method
	 * @param _switch kernel component to put declaration
	 * @param conditionalNumber condition number in component
	 * @param action
	 * @param variableSources additional parameter (may be null, if not used) - for each variable, 
	 * give additional source (different than default which is state vector)
	 * @param savedVariables map to save results
	 */
	protected void addSavedVariables(CLVariable stateVector, Switch _switch, int conditionalNumber, Action action,
			Map<String, CLVariable> variableSources, Map<String, CLVariable> savedVariables)
	{
		//clear previous adds
		savedVariables.clear();
		Set<PrismVariable> varsToSave = action.variablesCopiedBeforeUpdate();

		//for every saved variable, create a local variable in C
		for (PrismVariable var : varsToSave) {
			CLVariable savedVar = new CLVariable(new StdVariableType(var), translateSavedVariable(var.name));
			
			// are there any other sources of variables rather than original state vector?
			// this additional parameter is used in synchronized update, where one may want to
			// initialize 'saved' variable with the value in oldStateVector
			// (unnecessary usage of variables may happen, but OpenCL compiler should eliminate that)
			boolean flag = false;
			if( variableSources != null ) {
				CLVariable source = variableSources.get(var.name);
				if( source != null ) {
					flag = true;
					savedVar.setInitValue(source);
				}
			}
			
			// not using additional source - just initialize variable from state vector
			if(!flag) {
				savedVar.setInitValue(stateVector.accessField(translateSVField(var.name)));
			}
				
			_switch.addExpression(conditionalNumber, savedVar.getDefinition());

			savedVariables.put(var.name, savedVar);
		}
	}
}
