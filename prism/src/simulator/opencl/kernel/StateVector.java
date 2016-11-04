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
import simulator.opencl.kernel.memory.UserDefinedType;
import simulator.opencl.kernel.memory.VariableTypeInterface;

public class StateVector
{
	
	public class Translations
	{
		private Map<String, String> translations = new HashMap<>();
		
		public Translations(CLVariable sv, StructureType stateVectorType)
		{
			for (CLVariable var : stateVectorType.getFields()) {
				String name = var.varName.substring(STATE_VECTOR_PREFIX.length());
				CLVariable second = sv.accessField(var.varName);
				translations.put(name, second.varName);
			}
		}
		
		public String translate(String sourceExpr)
		{
			return translations.get(sourceExpr);
		}
		
		public Set<Map.Entry<String, String>> entrySet()
		{
			return translations.entrySet();
		}
	}

	/**
	 * StateVector field prefix.
	 */
	protected final static String STATE_VECTOR_PREFIX = "__STATE_VECTOR_";
	
	protected AbstractAutomaton model = null;
	
	protected StructureType stateVectorType = null;
	
	public StateVector(AbstractAutomaton model)
	{
		this.model = model;
		
		stateVectorType = importStateVector();
	}
	
	/**
	 * Create StateVector structure type from model's state vector.
	 */
	private StructureType importStateVector()
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
	 * @param initialState when null use default values
	 * @return
	 */
	public CLValue initStateVector(State initialState)
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
		return stateVectorType.initializeStdStructure(init);
	}

	public UserDefinedType getType()
	{
		return stateVectorType;
	}
	
	public Translations createTranslations(CLVariable stateVector)
	{
		return new Translations(stateVector, stateVectorType);
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
//	public static Map<String, String> getSVTranslations(CLVariable stateVector, StructureType stateVectorType)
//	{
//		/**
//		 * Special case - the translations are prepared for StateVector * sv,
//		 * but this one case works in main method - we have to use the StateVector instance directly.
//		 * 
//		 */
//		Map<String, String> translations = new HashMap<>();
//		for (CLVariable var : stateVectorType.getFields()) {
//			String name = var.varName.substring(STATE_VECTOR_PREFIX.length());
//			CLVariable second = stateVector.accessField(var.varName);
//			translations.put(name, second.varName);
//		}
//		
//		return translations;
//	}

	public static CLVariable accessField(CLVariable svInstance, String name)
	{
		return svInstance.accessField(translateSVField(name));
	}
	
	/**
	 * @param varName variable name
	 * @return corresponding field in StateVector structure
	 */
	private static String translateSVField(String varName)
	{
		return String.format("%s%s", STATE_VECTOR_PREFIX, varName);
	}
}
