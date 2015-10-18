//==============================================================================
//	
//	Copyright (c) 2002-
//	Authors:
//	* Marcin Copik <mcopik@gmail.com> (RWTH Aachen, formerly Silesian University of Technology)
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

import static simulator.opencl.kernel.expression.ExpressionGenerator.createAssignment;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createBinaryExpression;
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import parser.ast.RewardStruct;
import parser.ast.RewardStructItem;
import prism.Pair;
import simulator.opencl.RuntimeConfig;
import simulator.opencl.automaton.AbstractAutomaton.AutomatonType;
import simulator.opencl.automaton.command.SynchronizedCommand;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.ExpressionGenerator;
import simulator.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.opencl.kernel.expression.IfElse;
import simulator.opencl.kernel.expression.KernelComponent;
import simulator.opencl.kernel.expression.Method;
import simulator.opencl.kernel.memory.ArrayType;
import simulator.opencl.kernel.memory.CLValue;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.CLVariable.Location;
import simulator.opencl.kernel.memory.PointerType;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.opencl.kernel.memory.StdVariableType.StdType;
import simulator.opencl.kernel.memory.StructureType;
import simulator.sampler.SamplerDouble;
import simulator.sampler.SamplerRewardReach;

/**
 * This class generates code responsible for verification of reward properties.
 * 
 * The general algorithm is:
 * 1) Update transition and cumulative rewards, using previous state rewards <- Generate functions!
 * Depends on selected update (unsychronized/synchronized) which requires that the function call will be injected directly
 * before updating state vector when the chosen transition is known.
 * For CTMC, requires new and old time.
 * 2) Process the state vector update
 * 3) Compute new state rewards <- Generate functions!
 * Requires only new state vector.
 * 4) Recheck reward properties.
 */
public abstract class RewardGenerator implements KernelComponentGenerator
{
	/**
	 * Structure contains two fields:
	 * - a boolean "valueKnown" which marks whether the property has been already computed.
	 * - a floating-point value "propertyState" which contains the result; the type (single vs double precision)
	 * depends on the runtime configuration
	 */
	protected final StructureType REWARD_PROPERTY_STATE_STRUCTURE;

	/**
	 * Type of array containing states of all properties.
	 */
	protected final ArrayType REWARD_PROPERTY_STATE_ARRAY_TYPE;

	/**
	 * Structure fields used to save only what's necessary to gather the rewards for properties.
	 * 
	 * For details, look at the initializeRewardRequiredVars() methods.
	 */

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
	protected final static String REWARD_STRUCTURE_VAR_PREVIOUS_TRANSITION = "prevTransReward";

	/**
	 * Generic name of function which updates transition rewards.
	 */
	protected final static String TRANSITION_REWARD_UPDATE_FUNCTION_NAME = "transitionRewardUpdate";

	/**
	 * Generic name of function which updates state rewards.
	 */
	protected final static String STATE_REWARD_UPDATE_FUNCTION_NAME = "stateRewardUpdate";

	/**
	 * Variables required to save the state of reward in a C structure.
	 * The key is the class object of Sampler.
	 */
	protected final Map<Class<? extends SamplerDouble>, String[]> REWARD_REQUIRED_VARIABLES;

	/**
	 * Structures for rewards, indexed by PRISM rewardStructureIndex.
	 */
	protected Map<Integer, StructureType> rewardStructures = new TreeMap<>();

	/**
	 * Functions used to compute transition rewards.
	 * Key is the synchronization label or "" for non-synchronous update. Keeping an integer index would be nicer, but PRISM
	 * reward structure use strings unfortunately.
	 * Template:
	 * transitionRewardUpdate_unSynch(StateVector * sv, REWARD_STRUCTURE_0 * ...);
	 * OR
	 * transitionRewardUpdate_$synchlabel(StateVector * sv, REWARD_STRUCTURE_0 * ...);
	 * 
	 * The second element contains indices of reward structures used by this method.
	 */
	protected Map<String, Pair<Collection<Integer>, Method>> transitionUpdateFunctions = new TreeMap<>();

