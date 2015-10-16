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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import simulator.opencl.RuntimeConfig;
import simulator.opencl.automaton.AbstractAutomaton.AutomatonType;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.ExpressionGenerator;
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
	protected List<SamplerDouble> rewardProperties = null;

	public RewardGenerator(List<SamplerDouble> properties, RuntimeConfig config) throws KernelException
	{
		this.config = config;
		this.rewardProperties = properties;
		REWARD_REQUIRED_VARIABLES = initializeRewardRequiredVars();
		REWARD_PROPERTY_STATE_STRUCTURE = createPropertyStateType();
		REWARD_PROPERTY_STATE_ARRAY_TYPE = new ArrayType(REWARD_PROPERTY_STATE_STRUCTURE, rewardProperties.size());
		createRewardStructures();
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
	 * - cumulative CTMC - total cumulative reward, previous state & transition reward, current state reward
	 */
	protected Map<Class<? extends SamplerDouble>, String[]> initializeRewardRequiredVars() throws KernelException
	{
		Map<Class<? extends SamplerDouble>, String[]> map = new HashMap<>();

		// reachability - same for both CTMC and DTMC
		map.put(SamplerRewardReach.class, new String[] { REWARD_STRUCTURE_VAR_CUMULATIVE_TOTAL });
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
	public static RewardGenerator createGenerator(RuntimeConfig config, AutomatonType type, List<SamplerDouble> rewardProperties) throws KernelException
	{
		if (type == AutomatonType.DTMC) {
			return new RewardGeneratorDTMC(rewardProperties, config);
		} else {
			return new RewardGeneratorCTMC(rewardProperties, config);
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

	public void writeOutput(Expression threadPosition, Method mainMethod, CLVariable loopDetectionVariable)
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
}
