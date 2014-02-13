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
package simulator.gpu.opencl.kernel;

import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.accessArrayElement;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.accessStructureField;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.addParentheses;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.convertPrismAction;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.convertPrismGuard;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.convertPrismProperty;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.convertPrismRate;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.createAssignment;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.createBasicExpression;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.createNegation;
import static simulator.gpu.opencl.kernel.expression.ExpressionGenerator.fromString;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import parser.ast.ExpressionLiteral;
import simulator.gpu.automaton.AbstractAutomaton;
import simulator.gpu.automaton.AbstractAutomaton.StateVector;
import simulator.gpu.automaton.PrismVariable;
import simulator.gpu.automaton.command.Command;
import simulator.gpu.automaton.command.CommandInterface;
import simulator.gpu.automaton.command.SynchronizedCommand;
import simulator.gpu.automaton.update.Rate;
import simulator.gpu.automaton.update.Update;
import simulator.gpu.opencl.kernel.expression.ComplexKernelComponent;
import simulator.gpu.opencl.kernel.expression.Expression;
import simulator.gpu.opencl.kernel.expression.ExpressionGenerator;
import simulator.gpu.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.gpu.opencl.kernel.expression.ForLoop;
import simulator.gpu.opencl.kernel.expression.IfElse;
import simulator.gpu.opencl.kernel.expression.KernelComponent;
import simulator.gpu.opencl.kernel.expression.KernelMethod;
import simulator.gpu.opencl.kernel.expression.Method;
import simulator.gpu.opencl.kernel.expression.Switch;
import simulator.gpu.opencl.kernel.memory.ArrayType;
import simulator.gpu.opencl.kernel.memory.CLValue;
import simulator.gpu.opencl.kernel.memory.CLVariable;
import simulator.gpu.opencl.kernel.memory.CLVariable.Location;
import simulator.gpu.opencl.kernel.memory.PointerType;
import simulator.gpu.opencl.kernel.memory.StdVariableType;
import simulator.gpu.opencl.kernel.memory.StdVariableType.StdType;
import simulator.gpu.opencl.kernel.memory.StructureType;
import simulator.sampler.Sampler;
import simulator.sampler.SamplerBoolean;
import simulator.sampler.SamplerNext;
import simulator.sampler.SamplerUntil;

public abstract class KernelGenerator
{
	protected CLVariable varTime = null;
	protected CLVariable varSelectionSize = null;
	protected CLVariable varStateVector = null;
	protected CLVariable varPathLength = null;
	protected CLVariable varSynSelectionSize = null;
	protected CLVariable varGuardsTab = null;
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
	protected final static String STATE_VECTOR_PREFIX = "__STATE_VECTOR_";
	/**
	 * DTMC:
	 * struct SynCmdState {
	 * 	uint8_t numberOfTransitions;
	 *  bool[] flags;
	 *  }
	 */
	protected Map<String, StructureType> synchronizedStates = null;
	protected StructureType synCmdState = null;
	protected AbstractAutomaton model = null;
	protected KernelConfig config = null;
	protected Command commands[] = null;
	protected SynchronizedCommand synCommands[] = null;
	protected List<Sampler> properties = null;
	protected List<KernelComponent> additionalDeclarations = new ArrayList<>();
	protected EnumMap<KernelMethods, Method> helperMethods = new EnumMap<>(KernelMethods.class);
	protected List<Method> synchronizedGuards = null;
	protected List<Method> synchronizedUpdates = null;
	protected KernelMethod mainMethod = null;
	protected PRNGType prngType = null;

	/**
	 * 
	 * @param model
	 * @param properties
	 * @param config
	 */
	public KernelGenerator(AbstractAutomaton model, List<Sampler> properties, KernelConfig config)
	{
		this.model = model;
		this.properties = properties;
		this.config = config;
		this.prngType = config.prngType;
		importStateVector();
		int synSize = model.synchCmdsNumber();
		int size = model.commandsNumber();
		if (synSize != 0) {
			synCommands = new SynchronizedCommand[synSize];
		}
		commands = new Command[size - synSize];
		int normalCounter = 0, synCounter = 0;
		for (int i = 0; i < size; ++i) {
			CommandInterface cmd = model.getCommand(i);
			if (!cmd.isSynchronized()) {
				commands[normalCounter++] = (Command) cmd;
			} else {
				synCommands[synCounter++] = (SynchronizedCommand) cmd;
			}
		}
		if (synSize != 0) {
			createSynchronizedStructures();
		}
		additionalDeclarations.add(PROPERTY_STATE_STRUCTURE.getDefinition());
	}