	/**
	 * Functions used to compute state rewards.
	 * 
	 * For each reward structure, generate a separate method:
	 * stateRewardUpdate_$(reward_index)(StateVector * sv, REWARD_STRUCTURE_$(reward_index) *);
	 */
	protected Map<Integer, Method> stateUpdateFunctions = new TreeMap<>();

	/**
	 * Keep all helper methods - transition & state updates, property checking.
	 */
	protected List<Method> helperMethods = new ArrayList<>();

	/**
	 * For each property, add a double array corresponding to the result of this property in given thread.
	 */
	protected List<CLVariable> kernelArgs = null;

	/**
	 * For each reward structure, add a structure instance keeping data.
	 */
	protected List<CLVariable> rewardStructuresVars = null;

	/**
	 * Variable keeping state of all properties.
	 */
	protected CLVariable propertiesStateVar = null;

	/**
	 * Reward generator uses only the type of reward variable.
	 */
	protected RuntimeConfig config = null;

	/**
	 * Reward properties - all types are inherited from a SamplerDouble class.
	 */
	protected Collection<SamplerDouble> rewardProperties = null;

	/**
	 * Parent generator - access data
	 */
	protected KernelGenerator generator = null;

	public RewardGenerator(KernelGenerator generator) throws KernelException
	{
		this.generator = generator;
		this.config = generator.getRuntimeConfig();
		this.rewardProperties = generator.getRewardProperties();
		REWARD_REQUIRED_VARIABLES = initializeRewardRequiredVars();
		REWARD_PROPERTY_STATE_STRUCTURE = createPropertyStateType();
		REWARD_PROPERTY_STATE_ARRAY_TYPE = new ArrayType(REWARD_PROPERTY_STATE_STRUCTURE, rewardProperties.size());

		createRewardStructures();
		createRewardFunctions();

	}

	/**
	 * Analyze properties and create structures for all rewards, to keep only the necessary evaluations.
	 * @throws KernelException
	 */
	protected void createRewardStructures() throws KernelException
	{
		int index = -1;
		boolean flags[] = null;
		String vars[] = null;

		/**
		 * For each reward property, add necessary variables (skip if they have been already
		 * defined by some other reward).
		 */
		for (SamplerDouble rewardProperty : rewardProperties) {

			index = rewardProperty.getRewardIndex();
			vars = REWARD_REQUIRED_VARIABLES.get(rewardProperty.getClass());
			if (vars == null) {
				throw new KernelException("Unknown type of reward property: " + rewardProperty.getClass().getName() + " for property: "
						+ rewardProperty.toString());
			}

			flags = new boolean[vars.length];
			Arrays.fill(flags, true);
			StructureType type = rewardStructures.get(index);

			if (type != null) {
				for (int i = 0; i < vars.length; ++i) {
					flags[i] = type.containsField(vars[i]);
				}
			} else {
				type = new StructureType(String.format("REWARD_STRUCTURE_%d", index));
				rewardStructures.put(index, type);
			}

			for (int i = 0; i < vars.length; ++i) {
				if (flags[i]) {
					type.addVariable(new CLVariable(rewardVarsType(), vars[i]));
				}
			}
		}
	}

	/**
	 * Create all functions used by reward computations:
	 * a) updating transition-based rewards for non-synch and synchronous updates
	 * b) updating state-based rewards 
	 * c) checking properties
	 * d) writing output
	 * @throws KernelException 
	 */
	protected void createRewardFunctions() throws KernelException
	{
		/**
		 * For every synchronization label, group rewards with corresponding reward index.
		 */
		Map<String, List<Pair<Integer, RewardStructItem>>> transitionRewards = new HashMap<>();
		/**
		 * For every reward structure, group state rewards indexed by reward structure.
		 */
		Map<Integer, List<RewardStructItem>> stateRewards = new HashMap<>();

		int rwdIdx = 0;
		for (RewardStruct struct : generator.getModel().getPrismRewards()) {
			int size = struct.getNumItems();
			for (int i = 0; i < size; ++i) {
				RewardStructItem item = struct.getRewardStructItem(i);
				if (item.isTransitionReward()) {
					insertIntoMultiMap(transitionRewards, new Pair<>(rwdIdx, item), item.getSynch());
				} else {
					insertIntoMultiMap(stateRewards, item, rwdIdx);
				}
			}
			++rwdIdx;
		}

		/**
		 * Now create functions.
		 */
		createTransitionRewardFunction(transitionRewards);
		createStateRewardFunction(stateRewards);
	}

