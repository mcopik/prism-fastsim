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

import static simulator.opencl.kernel.expression.ExpressionGenerator.convertPrismProperty;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createAssignment;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createBinaryExpression;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createNegation;
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import parser.ast.RewardStruct;
import parser.ast.RewardStructItem;
import prism.Pair;
import prism.PrismLangException;
import simulator.opencl.RuntimeConfig;
import simulator.opencl.automaton.AbstractAutomaton.AutomatonType;
import simulator.opencl.automaton.command.SynchronizedCommand;
import simulator.opencl.kernel.KernelGenerator.LocalVar;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.ExpressionGenerator;
import simulator.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.opencl.kernel.expression.ExpressionList;
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
import simulator.sampler.SamplerRewardInstCont;
import simulator.sampler.SamplerRewardInstDisc;
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
	protected ArrayType REWARD_PROPERTY_STATE_ARRAY_TYPE;

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
	 * Generic name of function which updates state rewards.
	 */
	protected final static String CHECK_REWARD_PROPERTY_FUNCTION_NAME = "checkRewardProperty";

	/**
	 * Variables required to save the state of reward in a C structure.
	 * The key is the class object of Sampler.
	 */
	protected Map<Class<? extends SamplerDouble>, String[]> REWARD_REQUIRED_VARIABLES;

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
	 * The first element contains indices of reward structures used by this method.
	 */
	protected Map<String, Pair<Collection<Integer>, Method>> transitionUpdateFunctions = new TreeMap<>();

	/**
	 * Functions used to compute state rewards.
	 * 
	 * For each reward structure, generate a separate method:
	 * stateRewardUpdate_$(reward_index)(StateVector * sv, REWARD_STRUCTURE_$(reward_index) *);
	 */
	protected Map<Integer, Method> stateUpdateFunctions = new TreeMap<>();

	/*
	 * Functions used to verify reward properties.
	 * 
	 * Template:
	 * checkRewardProperty_$(type)(StateVector * sv, ARGS, RewardState *, ..., REWARD_STRUCTURE...)
	 * There will be N structures keeping reward state and M <= N structures keeping corresponding reward structures;
	 * M may be smaller then N, because different properties may use the same reward structure.
	 * For reachability, ARGS is empty (no additional args). For other samplers - current time.
	 * 
	 * Pair:
	 * a) method
	 * b) N + M + 1 integers:
	 * value of N, N integers containing properties indices, M 
	 * It's ugly, but I want to keep everything in one map and it's not my fault that Java doesn't have tuple
	 * or variadic generics to properly implement it (equivalence of C++ variadic templates).
	 */
	//protected Map<Class<? extends SamplerDouble>, Pair<Method, Integer[]>> propertyMethods = new TreeMap<>();
	protected Pair<Method, Collection<Integer>> propertyMethod = null;

	/**
	 * Keep all helper methods - transition & state updates, property checking.
	 */
	protected List<Method> helperMethods = new ArrayList<>();

	/**
	 * For each property, add a double array corresponding to the result of this property in given thread.
	 */
	protected List<CLVariable> kernelArgs = null;

	/**
	 * For each reward structure, create a local structure instance keeping data.
	 */
	protected Map<Integer, CLVariable> rewardStructuresVars = null;

	/**
	 * For every synchronization label, group rewards with corresponding reward index.
	 */
	Map<String, List<Pair<Integer, RewardStructItem>>> transitionRewards = new HashMap<>();
	
	/**
	 * Contains indices of all reward structures which have at least one transition reward.
	 */
	Set<Integer> rewardsWithTransition = new HashSet<>();
	
	/**
	 * For every reward structure, group state rewards indexed by reward structure.
	 */
	Map<Integer, List<RewardStructItem>> stateRewards = new HashMap<>();
	
	/**
	 * Variable keeping state of all properties.
	 */
	protected CLVariable propertiesStateVar = null;
	
	/**
	 * An argument of property verification function containing current time.
	 * DTMC - integer, CTMC - floating-point type.
	 */
	protected CLVariable argPropertyTime = null;

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
	
	/**
	 * True if there is at least one reward property.
	 */
	protected boolean activeGenerator = false;
	
	/**
	 * Set to true if there is a property which needs to know the current time for verification.
	 * Used to generate arguments for property methods.
	 * 
	 * Currently only reachability reward doesn't need a time argument. 
	 */
	protected boolean timedReward = false;

	public RewardGenerator(KernelGenerator generator) throws KernelException
	{
		this.generator = generator;
		this.config = generator.getRuntimeConfig();
		this.rewardProperties = generator.getRewardProperties();
		REWARD_PROPERTY_STATE_STRUCTURE = createPropertyStateType();

		if(rewardProperties.size() > 0) {
			activeGenerator = true;
		} else {
			return;
		}
		
		/**
		 * Set to true when there's at least one reward which needs to know the current time.
		 */
		for(SamplerDouble sampler : rewardProperties) {
			if( !(sampler instanceof SamplerRewardReach) ) {
				timedReward = true;
			}
		}
		
		REWARD_REQUIRED_VARIABLES = initializeRewardRequiredVars();
		REWARD_PROPERTY_STATE_ARRAY_TYPE = new ArrayType(REWARD_PROPERTY_STATE_STRUCTURE, rewardProperties.size());

		int rwdIdx = 0;
		for (RewardStruct struct : generator.getModel().getPrismRewards()) {
			int size = struct.getNumItems();
			for (int i = 0; i < size; ++i) {
				RewardStructItem item = struct.getRewardStructItem(i);
				if (item.isTransitionReward()) {
					insertIntoMultiMap(transitionRewards, new Pair<>(rwdIdx, item), item.getSynch());
					rewardsWithTransition.add(rwdIdx);
				} else {
					insertIntoMultiMap(stateRewards, item, rwdIdx);
				}
			}
			++rwdIdx;
		}
		
	}
	
	/**
	 * @return true iff generator is active and produces any code
	 */
	public boolean isGeneratorActive()
	{
		return activeGenerator;
	}	
	
	/**
	 * TODO: generate special case of CTMC cumulative
	 * @return always true
	 */
	public boolean canDetectLoop()
	{
		return !timedReward;
	}

	/**
	 * In a case of loop, we always have enough knowledge to stop sample generation.
	 * @see canDetectLoops() for details
	 * @return always true
	 */
	public boolean canExitOnLoop()
	{
		return canDetectLoop();
	}
	
	/**
	 * Split between constructor and generation function allows for a proper initialization of fields
	 * in child classes.
	 * @throws KernelException
	 * @throws PrismLangException
	 */
	protected void generateRewardCode() throws KernelException, PrismLangException
	{
		createRewardStructures();
		createRewardFunctions(generator.getModel().getPrismRewards().size());
		createPropertyFunctions();
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

			/**
			 * Do not add transition/state rewards if they are not provided by current reward (given by an index)
			 * They will always be zero in this reward structure and should be removed from OpenCL kernel.
			 */
			boolean hasStateReward = stateRewards.containsKey(index);
			boolean hasTransReward = rewardsWithTransition.contains(index);
			for(int i = 0; i < vars.length; ++i) {
				if (vars[i] == REWARD_STRUCTURE_VAR_CURRENT_STATE || vars[i] == REWARD_STRUCTURE_VAR_PREVIOUS_STATE) {
					if( !hasStateReward ) {
						flags[i] = false;
					}
				} else if (vars[i] == REWARD_STRUCTURE_VAR_PREVIOUS_TRANSITION) {
					if( !hasTransReward ) {
						flags[i] = false;
					}
				}
			}
			
			/**
			 * Do not add an already existing variable (shared by different samplers).
			 */
			if (type != null) {
				for (int i = 0; i < vars.length; ++i) {
					flags[i] &= !type.containsField(vars[i]);
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
		
		/**
		 * It may happen, e.g. due to an error in the model, that some property uses a reward
		 * which doesn't contribute any information. For example, an instantaneous reward uses
		 * a reward structure providing only transition rewards.
		 * 
		 * Result is quite simple - we get an empty structure which is invalid in C99. We can neither declare nor
		 * use them.
		 */
		for(Iterator<Entry<Integer, StructureType>> it = rewardStructures.entrySet().iterator(); it.hasNext();) {
			Entry<Integer, StructureType> entry = it.next();
			if(entry.getValue().getFields().size() == 0) {
				it.remove();
			}
		}
	}

	/**
	 * Create all functions used by reward computations:
	 * a) updating transition-based rewards for non-synch and synchronous updates
	 * b) updating state-based rewards 
	 * c) checking properties
	 * d) writing output
	 * @param rewardsCount number of rewards in PRISM model
	 * @throws KernelException 
	 */
	protected void createRewardFunctions(int rewardsCount) throws KernelException
	{
		/**
		 * Now create functions.
		 */
		createTransitionRewardFunction(transitionRewards);
		createStateRewardFunction(rewardsCount, stateRewards);
	}

	/**
	 * For each synchronization label, create a function which updates all needed transition rewards.
	 * @param transitionRewards synchronization label -> list of: reward & index of reward structure
	 * @throws KernelException
	 */
	private void createTransitionRewardFunction(Map<String, List<Pair<Integer, RewardStructItem>>> transitionRewards) throws KernelException
	{
		CLVariable pointerSV = new CLVariable(new PointerType(generator.getSVType()), "sv");
		Map<String, String> stateVectorTranslations = generator.getSVPtrTranslations();
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
				 * Update the transition reward IFF it's used by some property!
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
				Expression assignment = createBinaryExpression(structureField.getSource(), Operator.ADD_AUGM, rw);
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

	private void createStateRewardFunction(int rewardsCount, Map<Integer, List<RewardStructItem>> stateRewards) throws KernelException
	{
		CLVariable pointerSV = new CLVariable(new PointerType(generator.getSVType()), "sv");
		Map<String, String> stateVectorTranslations = generator.getSVPtrTranslations();
		
		/**
		 * For each reward structure:
		 * - if there is at least one state reward: create full function evaluating state reward
		 * - otherwise - create a function updating the cumulative reward, based only on transition rewards
		 */
		for (int i = 0; i < rewardsCount; ++i) {
			
		//for (Map.Entry<Integer, List<RewardStructItem>> item : stateRewards.entrySet()) {

			/**
			 * Method args:
			 * 1) pointer to state vector
			 * 2) pointer to reward structure which is going to be updated
			 */
			Method method = new Method(String.format("%s_%d", STATE_REWARD_UPDATE_FUNCTION_NAME, i), new StdVariableType(StdType.VOID));
			method.addArg(pointerSV);

			/**
			 * Update the transition reward IFF it's used by some property!
			 */
			StructureType rewardStruct = rewardStructures.get( i );
			if (rewardStruct == null)
				continue;
			CLVariable rwStructPointer = new CLVariable(new PointerType(rewardStruct), String.format("rewardStructure_%d", i));
			method.addArg(rwStructPointer);
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
				method.addExpression(stateRewardFunctionComputeCumulRw(cumulRw.getSource(), curStateRw, transitionRw));
			}
			
			/**
			 * And now proceed with state rewards, but only if there are state rewards for this index
			 */
			List<RewardStructItem> stateRw = stateRewards.get(i);
			if (stateRw != null) {

				stateRewardFunctionAdditionalArgs(method, rewardStruct);
	
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
	
				for (RewardStructItem rwItem : stateRw) {
	
					/**
					 * First, process the guard.
					 */
					Expression guard = ExpressionGenerator.convertPrismGuard(stateVectorTranslations, rwItem.getStates());
					IfElse condition = new IfElse(guard);
					Expression rw = ExpressionGenerator.convertPrismUpdate(pointerSV, rwItem.getReward(), stateVectorTranslations, null);
					condition.addExpression(ExpressionGenerator.createBinaryExpression(stateRewardDest, Operator.ADD_AUGM, rw));
	
					method.addExpression(condition);
				}
			}
			
			/**
			 * If we have a transition reward, it has to be restarted after computing cumulative reward.
			 * Otherwise, it could happen that in next iteration of main loop a transition without associated reward
			 * is taken and old value influences incorrectly computation of cumulative reward.
			 * 
			 * HOWEVER: we can't do it now, because this value is necessary for correct computation of cumulative
			 * reward property for CTMCs. There in virtually every case the last transition reward has to be subtracted
			 * from final value of cumulative reward.
			 * 
			 * For simplicity of implementation, for all models the reset process has been moved to property verification
			 * function. Hence we can use correct value of previous transition reward and reset this value before applying transition.
			 */
			
			stateUpdateFunctions.put(i, method);
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
	 * @param rewardStructure
	 */
	protected abstract void stateRewardFunctionAdditionalArgs(Method function, StructureType rewardStructure) throws KernelException;

	/**
	 * Update the cumulative reward kept at destination.
	 * DTMC:
	 * dest += stateReward + transitionReward;
	 * CTMC:
	 * dest += stateReward + timeSpentState * transitionReward;
	 * 
	 * State/transition reward may be null for a case when the reward structure contains only transition/state rewards.
	 * @param cumulReward
	 * @param stateReward
	 * @param transitionReward
	 * @returns update expression
	 */
	protected abstract Expression stateRewardFunctionComputeCumulRw(Expression cumulReward, CLVariable stateReward, CLVariable transitionReward)
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
	 * Create functions for direct computation of reward properties.
	 * - Reachability (common for DTMC, CTMC)
	 * - Instantaneous 
	 * - Cumulative 
	 * @throws KernelException
	 * @throws PrismLangException 
	 */
	protected void createPropertyFunctions() throws KernelException, PrismLangException
	{
		/**
		 * For each type of sampler, keep indices of properties for future processing;
		 * they are necessary for correct passing of arguments.
		 */
//		Map<Class<? extends SamplerDouble>, List< Pair<Integer, SamplerDouble> > > sortedSamplers = new HashMap<>();
//		int idx = 0;
//		for (SamplerDouble sampler : rewardProperties) {
//			insertIntoMultiMap(sortedSamplers, new Pair<>(idx++, sampler), sampler.getClass());
//		}
		//checkRewardProperty_$(type)(StateVector * sv, RewardState *, ..., REWARD_STRUCTURE...)
		CLVariable pointerSV = new CLVariable(new PointerType(generator.getSVType()), "sv");
		Method method = new Method(String.format("%s_Reachability", CHECK_REWARD_PROPERTY_FUNCTION_NAME),
				new StdVariableType(StdType.BOOL));
		method.addArg(pointerSV);
		if (timedReward) {
			// Type of time inherited from the main generator
			argPropertyTime = new CLVariable(generator.varTimeType, "time");
			method.addArg(argPropertyTime);
		}
		// Pointer to array of properties
		CLVariable propertyStatesVar = new CLVariable( REWARD_PROPERTY_STATE_ARRAY_TYPE, "propertyStates" );
		method.addArg(propertyStatesVar);
		
		// return value
		CLVariable allKnown = new CLVariable(new StdVariableType(StdType.BOOL), "allKnown");
		allKnown.setInitValue(StdVariableType.initialize(1));
		method.addLocalVar(allKnown);

		Map<Integer, CLVariable> rewardStructuresArgs = new HashMap<>();
		
		int index = 0;
		for(SamplerDouble sampler : rewardProperties) {

			int rewardIndex = sampler.getRewardIndex();
			
			/**
			 * Generates OpenCL source code for i-th reward property:
			 * 
			 * if( !propertyState[i].valueKnown ){
			 *  if( property_condition is true ) {
			 *    propertyState[i].valueKnown = true;
			 *    compute property value;
			 *  } else {
			 * 	  allKnown = false;
			 *  }
			 * }
			 */
			CLVariable currentProperty = propertyStatesVar.accessElement( fromString(index) );
			CLVariable valueKnown = currentProperty.accessField("valueKnown");
			IfElse ifElse = new IfElse(createNegation(valueKnown.getSource()));
			
			/**
			 * If there is no reward structure then it means that there's no point of iterating through this property
			 * - optimization has showed that this reward will always be zero.
			 * But we add a simple code to write the result only once.
			 */
			if ( rewardStructures.containsKey(rewardIndex) ) {
				
				// pointer to reward structure - add iff it hasn't been already added by other property
				CLVariable rewardStatePointer;
				if( !rewardStructuresArgs.containsKey(rewardIndex) ) {
					rewardStatePointer = new CLVariable(
							new PointerType(rewardStructures.get(rewardIndex)), String.format("rewardState_%d", rewardIndex));
					rewardStructuresArgs.put( rewardIndex, rewardStatePointer);
				} else {
					rewardStatePointer = rewardStructuresArgs.get( rewardIndex );
				}
			
				/**
				 * Reachability reward.
				 */
				if (sampler instanceof SamplerRewardReach) {
					createPropertyReachability(ifElse, (SamplerRewardReach) sampler, currentProperty, rewardStatePointer);
				}
				/**
				 * DTMC/CTMC instanteous reward
				 */
				else if (sampler instanceof SamplerRewardInstCont || sampler instanceof SamplerRewardInstDisc) {
					createPropertyInst(ifElse, sampler, currentProperty, rewardStatePointer);
				}
				/**
				 * DTMC/CTMC cumulative reward
				 */
				else {
					createPropertyCumul(ifElse, sampler, currentProperty, rewardStatePointer);
				}
			} else {
				CLVariable propertyState = currentProperty.accessField("propertyState");
				ifElse.addExpression(0, createAssignment(propertyState, fromString(0.0)));
				ifElse.addExpression(0, createAssignment(valueKnown, fromString("true")));
			}
			ifElse.addExpression(0, createBinaryExpression(allKnown.getSource(), 
					ExpressionGenerator.Operator.LAND_AUGM, valueKnown.getSource()));
			
			method.addExpression(ifElse);
			
		}

		method.addArg( rewardStructuresArgs.values() );
		
		/**
		 * If the reward structure contains a 'previous transition' field,
		 * write a zero value to restart it before taking next update.
		 * 
		 * Ensures that a CTMC cumulative reward can subtract this value when time > boundary,
		 * and after next update the transition reward is correct without manually setting value
		 * in each update label, even if there is no transition reward corresponding to it.
		 */
		for(Map.Entry<Integer, CLVariable> rewardStruct : rewardStructuresArgs.entrySet()) {

			CLVariable transitionRw = rewardStruct.getValue().accessField(REWARD_STRUCTURE_VAR_PREVIOUS_TRANSITION);
			if( transitionRw != null ) {
				method.addExpression( createAssignment(transitionRw, fromString(0)) );
			}
	
		}
		
		method.addReturn(allKnown);
		helperMethods.add(method);
		propertyMethod = new Pair<Method, Collection<Integer>>( method, rewardStructuresArgs.keySet() );
	}
	
	/**
	 * Implementation is very simple:
	 * if the target expression is true, then take the total cumulative reward and stop.
	 * 
	 * @param samplers
	 * @throws KernelException
	 * @throws PrismLangException 
	 */
	protected void createPropertyReachability(IfElse ifElse, SamplerRewardReach property, 
			CLVariable propertyState, CLVariable rewardState) throws KernelException, PrismLangException
	{
		CLVariable cumulativeReward = rewardState.accessField(REWARD_STRUCTURE_VAR_CUMULATIVE_TOTAL);
		String propertyCondition = visitPropertyExpression( property.getTarget() ).toString();
		ifElse.addExpression( createPropertyCondition(propertyState, propertyCondition, cumulativeReward.getSource()) );
	}

	/**
	 * TODO: copied directly from KernelGenerator. it should be in a parent class for property/reward
	 * Creates IfElse for property in PRISM language.
	 * @param propertyVar
	 * @param negation
	 * @param condition
	 * @param propertyValue
	 * @return property verification in conditional - write results to property structure
	 */
	protected IfElse createPropertyCondition(CLVariable propertyVar, String condition, Expression propertyValue)
	{
		IfElse ifElse = null;
		ifElse = new IfElse(convertPrismProperty(generator.svPtrTranslations, condition));
		CLVariable valueKnown = propertyVar.accessField("valueKnown");
		CLVariable propertyState = propertyVar.accessField("propertyState");
		ifElse.addExpression(0, createAssignment(propertyState, propertyValue));
		ifElse.addExpression(0, createAssignment(valueKnown, fromString("true")));
		return ifElse;
	}
	
	/**
	 * TODO: copied directly from KernelGenerator. it should be in a parent class for property/reward
	 * Creates IfElse for property in OpaenCL C.
	 * @param propertyVar
	 * @param negation
	 * @param condition
	 * @param propertyValue
	 * @return property verification in conditional - write results to property structure
	 */
	protected IfElse createPropertyCondition(CLVariable propertyVar, Expression condition, Expression propertyValue)
	{
		IfElse ifElse = null;
		ifElse = new IfElse(condition);
		createPropertyCondition(propertyVar, ifElse, propertyValue);
		return ifElse;
	}
	
	private void createPropertyCondition(CLVariable propertyVar, IfElse ifElse, Expression propertyValue)
	{
		CLVariable valueKnown = propertyVar.accessField("valueKnown");
		CLVariable propertyState = propertyVar.accessField("propertyState");
		ifElse.addExpression(0, createAssignment(propertyState, propertyValue));
		ifElse.addExpression(0, createAssignment(valueKnown, fromString("true")));
	}
	
	/**
	 * @param prop
	 * @return translate property: add parentheses, cast to float in division etc
	 * @throws PrismLangException
	 */
	protected parser.ast.Expression visitPropertyExpression(parser.ast.Expression prop) throws PrismLangException
	{
		return (parser.ast.Expression) prop.accept(generator.treeVisitor);
	}
	
	/**
	 * Implementation is different in discrete and continuous-time:
	 * a) DTMC: 
	 * 		- time bound reached - save current cumulative reward
	 * 		- deadlock - current cumulative reward + missing_time * (state)
	 * 		- loop - current cumulative reward + missing_time * (transition + state)
	 * Regarding if we're looping or reached deadlock: missing time is known because
	 * of a discrete time space.
	 * 
	 * b) CTMC:
	 * 		- time bound exceeded - save:
	 * 			cumulative reward - (time - bound) * previous state - transition if diff > 0
	 * 		- deadlock - current cumulative reward + missing_time * (state)
	 * 		- loop - continue looping until reaching time bound.
	 * If we have exactly reached the bound, then time - bound == 0 and we perform no substraction.
	 * In a case of deadlock we can compute missing state reward.
	 * When a loop is detected, one can't know a priori how many transitions are going to be executed
	 * before time bound is reached.
	 * 
	 * @param ifElse
	 * @param property
	 * @param propertyState
	 * @param rewardState
	 */
	protected abstract void createPropertyCumul(IfElse ifElse, SamplerDouble property, CLVariable propertyState, CLVariable rewardState);
	
	/**
	 * Implementation is different in discrete and continuous-time:
	 * a) DTMC: 
	 * 		- time bound reached - save current state reward
	 * 		- deadlock - save current state reward
	 * 		- loop - save current state reward
	 * Two latter approaches are justified by reaching time bound in exactly the same state.
	 * 
	 * b) CTMC:
	 * 		- time bound exceeded - save prev state reward
	 * 		  time bound equal to current time - save current state reward
	 * 		- deadlock - save current state reward
	 * 		- loop - save current state reward
	 * 
	 * @param ifElse
	 * @param property
	 * @param propertyState
	 * @param rewardState
	 */
	protected abstract void createPropertyInst(IfElse ifElse, SamplerDouble property, CLVariable propertyState, CLVariable rewardState);
	
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
	 * - reward reachability - total cumulative reward
	 * - instanteous reward DTMC - current state reward
	 * - instanteous reward CTMC - current and previous state reward
	 * - cumulative DTMC - total cumulative reward
	 * - cumulative CTMC - total cumulative reward, previous state & transition reward,
	 * 		current state reward for deadlock
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
		map.put(SamplerRewardReach.class, new String[] { REWARD_STRUCTURE_VAR_CUMULATIVE_TOTAL, 
						REWARD_STRUCTURE_VAR_PREVIOUS_TRANSITION,
						REWARD_STRUCTURE_VAR_CURRENT_STATE });
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
	 * @throws PrismLangException 
	 */
	public static RewardGenerator createGenerator(KernelGenerator generator, AutomatonType type) throws KernelException, PrismLangException
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
		if (activeGenerator) {
			List<KernelComponent> definitions = new ArrayList<>();
			for (Map.Entry<Integer, StructureType> rewardStruct : rewardStructures.entrySet()) {
				definitions.add(rewardStruct.getValue().getDefinition());
			}
			definitions.add(REWARD_PROPERTY_STATE_STRUCTURE.getDefinition());
			return definitions;
		}
		return Collections.emptyList();
	}

	/**
	 * @return args for the kernel used by this generator - reward result output
	 */
	public Collection<CLVariable> getKernelArgs()
	{
		if(activeGenerator) {
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
		return Collections.emptyList();
	}

	@Override
	public Collection<CLVariable> getLocalVars()
	{
		if(activeGenerator) {
			List<CLVariable> localVars = new ArrayList<>(2 * rewardProperties.size());
			rewardStructuresVars = new TreeMap<>();
	
			/**
			 * Declarations of structures containing necessary variables corresponding to given rewards.
			 */
			for (Map.Entry<Integer, StructureType> reward : rewardStructures.entrySet()) {
				CLVariable var = new CLVariable(reward.getValue(), String.format("reward_%d", reward.getKey()));
				Float initValues[] = new Float[reward.getValue().getNumberOfFields()];
				Arrays.fill(initValues, 0.0f);
				CLValue initValue = reward.getValue().initializeStdStructure(initValues);
				var.setInitValue(initValue);
				rewardStructuresVars.put(reward.getKey(), var);
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
		return Collections.emptyList();
	}

	/**
	 * Generate code for writing reward result into an OpenCL buffer.
	 * @param threadPosition
	 * @param mainMethod
	 * @param loopDetectionVariable
	 */
	public void kernelWriteOutput(Method mainMethod, Expression threadPosition)
	{
		if(activeGenerator) {
			for (int i = 0; i < rewardProperties.size(); ++i) {
				CLVariable result = kernelArgs.get(i).accessElement(threadPosition);
	
				CLVariable property = propertiesStateVar.accessElement(fromString(i)).accessField("propertyState");
				CLVariable valueKnown = propertiesStateVar.accessElement(fromString(i)).accessField("valueKnown");
				//TODO: proper loop detection
				Expression succesfullComputation = createBinaryExpression(valueKnown.getSource(), ExpressionGenerator.Operator.LOR,
						generator.kernelLoopExpression());
				Expression assignment = ExpressionGenerator.createConditionalAssignment(ExpressionGenerator.addParentheses(succesfullComputation), property
						.getSource().toString(), "NAN");
	
				mainMethod.addExpression(createAssignment(result, assignment));
			}
		}
	}
	
	/**
	 * Before the state update, evaluate transition rewards for a non-synchronized update.
	 * 
	 * Args state vector structure, N reward structures
	 * 
	 * @param stateVector
	 * @param commandLabel
	 */
	private Expression callTransitionReward(CLVariable stateVector, String commandLabel)
	{
		Pair<Collection<Integer>, Method> transitionUpdate = transitionUpdateFunctions.get(commandLabel);
		List<CLValue> args = new ArrayList<>();
		args.add( stateVector.convertToPointer() );
		
		if( transitionUpdate != null ) {
			for(Integer rewardIndex : transitionUpdate.first ) {
				args.add( rewardStructuresVars.get(rewardIndex).convertToPointer() );
			}
			return transitionUpdate.second.callMethod( args );
		}
		else {
			return fromString("");
		}
	}
	
	public Expression kernelBeforeUpdate(CLVariable stateVector)
	{
		if(activeGenerator) {
			return callTransitionReward(stateVector, "");
		}
		return new Expression();
	}

	/**
	 * Before the state update, evaluate transition rewards for a synchronized update.
	 * @param stateVector
	 * @param cmd
	 */
	public Expression kernelBeforeUpdate(CLVariable stateVector, SynchronizedCommand cmd)
	{
		if(activeGenerator) {
			return callTransitionReward(stateVector, cmd.synchLabel);
		}
		return new Expression();
	}

	/**
	 * After the state update, evaluate state rewards.
	 * @param component
	 * @param stateVector
	 */
	public KernelComponent kernelAfterUpdate(CLVariable stateVector)
	{
		if(activeGenerator) {
			ExpressionList list = new ExpressionList();
			for(Map.Entry<Integer, Method> stateMethod : stateUpdateFunctions.entrySet()) {
				CLVariable rewardStructure = rewardStructuresVars.get( stateMethod.getKey() );
				list.addExpression( callStateRewardFunction(stateMethod.getValue(), stateVector, rewardStructure) );
			}
			return list;
		}
		return new Expression();
	}
	
	/**
	 * DTMC: call stateReward(svPtr, rewardPtr)
	 * CTMC: either as above or stateReward(svPtr, rewardPtr, time, updatedTime)
	 * @param method
	 * @param stateVector
	 * @param rewardStructure
	 * @return
	 */
	protected abstract Expression callStateRewardFunction(Method method, CLVariable stateVector, CLVariable rewardStructure);
	
	/**
	 * TODO: do we need a special case of checking at time 0 for CTMC?
	 * @param stateVector
	 * @param currentTime
	 */
	public Expression kernelUpdateProperties(CLVariable stateVector, CLVariable currentTime)
	{
		if(activeGenerator) {
			List<CLValue> args = new ArrayList<>();
			args.add( stateVector.convertToPointer());
			if (timedReward) {
				args.add( generator.kernelGetLocalVar(LocalVar.TIME) );	
			}
			args.add( propertiesStateVar );
			for(Integer idx : propertyMethod.second) {
				args.add( rewardStructuresVars.get(idx).convertToPointer() );
			}
			return propertyMethod.first.callMethod( args );
		}
		return new Expression();
	}

	/**
	 * @return additional methods which need to be declared and defined
	 */
	public Collection<Method> getAdditionalMethods()
	{
		if (activeGenerator) {
			return helperMethods;
		}
		return Collections.emptyList();
	}
	
	/**
	 * DTMC - always false, time step is always 1
	 * CTMC - both time and updated_time are required only for computing cumulative state rewards 
	 * @return true iff at least one function needs to know both times of entering and leaving a state
	 */
	public abstract boolean needsTimeDifference();
}