	protected abstract void createSynchronizedStructures();

	public StructureType getSVType()
	{
		return stateVectorType;
	}

	public List<KernelComponent> getAdditionalDeclarations()
	{
		if (synchronizedStates != null) {
			for (StructureType type : synchronizedStates.values()) {
				additionalDeclarations.add(type.getDefinition());
			}
		}
		if (synchronizedGuards != null) {
			for (Method method : synchronizedGuards) {
				additionalDeclarations.add(method);
			}
		}
		if (synchronizedUpdates != null) {
			for (Method method : synchronizedUpdates) {
				additionalDeclarations.add(method);
			}
		}
		return additionalDeclarations;
	}

	public Collection<Method> getHelperMethods()
	{
		return helperMethods.values();
	}

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
			//TODO: initial state
			/**
			for (int i = 0; i < initVars.length; ++i) {
				CLVariable var = new CLVariable(new StdVariableType((Integer)initVars[i]), vars[i].name);
				stateVectorType.addVariable(var);
				init[i] = new Integer(vars[i].initValue);
			}**/
		}
		return stateVectorType.initializeStdStructure(init);
	}

	public Method createMainMethod() throws KernelException
	{
		Method currentMethod = new KernelMethod();

		/**
		 * Main method arguments.
		 */
		//ARG 0: prng input
		currentMethod.addArg(prngType.getAdditionalInput());
		//ARG 1: number of simulations in this iteration
		CLVariable numberOfSimulations = new CLVariable(new StdVariableType(StdType.UINT32), "numberOfSimulations");
		currentMethod.addArg(numberOfSimulations);
		//ARG 2: offset in access into global array of results
		CLVariable sampleNumber = new CLVariable(new StdVariableType(StdType.UINT32), "sampleNumber");
		currentMethod.addArg(sampleNumber);
		//ARG 3: offset in access into global array of results
		CLVariable pathLengths = new CLVariable(new PointerType(new StdVariableType(StdType.UINT32)), "pathLengths");
		pathLengths.memLocation = Location.GLOBAL;
		currentMethod.addArg(pathLengths);
		//ARG 4..N: property results
		CLVariable[] propertyResults = new CLVariable[properties.size()];
		for (int i = 0; i < propertyResults.length; ++i) {
			propertyResults[i] = new CLVariable(new PointerType(new StdVariableType(StdType.UINT8)),
			//propertyNumber
					String.format("property%d", i));
			propertyResults[i].memLocation = Location.GLOBAL;
			currentMethod.addArg(propertyResults[i]);
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
		currentMethod.registerStateVector(varStateVector);
		//property results
		ArrayType propertiesArrayType = new ArrayType(PROPERTY_STATE_STRUCTURE, properties.size());
		varPropertiesArray = new CLVariable(propertiesArrayType, "properties");
		currentMethod.addLocalVar(varPropertiesArray);
		CLValue initValues[] = new CLValue[properties.size()];
		CLValue initValue = PROPERTY_STATE_STRUCTURE.initializeStdStructure(new Number[] { 0, 0 });
		for (int i = 0; i < initValues.length; ++i) {
			initValues[i] = initValue;
		}
		varPropertiesArray.setInitValue(propertiesArrayType.initializeArray(initValues));
		//guardsTab
		varGuardsTab = new CLVariable(new ArrayType(new StdVariableType(0, commands.length), commands.length), "guardsTab");
		currentMethod.addLocalVar(varGuardsTab);
		//TODO: guardsTab in synchronized
		//pathLength
		varPathLength = new CLVariable(new StdVariableType(StdType.UINT32), "pathLength");
		currentMethod.addLocalVar(varPathLength);
		//additional local variables, mainly selectionSize. depends on DTMC/CTMC
		mainMethodDefineLocalVars(currentMethod);

		/**
		 * Create helpers method.
		 */
		helperMethods.put(KernelMethods.CHECK_GUARDS, createNonsynGuardsMethod());
		helperMethods.put(KernelMethods.PERFORM_UPDATE, createNonsynUpdate());
		if (synCommands == null) {
			//			helperMethods.put(KernelMethods.CHECK_GUARDS_SYN, createSynGuardsMethod());
			//			helperMethods.put(KernelMethods.PERFORM_UPDATE_SYN, createSynUpdate());
		}
		helperMethods.put(KernelMethods.UPDATE_PROPERTIES, createPropertiesMethod());

		/**
		 * reject samples with globalID greater than numberOfSimulations
		 */
		currentMethod.addExpression(String.format("if(%s > %s) {\n return;\n}\n", globalID.varName, numberOfSimulations.varName));
		/**
		 * initialize generator
		 */
		currentMethod.addExpression(prngType.initializeGenerator());
		mainMethodFirstUpdateProperties(currentMethod);
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
				IfElse ifElse = new IfElse(createBasicExpression(condition, Operator.EQ, fromString(0)));
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
		Expression callCheckGuards = helperMethods.get(KernelMethods.CHECK_GUARDS).callMethod(
		//(stateVector,guardsTab)
				varStateVector.convertToPointer(), varGuardsTab);
		loop.addExpression(createAssignment(varSelectionSize, callCheckGuards));
		if (synCommands != null) {
			//TODO: call synchronized check guards
		}
		/**
		 * if(selectionSize + synSelectionSize == 0) -> deadlock, break
		 */
		Expression sum = null;
		if (varSynSelectionSize != null && varSelectionSize != null) {
			sum = createBasicExpression(varSelectionSize.getSource(), Operator.ADD, varSynSelectionSize.getSource());
		} else if (varSynSelectionSize != null) {
			sum = varSynSelectionSize.getSource();
		} else {
			sum = varSelectionSize.getSource();
		}
		IfElse deadlockState = new IfElse(createBasicExpression(sum, Operator.EQ, fromString(0)));
		deadlockState.addExpression(new Expression("break;\n"));
		loop.addExpression(deadlockState);
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
		mainMethodUpdateProperties(loop);
		//loop.addExpression(new Expression("if(get_global_id(0) < 10)printf(\"%d %f %f \",get_global_id(0),time,updatedTime);\n"));
		/**
		 * call update method; 
		 * most complex case - both nonsyn and synchronized updates
		 */
		if (varSelectionSize != null & varSynSelectionSize != null) {
			mainMethodCallBothUpdates(loop);
		}
		/**
		 * only synchronized updates
		 */
		else if (varSelectionSize == null) {
			mainMethodCallSynUpdate(loop);
		}
		/**
		 * only nonsyn updates
		 */
		else {
			mainMethodCallNonsynUpdate(loop);
		}
		/**
		 * DTMC:
		 * int transitions = 0;
		 * CTMC:
		 * float transitions = 0.0f;
		 * local uint8_t flags[NUMBER];
		 * PropertyState properties[];
		 * for(int i = 0;i < maxPathLength;++i) {
		 * 		transitions = checkGuards(sv,flags);
		 * 		float selection = rng.random(transitions);
		 * 		DTMC:
		 * 		update(sv,selection,transitions);
		 * 		CTMC:
		 * 		update(sv,selection,flags);
		 * 		updateProperties(sv,properties);
		 * }
		 */
		/**
		 * For CTMC&bounded until -> update current time.
		 */
		mainMethodUpdateTimeAfter(currentMethod, loop);
		currentMethod.addExpression(loop);
		//sampleNumber + globalID
		Expression position = createBasicExpression(globalID.getSource(), Operator.ADD, sampleNumber.getSource());
		CLVariable pathLength = pathLengths.varType.accessElement(pathLengths, position);
		currentMethod.addExpression(createAssignment(pathLength, varPathLength));
		for (int i = 0; i < properties.size(); ++i) {
			CLVariable result = accessArrayElement(propertyResults[i], position);
			CLVariable property = accessArrayElement(varPropertiesArray, fromString(i)).accessField("propertyState");
			currentMethod.addExpression(createAssignment(result, property));
		}
		currentMethod.addExpression(prngType.deinitializeGenerator());

		if (prngType.getAdditionalDefinitions() != null) {
			additionalDeclarations.addAll(prngType.getAdditionalDefinitions());
		}
		currentMethod.addInclude(prngType.getIncludes());
		return currentMethod;
	}

	protected abstract int mainMethodRandomsPerIteration();

	protected abstract void mainMethodDefineLocalVars(Method currentMethod) throws KernelException;

	protected void mainMethodCallBothUpdates(ComplexKernelComponent parent)
	{
		//selection
		CLVariable selection = new CLVariable(new StdVariableType(StdType.FLOAT), "selection");
		Expression sum = createBasicExpression(varSelectionSize.getSource(), Operator.ADD, varSynSelectionSize.getSource());
		addParentheses(sum);
		selection.setInitValue(config.prngType.getRandomFloat(fromString(0), sum));
		parent.addExpression(selection.getDefinition());
		Expression condition = createBasicExpression(selection.getSource(), Operator.LT, varSelectionSize.getSource());
		IfElse ifElse = new IfElse(condition);
		/**
		 * if(selection < selectionSize)
		 * callNonsynUpdate(..)
		 */
		Method update = helperMethods.get(KernelMethods.PERFORM_UPDATE);
		ifElse.addExpression(update.callMethod(
		//stateVector
				varStateVector.convertToPointer(),
				//non-synchronized guards tab
				varGuardsTab,
				//select 
				selection));
		/**
		 * else
		 * callSynUpdate()
		 */
		//TODO: call synchronized update
		parent.addExpression(ifElse);
	}

	protected void mainMethodCallSynUpdate(ComplexKernelComponent parent)
	{
		//TODO: call synchronized update
	}

	protected abstract void mainMethodCallNonsynUpdate(ComplexKernelComponent parent);

	protected abstract void mainMethodFirstUpdateProperties(ComplexKernelComponent parent);

	protected abstract void mainMethodUpdateProperties(ComplexKernelComponent currentMethod);

	protected abstract void mainMethodUpdateTimeBefore(Method currentMethod, ComplexKernelComponent parent);

	protected abstract void mainMethodUpdateTimeAfter(Method currentMethod, ComplexKernelComponent parent);

	protected Method createNonsynGuardsMethod() throws KernelException
	{
		if (commands == null) {
			return null;
		}
		Method currentMethod = guardsMethodCreateSignature();
		//StateVector * sv
		CLVariable sv = new CLVariable(varStateVector.getPointer(), "sv");
		currentMethod.addArg(sv);
		currentMethod.registerStateVector(sv);
		//bool * guardsTab
		CLVariable guards = new CLVariable(varGuardsTab.getPointer(), "guardsTab");
		currentMethod.addArg(guards);
		//counter
		CLVariable counter = new CLVariable(new StdVariableType(0, commands.length), "counter");
		counter.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(counter);
		PrismVariable[] vars = model.getStateVector().getVars();
		guardsMethodCreateLocalVars(currentMethod);
		for (int i = 0; i < commands.length; ++i) {
			guardsMethodCreateCondition(currentMethod, i, convertPrismGuard(vars, commands[i].getGuard().toString()));
		}
		//signature last guard
		CLVariable position = guards.varType.accessElement(guards, new Expression(counter.varName));
		IfElse ifElse = new IfElse(createBasicExpression(counter.getSource(), Operator.NE, fromString(commands.length)));
		ifElse.addExpression(0, createAssignment(position, fromString(commands.length)));
		currentMethod.addExpression(ifElse);
		//currentMethod.addExpression(new Expression("guardsTab[0] = s; counter++;"));
		guardsMethodReturnValue(currentMethod);
		return currentMethod;
	}

	protected abstract Method guardsMethodCreateSignature();

	protected abstract void guardsMethodCreateLocalVars(Method currentMethod) throws KernelException;

	protected abstract void guardsMethodCreateCondition(Method currentMethod, int position, String guard);

	protected abstract void guardsMethodReturnValue(Method currentMethod);

	protected Method createNonsynUpdate() throws KernelException
	{
		if (commands == null) {
			return null;
		}
		Method currentMethod = new Method("updateNonsynGuards", new StdVariableType(StdType.VOID));
		//StateVector * sv
		CLVariable sv = new CLVariable(varStateVector.getPointer(), "sv");
		currentMethod.addArg(sv);
		currentMethod.registerStateVector(sv);
		//bool * guardsTab
		CLVariable guards = new CLVariable(new PointerType(new StdVariableType(0, commands.length)), "guardsTab");
		currentMethod.addArg(guards);
		//float sum
		CLVariable selectionSum = new CLVariable(new StdVariableType(StdType.FLOAT), "selectionSum");
		selectionSum.setInitValue(StdVariableType.initialize(0.0f));
		currentMethod.addArg(selectionSum);
		CLVariable selection = new CLVariable(new StdVariableType(0, commands.length), "selection");
		selection.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(selection);
		updateMethodAdditionalArgs(currentMethod);
		updateMethodLocalVars(currentMethod);
		updateMethodPerformSelection(currentMethod);
		CLVariable guardsTabSelection = accessArrayElement(varGuardsTab, selection.getSource());
		Switch _switch = new Switch(guardsTabSelection.getSource());
		int switchCounter = 0;
		PrismVariable[] vars = model.getStateVector().getVars();
		for (int i = 0; i < commands.length; ++i) {
			Update update = commands[i].getUpdate();
			Rate rate = new Rate(update.getRate(0));
			if (update.getActionsNumber() > 1) {
				IfElse ifElse = new IfElse(createBasicExpression(selectionSum.getSource(), Operator.LT, fromString(convertPrismRate(vars, rate))));
				if (!update.isActionTrue(0)) {
					ifElse.addExpression(0, convertPrismAction(update.getAction(0)));
				}
				for (int j = 1; j < update.getActionsNumber(); ++j) {
					rate.addRate(update.getRate(j));
					ifElse.addElif(createBasicExpression(selectionSum.getSource(), Operator.LT, fromString(convertPrismRate(vars, rate))));
					if (!update.isActionTrue(j)) {
						ifElse.addExpression(j, convertPrismAction(update.getAction(j)));
					}
				}
				_switch.addCase(new Expression(Integer.toString(i)));
				_switch.addCommand(switchCounter++, ifElse);
			} else {
				if (!update.isActionTrue(0)) {
					_switch.addCase(new Expression(Integer.toString(i)));
					_switch.addCommand(switchCounter++, convertPrismAction(update.getAction(0)));
				}
			}
		}
		currentMethod.addExpression(_switch);
		//		currentMethod
		//				.addExpression(new Expression(
		//						"if(get_global_id(0) < 10)printf(\"%f %d %d %d %d %d\\n\",selectionSum,selection,numberOfCommands,(*sv).__STATE_VECTOR_q),(*sv).__STATE_VECTOR_s2),(*sv).__STATE_VECTOR_s2);\n"));
		return currentMethod;
	}

	protected abstract void updateMethodPerformSelection(Method currentMethod) throws KernelException;

	protected abstract void updateMethodAdditionalArgs(Method currentMethod) throws KernelException;

	protected abstract void updateMethodLocalVars(Method currentMethod) throws KernelException;

	protected Method createPropertiesMethod() throws KernelException
	{
		Method currentMethod = new Method("checkProperties", new StdVariableType(StdType.BOOL));
		//StateVector * sv
		CLVariable sv = new CLVariable(varStateVector.getPointer(), "sv");
		currentMethod.addArg(sv);
		currentMethod.registerStateVector(sv);
		//PropertyState * property
		CLVariable propertyState = new CLVariable(new PointerType(PROPERTY_STATE_STRUCTURE), "propertyState");
		currentMethod.addArg(propertyState);
		propertiesMethodTimeArg(currentMethod);
		//uint counter
		CLVariable counter = new CLVariable(new StdVariableType(0, properties.size()), "counter");
		currentMethod.addLocalVar(counter);
		//bool allKnown
		CLVariable allKnown = new CLVariable(new StdVariableType(StdType.BOOL), "allKnown");
		allKnown.setInitValue(StdVariableType.initialize(1));
		currentMethod.addLocalVar(allKnown);
		for (int i = 0; i < properties.size(); ++i) {
			Sampler property = properties.get(i);
			CLVariable currentProperty = accessArrayElement(propertyState, counter.getName());
			CLVariable valueKnown = accessStructureField(currentProperty, "valueKnown");
			if (!(property instanceof SamplerBoolean)) {
				throw new KernelException("Currently rewards are not supported!");
			}
			IfElse ifElse = new IfElse(createNegation(valueKnown.getName()));
			ifElse.addExpression(0, createAssignment(allKnown, fromString("false")));
			if (property instanceof SamplerNext) {
				propertiesMethodAddNext(ifElse, (SamplerNext) property, currentProperty);
			} else if (property instanceof SamplerUntil) {
				propertiesMethodAddUntil(ifElse, (SamplerUntil) property, currentProperty);
			} else {
				propertiesMethodAddBoundedUntil(currentMethod, ifElse, (SamplerBoolean) property, currentProperty);
			}
			ifElse.addExpression(0, createAssignment(allKnown, valueKnown));
			currentMethod.addExpression(ifElse);
		}
		currentMethod.addReturn(allKnown);
		return currentMethod;
	}

	protected abstract void propertiesMethodTimeArg(Method currentMethod) throws KernelException;

	protected void propertiesMethodAddNext(ComplexKernelComponent parent, SamplerNext property, CLVariable propertyVar)
	{
		IfElse ifElse = createPropertyCondition(propertyVar, false, property.getExpression().toString(), true);
		createPropertyCondition(ifElse, propertyVar, false, null, false);
		parent.addExpression(ifElse);
	}

	protected void propertiesMethodAddUntil(ComplexKernelComponent parent, SamplerUntil property, CLVariable propertyVar)
	{
		IfElse ifElse = createPropertyCondition(propertyVar, false, property.getRightSide().toString(), true);
		/**
		 * in F/G it is true
		 */
		if (!(property.getLeftSide() instanceof ExpressionLiteral)) {
			createPropertyCondition(ifElse, propertyVar, true, property.getLeftSide().toString(), false);
		}
		parent.addExpression(ifElse);
	}

	protected abstract void propertiesMethodAddBoundedUntil(Method currentMethod, ComplexKernelComponent parent, SamplerBoolean property, CLVariable propertyVar);

	protected IfElse createPropertyCondition(CLVariable propertyVar, boolean negation, String condition, boolean propertyValue)
	{
		IfElse ifElse = null;
		if (!negation) {
			ifElse = new IfElse(convertPrismProperty(condition));
		} else {
			ifElse = new IfElse(createNegation(convertPrismProperty(condition)));
		}
		CLVariable valueKnown = accessStructureField(propertyVar, "valueKnown");
		CLVariable propertyState = accessStructureField(propertyVar, "propertyState");
		if (propertyValue) {
			ifElse.addExpression(0, createAssignment(propertyState, fromString("true")));
		} else {
			ifElse.addExpression(0, createAssignment(propertyState, fromString("false")));
		}
		ifElse.addExpression(0, createAssignment(valueKnown, fromString("true")));
		return ifElse;
	}

	protected void createPropertyCondition(IfElse ifElse, CLVariable propertyVar, boolean negation, String condition, boolean propertyValue)
	{
		if (condition != null) {
			if (!negation) {
				ifElse.addElif(convertPrismProperty(condition));
			} else {
				ifElse.addElif(createNegation(convertPrismProperty(condition)));
			}
		} else {
			ifElse.addElse();
		}
		CLVariable valueKnown = accessStructureField(propertyVar, "valueKnown");
		CLVariable propertyState = accessStructureField(propertyVar, "propertyState");
		if (propertyValue) {
			ifElse.addExpression(ifElse.size() - 1, createAssignment(propertyState, fromString("true")));
		} else {
			ifElse.addExpression(ifElse.size() - 1, createAssignment(propertyState, fromString("false")));
		}
		ifElse.addExpression(ifElse.size() - 1, createAssignment(valueKnown, fromString("true")));
	}

	public String translateSVField(String varName)
	{
		return String.format("%s%s", STATE_VECTOR_PREFIX, varName);
	}
}