	/**
	 * For each synchronization label, create a function which updates all needed transition rewards.
	 * @param transitionRewards synchronization label -> list of: reward & index of reward structure
	 * @throws KernelException
	 */
	private void createTransitionRewardFunction(Map<String, List<Pair<Integer, RewardStructItem>>> transitionRewards) throws KernelException
	{
		CLVariable pointerSV = new CLVariable(new PointerType(generator.getStateVectorType()), "sv");
		Map<String, String> stateVectorTranslations = generator.getSVTranslations();
		for (Map.Entry<String, List<Pair<Integer, RewardStructItem>>> item : transitionRewards.entrySet()) {

			/**
			 * Method args:
			 * 1) pointer to state vector
			 * 2) pointers to reach reward structure which is going to be updated
			 */
			Method method = null;
			if (item.getKey().isEmpty()) {
				method = new Method(String.format("%s_unsynch", TRANSITION_REWARD_UPDATE_FUNCTION_NAME), new StdVariableType(StdType.VOID));
			} else {
				method = new Method(String.format("%s_%s", TRANSITION_REWARD_UPDATE_FUNCTION_NAME, item.getKey()), new StdVariableType(StdType.VOID));
			}
			method.addArg(pointerSV);

			/**
			 * LinkedHashSet is necessary to preserve the insert order - it will keep the order of arguments in function.
			 */
			Map<Integer, CLVariable> rewardArgs = new LinkedHashMap<>();
			boolean nonEmptyFunc = false;
			for (Pair<Integer, RewardStructItem> rwItem : item.getValue()) {

				/**
				 * Update the transition reward only IFF it's used by some property!
				 */
				StructureType rewardStruct = rewardStructures.get(rwItem.first);
				if (rewardStruct == null)
					continue;
				CLVariable pointer = rewardArgs.get(rwItem.first);
				if (pointer == null) {
					pointer = new CLVariable(new PointerType(rewardStruct), String.format("rewardStructure_%d", rwItem.first));
					method.addArg(pointer);
					rewardArgs.put(rwItem.first, pointer);
				}

				/**
				 * Check if we need to compute the transition reward.
				 */
				CLVariable structureField = null;
				structureField = pointer.accessField(REWARD_STRUCTURE_VAR_PREVIOUS_TRANSITION);
				if (structureField == null)
					continue;

				/**
				 * First, process the guard.
				 */
				Expression guard = ExpressionGenerator.convertPrismGuard(stateVectorTranslations, rwItem.second.getStates());
				IfElse condition = new IfElse(guard);
				Expression rw = ExpressionGenerator.convertPrismUpdate(pointerSV, rwItem.second.getReward(), stateVectorTranslations, null);

				/**
				 * If the guard is true, then write new transition reward.
				 */
				Expression assignment = createAssignment(structureField, rw);
				condition.addExpression(assignment);

				// otherwise, we would get empty functions for rewards which are not needed
				nonEmptyFunc = true;
				method.addExpression(condition);
			}

			// put method with indices of reward structures
			if (nonEmptyFunc) {
				transitionUpdateFunctions.put(item.getKey(), new Pair<Collection<Integer>, Method>(rewardArgs.keySet(), method));
				helperMethods.add(method);
			}
		}
	}

