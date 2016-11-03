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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import parser.State;
import simulator.opencl.automaton.AbstractAutomaton;
import simulator.opencl.automaton.PrismVariable;
import simulator.opencl.automaton.update.Action;
import simulator.opencl.kernel.KernelGenerator.LocalVar;
import simulator.opencl.kernel.expression.IfElse;
import simulator.opencl.kernel.expression.Switch;
import simulator.opencl.kernel.memory.CLValue;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.opencl.kernel.memory.StructureType;

public class StateVector
{
	/**
	 * StateVector field prefix.
	 */
	protected final static String STATE_VECTOR_PREFIX = "__STATE_VECTOR_";

	/**
	 * Prefix of variable used to save value of StateVector field.
	 */
	protected final static String SAVED_VARIABLE_PREFIX = "SAVED_VARIABLE__";
	
	/**
	 * Create StateVector structure type from model's state vector.
	 */
	public static StructureType importStateVector(AbstractAutomaton model)
	{
		AbstractAutomaton.StateVector sv = model.getStateVector();
		StructureType stateVectorType = new StructureType("StateVector");
		PrismVariable[] vars = sv.getVars();
		for (int i = 0; i < vars.length; ++i) {
			CLVariable var = new CLVariable(new StdVariableType(vars[i]), translateSVField(vars[i].name));
			stateVectorType.addVariable(var);
		}
		return stateVectorType;
	}

	/**
	 * Initialize state vector from initial state declared in model or provided by user.  
	 * @return structure initialization value
	 */
	public static CLValue initStateVector(AbstractAutomaton model, StructureType svType, State initialState)
	{
		AbstractAutomaton.StateVector sv = model.getStateVector();
		Integer[] init = new Integer[sv.size()];
		if (initialState == null) {
			PrismVariable[] vars = sv.getVars();
			for (int i = 0; i < vars.length; ++i) {
				init[i] = vars[i].initValue;
			}
		} else {
			Object[] initVars = initialState.varValues;
			for (int i = 0; i < initVars.length; ++i) {
				if (initVars[i] instanceof Integer) {
					init[i] = (Integer) initVars[i];
				} else {
					init[i] = new Integer(((Boolean) initVars[i]) ? 1 : 0);
				}
			}
		}
		return svType.initializeStdStructure(init);
	}

	
	public static void createTranslations(CLVariable sv, StructureType stateVectorType,
			Map<String, String> translations)
	{
		for (CLVariable var : stateVectorType.getFields()) {
			String name = var.varName.substring(STATE_VECTOR_PREFIX.length());
			CLVariable second = sv.accessField(var.varName);
			translations.put(name, second.varName);
		}
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
	public static void addSavedVariables(CLVariable stateVector, IfElse ifElse, int conditionalNumber, Action action, Map<String, CLVariable> variableSources,
			Map<String, CLVariable> savedVariables)
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
			if (variableSources != null) {
				CLVariable source = variableSources.get(var.name);
				if (source != null) {
					flag = true;
					savedVar.setInitValue(source);
				}
			}

			// not using additional source - just initialize variable from state vector
			if (!flag) {
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
	public static void addSavedVariables(CLVariable stateVector, Switch _switch, int conditionalNumber, Action action, Map<String, CLVariable> variableSources,
			Map<String, CLVariable> savedVariables)
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
			if (variableSources != null) {
				CLVariable source = variableSources.get(var.name);
				if (source != null) {
					flag = true;
					savedVar.setInitValue(source);
				}
			}

			// not using additional source - just initialize variable from state vector
			if (!flag) {
				savedVar.setInitValue(stateVector.accessField(translateSVField(var.name)));
			}

			_switch.addExpression(conditionalNumber, savedVar.getDefinition());

			savedVariables.put(var.name, savedVar);
		}
	}

	/**
	 * Returns a special case of map returned from
	 * @see getSVPtrTranslations()
	 * 
	 * FROM:
	 * PRISM variable name NAME
	 * TO:
	 * - access in state vector instance in kernel method
	 * - field name begins with STATE_VECTOR_PREFIX
	 * - field name ends with NAME
	 * @return
	 */
	public static Map<String, String> getSVTranslations(CLVariable stateVector, StructureType stateVectorType)
	{
		/**
		 * Special case - the translations are prepared for StateVector * sv,
		 * but this one case works in main method - we have to use the StateVector instance directly.
		 * 
		 */
		Map<String, String> translations = new HashMap<>();
		for (CLVariable var : stateVectorType.getFields()) {
			String name = var.varName.substring(STATE_VECTOR_PREFIX.length());
			CLVariable second = stateVector.accessField(var.varName);
			translations.put(name, second.varName);
		}
		
		return translations;
	}

	/**
	 * @param varName variable name
	 * @return corresponding field in StateVector structure
	 */
	public static String translateSVField(String varName)
	{
		return String.format("%s%s", STATE_VECTOR_PREFIX, varName);
	}

	/**
	 * @param varName variable name
	 * @return corresponding variable to "save" previous value from StateVector
	 */
	public static String translateSavedVariable(String varName)
	{
		return String.format("%s%s", SAVED_VARIABLE_PREFIX, varName);
	}
}
