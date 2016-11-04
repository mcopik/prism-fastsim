package simulator.opencl.kernel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import simulator.opencl.automaton.PrismVariable;
import simulator.opencl.automaton.update.Action;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.IfElse;
import simulator.opencl.kernel.expression.Include;
import simulator.opencl.kernel.expression.Switch;
import simulator.opencl.kernel.memory.CLValue;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.PointerType;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.opencl.kernel.memory.StructureType;
import simulator.opencl.kernel.memory.UserDefinedType;
import simulator.opencl.kernel.memory.VariableTypeInterface;

public class SavedVariables extends StructureType
{
	static public class Translations
	{
		private boolean isActive = false;
		
		/**
		 * Save variables, not string, to allow modification for local copies.
		 * @see createTranslations in parent class for details
		 */
		private Map<String, CLVariable> translations = null;
		
		private Translations()
		{
		}
		
		private Translations(Map<String, CLVariable> translations)
		{
			this.translations = new HashMap<>(translations);
			isActive = true;
		}
		
		private Translations(CLVariable instance, StructureType stateVectorType)
		{
			this.translations = new HashMap<>();
			for (CLVariable var : stateVectorType.getFields()) {
				String name = var.varName.substring(SavedVariables.SAVED_VARIABLE_PREFIX.length());
				translations.put(name, instance.accessField(var.varName));
			}
			isActive = translations.size() > 0;
		}
		
		private void add(String name, CLVariable var)
		{
			if(isActive) {
				translations.put(name, var);
			}
		}
		
		public CLVariable get(String sourceExpr)
		{
			if(isActive) {
				return translations.get(sourceExpr);
			}
			return null;
		}
		
		public boolean hasTranslation(String sourceExpr)
		{
			if(isActive) {
				return translations.containsKey(sourceExpr);
			}
			return false;
		}
		
		public Set<Map.Entry<String, CLVariable>> entrySet()
		{
			if(isActive) {
				return translations.entrySet();
			}
			return Collections.emptySet();
		}
		
		public void clear()
		{
			if(isActive) {
				translations.clear();
			}
		}
		
		public Translations copy()
		{
			if(isActive) {
				return new Translations(translations);
			}
			return this;
		}
		
		public static Translations createEmpty()
		{
			return new Translations(new HashMap<String,CLVariable>());
		}
	}
	
	/**
	 * Prefix of variable used to save value of StateVector field.
	 */
	protected final static String SAVED_VARIABLE_PREFIX = "SAVED_VARIABLE__";
	
	private Set<PrismVariable> varsToSave = null;
	
	private boolean isActive = false;
	
	public SavedVariables(Set<PrismVariable> varsToSave, String suffix)
	{
		super( String.format("SAVE_VARIABLES_SYNCHR_%s", suffix) );
		if (!varsToSave.isEmpty()) {
			isActive = true;
			this.varsToSave = varsToSave;
			for (PrismVariable var : varsToSave) {
				CLVariable structureVar = new CLVariable(
						new StdVariableType(var),
						translateSavedVariable(var.name)
						);
				addVariable(structureVar);
			}
		}
	}
	
	public CLVariable createInstance(CLVariable stateVector, String name)
	{	
		if(isActive) {
			CLVariable savedVarsInstance = new CLVariable(this, name);
			CLValue[] init = new CLValue[varsToSave.size()];
			int i = 0;
			for (PrismVariable var : varsToSave) {
				init[i++] = StateVector.accessField(stateVector, var.name);
			}
			savedVarsInstance.setInitValue(initializeStdStructure(init));
			
			return savedVarsInstance;
		}
		return null;
	}
	
	public CLVariable createPointer(String name)
	{
		if(isActive) {
			return new CLVariable(new PointerType(this), name);
		}
		return null;
	}
	
	/**
	 * @param instance
	 * @return translations to access an instance of SavedVariables
	 */
	public SavedVariables.Translations createTranslations(CLVariable instance)
	{
		if(isActive) {
			return new SavedVariables.Translations(instance, this);
		}
		return new SavedVariables.Translations();
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
//	public static void addSavedVariables(CLVariable stateVector, IfElse ifElse, int conditionalNumber, Action action, Map<String, CLVariable> variableSources,
//			Map<String, CLVariable> savedVariables)
//	{
//		//clear previous adds
//		savedVariables.clear();
//		Set<PrismVariable> varsToSave = action.variablesCopiedBeforeUpdate();
//
//		//for every saved variable, create a local variable in C
//		for (PrismVariable var : varsToSave) {
//			CLVariable savedVar = new CLVariable(new StdVariableType(var), translateSavedVariable(var.name));
//
//			// are there any other sources of variables rather than original state vector?
//			// this additional parameter is used in synchronized update, where one may want to
//			// initialize 'saved' variable with the value in oldStateVector
//			// (unnecessary usage of variables may happen, but OpenCL compiler should eliminate that)
//			boolean flag = false;
//			if (variableSources != null) {
//				CLVariable source = variableSources.get(var.name);
//				if (source != null) {
//					flag = true;
//					savedVar.setInitValue(source);
//				}
//			}
//
//			// not using additional source - just initialize variable from state vector
//			if (!flag) {
//				savedVar.setInitValue(stateVector.accessField(translateSVField(var.name)));
//			}
//			ifElse.addExpression(conditionalNumber, savedVar.getDefinition());
//
//			savedVariables.put(var.name, savedVar);
//		}
//	}
	
	/**
	 * 
	 * @param stateVector
	 * @param ifElse
	 * @param conditionalNumber
	 * @param action
	 * @param variableSources
	 * @return
	 */
	public static Collection<CLVariable> createTranslations(CLVariable stateVector, Action action,
			SavedVariables.Translations variableSources, SavedVariables.Translations localCopy)
	{
		Set<PrismVariable> varsToSave = action.variablesCopiedBeforeUpdate();
		if( !varsToSave.isEmpty() ) {
			//variables saved in a single update
			//Map<String, CLVariable> varsSaved = new HashMap<>();
			List<CLVariable> localVars = new ArrayList<>();
	
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
					savedVar.setInitValue(StateVector.accessField(stateVector, var.name));
				}
				localVars.add(savedVar);
				localCopy.add(var.name, savedVar);
			}
			return localVars;
		}
		return Collections.emptyList();
	}
	
	public static Collection<CLVariable> createTranslations(CLVariable stateVector, Action action, SavedVariables.Translations localCopy)
	{
		return createTranslations(stateVector, action, null, localCopy);
	}
	
	@Override
	public Expression getDefinition()
	{
		if(isActive) {
			return super.getDefinition();
		}
		return new Expression();
	}

	/**
	 * @param varName variable name
	 * @return corresponding variable to "save" previous value from StateVector
	 */
	private static String translateSavedVariable(String varName)
	{
		return String.format("%s%s", SAVED_VARIABLE_PREFIX, varName);
	}
}