	private void createStateRewardFunction(Map<Integer, List<RewardStructItem>> stateRewards) throws KernelException
	{
		CLVariable pointerSV = new CLVariable(new PointerType(generator.getStateVectorType()), "sv");
		Map<String, String> stateVectorTranslations = generator.getSVTranslations();

		for (Map.Entry<Integer, List<RewardStructItem>> item : stateRewards.entrySet()) {

			/**
			 * Method args:
			 * 1) pointer to state vector
			 * 2) pointer to reward structure which is going to be updated
			 */
			Method method = new Method(String.format("%s_%d", STATE_REWARD_UPDATE_FUNCTION_NAME, item.getKey()), new StdVariableType(StdType.VOID));
			method.addArg(pointerSV);

			/**
			 * Update the transition reward only IFF it's used by some property!
			 */
			StructureType rewardStruct = rewardStructures.get(item.getKey());
			if (rewardStruct == null)
				continue;
			CLVariable rwStructPointer = new CLVariable(new PointerType(rewardStruct), String.format("rewardStructure_%d", item.getKey()));
			method.addArg(rwStructPointer);

			stateRewardFunctionAdditionalArgs(method);

			CLVariable transitionRw = rwStructPointer.accessField(REWARD_STRUCTURE_VAR_PREVIOUS_TRANSITION);
			CLVariable prevStateRw = rwStructPointer.accessField(REWARD_STRUCTURE_VAR_PREVIOUS_STATE);
			CLVariable curStateRw = rwStructPointer.accessField(REWARD_STRUCTURE_VAR_CURRENT_STATE);

			/**
			 * Compute the cumulative reward - it always requires keeping rewards from previous state (new state should not be included
			 * if the property is validated in current state).
			 * "current state" contains rewards from previous state (we haven't touched it yet!)
			 * Implementation is different for both DTMC and CTMC.
			 */
			CLVariable cumulRw = rwStructPointer.accessField(REWARD_STRUCTURE_VAR_CUMULATIVE_TOTAL);
			if (cumulRw != null) {
				method.addExpression(stateRewardFunctionComputeCumulRw(cumulRw.getSource(), prevStateRw.getSource(), transitionRw.getSource()));
			}

			/**
			 * Save the previous state if there is a field in structure for it.
			 */
			if (prevStateRw != null && curStateRw != null) {
				method.addExpression(ExpressionGenerator.createBinaryExpression(prevStateRw.getSource(), Operator.AS, curStateRw.getSource()));
			}

			/**
			 * Then write the results:
			 * 1) if there is a field for current state -> write there
			 * 2) if there is a field for previous state -> it's an optimization for cumulative reward,
			 * store the current state there
			 * This is only possible BECAUSE currently there's no sampler which requires only previous, but no current state.
			 */
			Expression stateRewardDest = null;
			if (curStateRw != null) {
				stateRewardDest = curStateRw.getSource();
			} else {
				stateRewardDest = prevStateRw.getSource();
			}
			/**
			 * 'Restart' the state rewards by writing zero (we take a sum over the whole reward structure). 
			 */
			method.addExpression(ExpressionGenerator.createBinaryExpression(stateRewardDest, Operator.AS, fromString(0.0)));

			for (RewardStructItem rwItem : item.getValue()) {

				/**
				 * First, process the guard.
				 */
				Expression guard = ExpressionGenerator.convertPrismGuard(stateVectorTranslations, rwItem.getStates());
				IfElse condition = new IfElse(guard);
				Expression rw = ExpressionGenerator.convertPrismUpdate(pointerSV, rwItem.getReward(), stateVectorTranslations, null);
				condition.addExpression(ExpressionGenerator.createBinaryExpression(stateRewardDest, Operator.ADD_AUGM, rw));

				method.addExpression(condition);
			}

			stateUpdateFunctions.put(item.getKey(), method);
			helperMethods.add(method);
		}
	}

	/**
	 * Add additional arguments for state reward update function.
	 * DTMC:
	 * None
	 * CTMC:
	 * Time spent in time, i.e. new and old time (entering state before update and leaving it).
	 * @param function
	 */
	protected abstract void stateRewardFunctionAdditionalArgs(Method function) throws KernelException;

	/**
	 * Update the cumulative reward kept at destination.
	 * DTMC:
	 * dest += stateReward + transitionReward;
	 * CTMC:
	 * dest += stateReward + timeSpentState * transitionReward;
	 * @param cumulReward
	 * @param stateReward
	 * @param transitionReward
	 * @returns update expression
	 */
	protected abstract Expression stateRewardFunctionComputeCumulRw(Expression cumulReward, Expression stateReward, Expression transitionReward)
			throws KernelException;

