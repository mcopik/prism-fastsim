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

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;

import simulator.gpu.automaton.AbstractAutomaton;
import simulator.gpu.automaton.AbstractAutomaton.StateVector;
import simulator.gpu.automaton.PrismVariable;
import simulator.gpu.automaton.command.Command;
import simulator.gpu.automaton.command.CommandInterface;
import simulator.gpu.automaton.command.SynchronizedCommand;
import simulator.gpu.automaton.update.Rate;
import simulator.gpu.automaton.update.Update;
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
import simulator.gpu.opencl.kernel.memory.CLVariable;
import simulator.gpu.opencl.kernel.memory.CLVariable.Location;
import simulator.gpu.opencl.kernel.memory.PointerType;
import simulator.gpu.opencl.kernel.memory.RNGType;
import simulator.gpu.opencl.kernel.memory.StdVariableType;
import simulator.gpu.opencl.kernel.memory.StdVariableType.StdType;
import simulator.gpu.opencl.kernel.memory.StructureType;
import simulator.sampler.Sampler;
import simulator.sampler.SamplerBoolean;
import simulator.sampler.SamplerNext;
import simulator.sampler.SamplerUntil;

public abstract class KernelGenerator
{
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
	 * DTMC:
	 * struct SynCmdState {
	 * 	uint8_t numberOfTransitions;
	 *  bool[] flags;
	 *  }
	 */
	protected StructureType synCmdState = null;
	protected AbstractAutomaton model = null;
	protected KernelConfig config = null;
	protected Command commands[] = null;
	protected SynchronizedCommand synCommands[] = null;
	protected List<Sampler> properties = null;
	protected CLVariable stateVector = null;
	protected List<KernelComponent> additionalDeclarations = new ArrayList<>();
	protected EnumMap<KernelMethods, Method> helperMethods = new EnumMap<>(KernelMethods.class);
	protected KernelMethod mainMethod = null;

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
		additionalDeclarations.add(PROPERTY_STATE_STRUCTURE.getDefinition());
	}

	public StructureType getSVType()
	{
		return stateVectorType;
	}

	public List<KernelComponent> getAdditionalDeclarations()
	{
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
		Integer[] init = new Integer[sv.size()];
		if (config.initialState == null) {
			PrismVariable[] vars = sv.getVars();
			for (int i = 0; i < vars.length; ++i) {
				CLVariable var = new CLVariable(new StdVariableType(vars[i]), vars[i].name);
				stateVectorType.addVariable(var);
				init[i] = new Integer(vars[i].initValue);
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
		stateVector = new CLVariable(stateVectorType, "stateVector");
		stateVector.setInitValue(stateVectorType.initializeStdStructure(init));
		additionalDeclarations.add(stateVectorType.getDefinition());
	}

	public Method createMainMethod() throws KernelException
	{
		helperMethods.put(KernelMethods.CHECK_GUARDS, createNonsynGuardsMethod());
		helperMethods.put(KernelMethods.PERFORM_UPDATE, createNonsynUpdate());
		helperMethods.put(KernelMethods.UPDATE_PROPERTIES, createPropertiesMethod());
		Method currentMethod = new KernelMethod();
		//generatorOffset for this set of threads
		CLVariable generatorOffset = new CLVariable(new StdVariableType(StdType.UINT64), "generatorOffset");
		currentMethod.addArg(generatorOffset);
		//number of simulations in this iteration
		CLVariable numberOfSimulations = new CLVariable(new StdVariableType(StdType.UINT32), "numberOfSimulations");
		currentMethod.addArg(numberOfSimulations);
		//offset in access into global array of results
		CLVariable sampleNumber = new CLVariable(new StdVariableType(StdType.UINT32), "sampleNumber");
		currentMethod.addArg(sampleNumber);
		//global arrays of results
		CLVariable samplingResults[] = new CLVariable[properties.size()];
		for (int i = 0; i < properties.size(); ++i) {
			samplingResults[i] = new CLVariable(new PointerType(new StdVariableType(StdType.CHAR)), String.format("samplingResults%d", i));
			samplingResults[i].memLocation = Location.GLOBAL;
			currentMethod.addArg(samplingResults[i]);
		}
		//global ID of thread
		CLVariable globalID = new CLVariable(new StdVariableType(StdType.UINT32), "globalID");
		globalID.setInitValue(ExpressionGenerator.assignGlobalID());
		currentMethod.addLocalVar(globalID);
		//property results
		CLVariable propertiesArray = new CLVariable(new ArrayType(PROPERTY_STATE_STRUCTURE, properties.size()), "properties");
		currentMethod.addLocalVar(propertiesArray);
		//guardsTab
		CLVariable guards = new CLVariable(new ArrayType(new StdVariableType(0, commands.length), commands.length), "guardsTab");
		currentMethod.addLocalVar(guards);
		//selection
		CLVariable selection = new CLVariable(new StdVariableType(StdType.FLOAT), "selection");
		currentMethod.addLocalVar(selection);
		mainMethodDefineLocalVars(currentMethod);
		CLVariable selectionSize = currentMethod.getLocalVar("selectionSize");
		CLVariable rng = new CLVariable(new RNGType(), "rng");
		currentMethod.addLocalVar(stateVector);
		currentMethod.registerStateVector(stateVector);
		currentMethod.addLocalVar(rng);
		currentMethod.addExpression(String.format("if(%s >= %s) {\n return;\n}\n", globalID.varName, numberOfSimulations.varName));
		currentMethod.addExpression(RNGType.initializeGenerator(rng, generatorOffset, Long.toString(config.rngOffset) + "L"));
		//ForLoop loop = new ForLoop("i", 0, config.maxPathLength);
		ForLoop loop = new ForLoop("i", 0, 15);
		Expression callCheckGuards = helperMethods.get(KernelMethods.CHECK_GUARDS).callMethod(stateVector.convertToPointer(), guards);
		loop.addExpression(ExpressionGenerator.createAssignment(selectionSize, callCheckGuards));
		loop.addExpression(RNGType.assignRandomFloat(rng, selection, selectionSize));
		loop.addExpression(new Expression("printf(\"%d %f %d %d\\n\",selectionSize,selection,stateVector.s, stateVector.d);"));
		loop.addExpression(mainMethodCallUpdate(currentMethod));
		loop.addExpression(new Expression("printf(\"%d %f %d %d\\n\",selectionSize,selection,stateVector.s, stateVector.d);"));
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
		currentMethod.addExpression(loop);
		CLVariable result = samplingResults[0].varType.accessElement(samplingResults[0], new Expression(globalID.varName));
		currentMethod.addExpression(ExpressionGenerator.createConditionalAssignment(result.varName, "globalID % 2 == 0", "true", "false"));
		//currentMethod.addExpression(new Expression("printf(\"%d\\n\",globalID);"));
		return currentMethod;
	}

	protected abstract void mainMethodDefineLocalVars(Method currentMethod) throws KernelException;

	protected abstract KernelComponent mainMethodCallUpdate(Method currentMethod);

	protected Method createNonsynGuardsMethod() throws KernelException
	{
		if (commands == null) {
			return null;
		}
		Method currentMethod = guardsMethodCreateSignature();
		//StateVector * sv
		CLVariable sv = new CLVariable(stateVector.getPointer(), "sv");
		currentMethod.addArg(sv);
		currentMethod.registerStateVector(sv);
		//bool * guardsTab
		CLVariable guards = new CLVariable(new PointerType(new StdVariableType(0, commands.length)), "guardsTab");
		currentMethod.addArg(guards);
		//counter
		CLVariable counter = new CLVariable(new StdVariableType(0, commands.length), "counter");
		counter.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(counter);
		guardsMethodCreateLocalVars(currentMethod);
		for (int i = 0; i < commands.length; ++i) {
			guardsMethodCreateCondition(currentMethod, i, commands[i].getGuard().toString().replace("=", "=="));
		}
		//signature last guard
		CLVariable position = guards.varType.accessElement(guards, new Expression(counter.varName));
		IfElse ifElse = new IfElse(ExpressionGenerator.createBasicExpression(counter, Operator.NE, Integer.toString(commands.length)));
		ifElse.addCommand(0, ExpressionGenerator.createAssignment(position, Integer.toString(commands.length)));
		currentMethod.addExpression(ifElse);
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
		CLVariable sv = new CLVariable(stateVector.getPointer(), "sv");
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
		Switch _switch = new Switch(new Expression(selection.varName));
		int switchCounter = 0;
		for (int i = 0; i < commands.length; ++i) {
			Update update = commands[i].getUpdate();
			Rate rate = new Rate(update.getRate(0));
			if (update.getActionsNumber() > 1) {
				IfElse ifElse = new IfElse(ExpressionGenerator.createBasicExpression(selectionSum, Operator.LT, rate.toString()));
				if (!update.isActionTrue(0)) {
					ifElse.addCommand(0, ExpressionGenerator.convertPrismAction(update.getAction(0)));
				}
				for (int j = 1; j < update.getActionsNumber(); ++j) {
					rate.addRate(update.getRate(j));
					ifElse.addElif(ExpressionGenerator.createBasicExpression(selectionSum, Operator.LT, rate.toString()));
					if (!update.isActionTrue(j)) {
						ifElse.addCommand(j, ExpressionGenerator.convertPrismAction(update.getAction(j)));
					}
				}
				_switch.addCase(new Expression(Integer.toString(i)));
				_switch.addCommand(switchCounter++, ifElse);
			} else {
				if (!update.isActionTrue(0)) {
					_switch.addCase(new Expression(Integer.toString(i)));
					_switch.addCommand(switchCounter++, ExpressionGenerator.convertPrismAction(update.getAction(0)));
				}
			}
		}
		currentMethod.addExpression(_switch);
		return currentMethod;
	}

	protected abstract void updateMethodPerformSelection(Method currentMethod) throws KernelException;

	protected abstract void updateMethodAdditionalArgs(Method currentMethod) throws KernelException;

	protected abstract void updateMethodLocalVars(Method currentMethod) throws KernelException;

	protected Method createPropertiesMethod() throws KernelException
	{
		Method currentMethod = new Method("updateNonsynGuards", new StdVariableType(StdType.VOID));
		currentMethod = new Method("checkProperties", new StdVariableType(StdType.BOOL));
		//StateVector * sv
		CLVariable sv = new CLVariable(stateVector.getPointer(), "sv");
		currentMethod.addArg(sv);
		currentMethod.registerStateVector(sv);
		//PropertyState * property
		CLVariable propertyState = new CLVariable(new PointerType(PROPERTY_STATE_STRUCTURE), "propertyState");
		currentMethod.addArg(propertyState);
		propertiesMethodTimeArg(currentMethod);
		CLVariable time = currentMethod.getArg("time");
		//uint counter
		CLVariable counter = new CLVariable(new StdVariableType(0, properties.size()), "counter");
		currentMethod.addLocalVar(counter);
		//bool allKnown
		CLVariable allKnown = new CLVariable(new StdVariableType(StdType.BOOL), "allKnown");
		currentMethod.addLocalVar(allKnown);
		for (int i = 0; i < properties.size(); ++i) {
			Sampler property = properties.get(i);
			CLVariable currentProperty = propertyState.varType.accessElement(propertyState, counter.getName());
			CLVariable valueKnown = currentProperty.varType.accessField(currentProperty.varName, "valueKnown");
			if (!(property instanceof SamplerBoolean)) {
				throw new KernelException("Currently rewards are not supported!");
			}
			IfElse ifElse = new IfElse(ExpressionGenerator.createNegation(valueKnown.getName()));
			ifElse.addCommand(0, ExpressionGenerator.createAssignment(allKnown, "false"));
			if (property instanceof SamplerNext) {
				ifElse.addCommand(0, propertiesMethodAddNext(currentMethod, (SamplerNext) property, currentProperty));
			} else if (property instanceof SamplerUntil) {
				ifElse.addCommand(0, propertiesMethodAddUntil(currentMethod, (SamplerUntil) property, currentProperty));
			} else {
				ifElse.addCommand(0, propertiesMethodAddBoundedUntil(currentMethod, (SamplerBoolean) property, currentProperty));
			}
			currentMethod.addExpression(ifElse);
		}
		currentMethod.addReturn(allKnown);
		return currentMethod;
	}

	protected abstract void propertiesMethodTimeArg(Method currentMethod) throws KernelException;

	protected KernelComponent propertiesMethodAddNext(Method currentMethod, SamplerNext property, CLVariable propertyVar)
	{
		CLVariable valueKnown = propertyVar.varType.accessField(propertyVar.varName, "valueKnown");
		CLVariable propertyState = propertyVar.varType.accessField(propertyVar.varName, "propertyState");
		IfElse ifElse = new IfElse(propertiesMethodCreateExpression(property.getExpression().toString()));
		ifElse.addCommand(0, ExpressionGenerator.createAssignment(propertyState, "true"));
		ifElse.addCommand(0, ExpressionGenerator.createAssignment(valueKnown, "true"));
		ifElse.addElse();
		ifElse.addCommand(1, ExpressionGenerator.createAssignment(propertyState, "false"));
		ifElse.addCommand(1, ExpressionGenerator.createAssignment(valueKnown, "true"));
		return ifElse;
	}

	protected KernelComponent propertiesMethodAddUntil(Method currentMethod, SamplerUntil property, CLVariable propertyVar)
	{

		CLVariable valueKnown = propertyVar.varType.accessField(propertyVar.varName, "valueKnown");
		CLVariable propertyState = propertyVar.varType.accessField(propertyVar.varName, "propertyState");
		IfElse ifElse = new IfElse(propertiesMethodCreateExpression(property.getRightSide().toString()));
		ifElse.addCommand(0, ExpressionGenerator.createAssignment(propertyState, "true"));
		ifElse.addCommand(0, ExpressionGenerator.createAssignment(valueKnown, "true"));
		ifElse.addElif(ExpressionGenerator.createNegation(propertiesMethodCreateExpression(property.getLeftSide().toString())));
		ifElse.addCommand(1, ExpressionGenerator.createAssignment(propertyState, "false"));
		ifElse.addCommand(1, ExpressionGenerator.createAssignment(valueKnown, "true"));
		return ifElse;
	}

	protected abstract KernelComponent propertiesMethodAddBoundedUntil(Method currentMethod, SamplerBoolean property, CLVariable propertyVar);

	protected Expression propertiesMethodCreateExpression(String expr)
	{
		String newExpr = expr.replace("=", "==").replace("&", "&&").replace("|", "||");
		if (expr.charAt(0) == ('!')) {
			return new Expression(String.format("!(%s)", newExpr.substring(1)));
		} else {
			return new Expression(newExpr);
		}

	}
}