	/**
	 * Utility method - insert a value for a map keeping lists of objects.
	 * Checks for existence of a key and creates a new list automatically.
	 * @param map
	 * @param item
	 * @param key
	 */
	private <T, V> void insertIntoMultiMap(Map<T, List<V>> map, V item, T key)
	{
		List<V> list = map.get(key);
		if (list == null) {
			list = new LinkedList<>();
			map.put(key, list);
		}
		list.add(item);
	}

	/**
	 * @return structure type keeping the evaluation of a reward property
	 */
	protected StructureType createPropertyStateType()
	{
		StructureType type = new StructureType("RewardState");
		type.addVariable(new CLVariable(rewardVarsType(), "propertyState"));
		type.addVariable(new CLVariable(new StdVariableType(StdType.BOOL), "valueKnown"));
		return type;
	}

	/**
	 * Not all OpenCL devices support double-precision floating-point numbers.
	 * @return type of variable containing rewards
	 */
	protected StdVariableType rewardVarsType()
	{
		if (this.config.rewardVariableType == RuntimeConfig.RewardVariableType.FLOAT) {
			return new StdVariableType(StdType.FLOAT);
		} else {
			return new StdVariableType(StdType.DOUBLE);
		}
	}

	/**
	 * Import all reward samplers and create C structures to contain required information in kernel launch.
	 * 
	 * For every reward structure:
	 * - reward reachability - total cumulative reward, i.e. 
	 * - instanteous reward DTMC - current state reward
	 * - instanteous reward CTMC - current and previous state reward
	 * - cumulative DTMC - total cumulative reward
	 * - cumulative CTMC - total cumulative reward, previous state & transition reward, current state reward
	 */
	protected Map<Class<? extends SamplerDouble>, String[]> initializeRewardRequiredVars() throws KernelException
	{
		Map<Class<? extends SamplerDouble>, String[]> map = new HashMap<>();

		/**
		 * reachability - same for both CTMC and DTMC
		 * doesn't use the reward/transition fields, but requires an additional temporary to save the rewards.
		 * transition is computed before the update, state after the update and:
		 * cumulative += transition * time + state;
		 */
		map.put(SamplerRewardReach.class, new String[] { REWARD_STRUCTURE_VAR_CUMULATIVE_TOTAL });
		map.put(SamplerRewardReach.class, new String[] { REWARD_STRUCTURE_VAR_PREVIOUS_TRANSITION });
		map.put(SamplerRewardReach.class, new String[] { REWARD_STRUCTURE_VAR_PREVIOUS_STATE });
		// cumulative and instantaneous - different variables for different automata
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
	 * @param map
	 */
	protected abstract void initializeRewardRequiredVarsCumulative(Map<Class<? extends SamplerDouble>, String[]> map);

	/**
	 * DTMC:
	 * - current state reward
	 * 
	 * CTMC:
	 * - current and previous state reward
	 * @param map
	 */
	protected abstract void initializeRewardRequiredVarsInstantaneous(Map<Class<? extends SamplerDouble>, String[]> map);

	/**
	 * Factory method.
	 * @param config
	 * @param type
	 * @param rewardProperties
	 * @return propert intance of reward generator
	 * @throws KernelException 
	 */
	public static RewardGenerator createGenerator(KernelGenerator generator, AutomatonType type) throws KernelException
	{
		if (type == AutomatonType.DTMC) {
			return new RewardGeneratorDTMC(generator);
		} else {
			return new RewardGeneratorCTMC(generator);
		}
	}

	@Override
	public Collection<? extends KernelComponent> getDefinitions()
	{
		List<KernelComponent> definitions = new ArrayList<>();
		for (Map.Entry<Integer, StructureType> rewardStruct : rewardStructures.entrySet()) {
			definitions.add(rewardStruct.getValue().getDefinition());
		}
		definitions.add(REWARD_PROPERTY_STATE_STRUCTURE.getDefinition());
		return definitions;
	}

	/**
	 * @return args for the kernel used by this generator - reward result output
	 */
	public Collection<CLVariable> getKernelArgs()
	{
		/**
		 * Variables containing reward results.
		 */
		kernelArgs = new ArrayList<>(rewardProperties.size());

		for (int i = 0; i < rewardProperties.size(); ++i) {
			CLVariable rewardResult = new CLVariable(new PointerType(rewardVarsType()),
			//propertyNumber
					String.format("rewardOutput_%d", i));
			rewardResult.memLocation = Location.GLOBAL;
			kernelArgs.add(rewardResult);
		}

		return kernelArgs;
	}

	@Override
	public Collection<CLVariable> getLocalVars()
	{
		List<CLVariable> localVars = new ArrayList<>(2 * rewardProperties.size());
		rewardStructuresVars = new ArrayList<>(rewardProperties.size());

		/**
		 * Declarations of structures containing necessary variables corresponding to given rewards.
		 */
		for (Map.Entry<Integer, StructureType> reward : rewardStructures.entrySet()) {
			CLVariable var = new CLVariable(reward.getValue(), String.format("reward_%d", reward.getKey()));
			Float initValues[] = new Float[reward.getValue().getNumberOfFields()];
			Arrays.fill(initValues, 0.0f);
			CLValue initValue = reward.getValue().initializeStdStructure(initValues);
			var.setInitValue(initValue);
			rewardStructuresVars.add(var);
			localVars.add(var);
		}

		/**
		 * Declarations of array the current state of each property.
		 */
		propertiesStateVar = new CLVariable(REWARD_PROPERTY_STATE_ARRAY_TYPE, "rewardProperties");
		localVars.add(propertiesStateVar);
		CLValue initValues[] = new CLValue[rewardProperties.size()];
		CLValue initValue = REWARD_PROPERTY_STATE_STRUCTURE.initializeStdStructure(new Number[] { 0, 0 });
		for (int i = 0; i < initValues.length; ++i) {
			initValues[i] = initValue;
		}
		propertiesStateVar.setInitValue(REWARD_PROPERTY_STATE_ARRAY_TYPE.initializeArray(initValues));

		return localVars;
	}

	/**
	 * Generate code for writing reward result into an OpenCL buffer.
	 * @param threadPosition
	 * @param mainMethod
	 * @param loopDetectionVariable
	 */
	public void writeOutput(Method mainMethod, Expression threadPosition, CLVariable loopDetectionVariable)
	{
		for (int i = 0; i < rewardProperties.size(); ++i) {
			CLVariable result = kernelArgs.get(i).accessElement(threadPosition);

			CLVariable property = propertiesStateVar.accessElement(fromString(i)).accessField("propertyState");
			CLVariable valueKnown = propertiesStateVar.accessElement(fromString(i)).accessField("valueKnown");
			Expression assignment = ExpressionGenerator.createConditionalAssignment(
					createBinaryExpression(valueKnown.getSource(), ExpressionGenerator.Operator.LOR, loopDetectionVariable.getSource()), property.getSource()
							.toString(), "NAN");

			mainMethod.addExpression(createAssignment(result, assignment));
		}
	}

	/**
	 * Before the state update, evaluate transition rewards for a non-synchronized update.
	 * @param stateVector
	 */
	public Expression beforeUpdate(CLVariable stateVector)
	{
		return new Expression("");
	}

	/**
	 * Before the state update, evaluate transition rewards for a synchronized update.
	 * @param stateVector
	 * @param cmd
	 */
	public Expression beforeUpdate(CLVariable stateVector, SynchronizedCommand cmd)
	{
		return new Expression("");
	}

	/**
	 * Before the state update, evaluate state rewards.
	 * @param component
	 * @param stateVector
	 */
	public Expression afterUpdate(CLVariable stateVector)
	{
		return new Expression("");
	}

	/**
	 * @return additional methods which need to be declared and defined
	 */
	public Collection<Method> getAdditionalMethods()
	{
		return helperMethods;
	}
}
