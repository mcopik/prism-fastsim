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
import java.util.List;

import prism.Preconditions;
import simulator.gpu.automaton.AbstractAutomaton;
import simulator.gpu.automaton.AbstractAutomaton.StateVector;
import simulator.gpu.automaton.PrismVariable;
import simulator.gpu.automaton.command.Command;
import simulator.gpu.automaton.command.CommandInterface;
import simulator.gpu.automaton.command.SynchronizedCommand;
import simulator.gpu.opencl.kernel.expression.Expression;
import simulator.gpu.opencl.kernel.expression.ExpressionGenerator;
import simulator.gpu.opencl.kernel.expression.ForLoop;
import simulator.gpu.opencl.kernel.expression.IfElse;
import simulator.gpu.opencl.kernel.expression.Include;
import simulator.gpu.opencl.kernel.expression.KernelMethod;
import simulator.gpu.opencl.kernel.expression.MemoryTranslatorVisitor;
import simulator.gpu.opencl.kernel.expression.Method;
import simulator.gpu.opencl.kernel.memory.CLVariable;
import simulator.gpu.opencl.kernel.memory.PointerType;
import simulator.gpu.opencl.kernel.memory.RNGType;
import simulator.gpu.opencl.kernel.memory.StdVariableType;
import simulator.gpu.opencl.kernel.memory.StdVariableType.StdType;
import simulator.gpu.opencl.kernel.memory.StructureType;
import simulator.gpu.property.Property;

public class Kernel
{
	/**
	 * BasicDebug_Kernel.cl from AMDAPP's samples.
	 */
	public final static String TEST_KERNEL = "__kernel void main() { \n" + "uint globalID = get_global_id(0); \n" + "uint groupID = get_group_id(0);  \n"
			+ "uint localID = get_local_id(0); \n" + "printf(\"the global ID of this thread is : %d\\n\",globalID); \n" + "}";

	/**
	 * Input - three objects which determine kernel's source.
	 */
	private KernelConfig config = null;
	private AbstractAutomaton model = null;
	private Command commands[] = null;
	private SynchronizedCommand synCommands[] = null;
	private Property[] properties = null;
	/**
	 * Source components.
	 */
	private String kernelSource = null;
	private List<Include> includes = new ArrayList<>();
	/**
	 * struct StateVector {
	 * 	each_variable;
	 * }
	 */
	private StructureType stateVectorType = null;
	/**
	 * struct PropertyState {
	 *  bool value;
	 *  bool valueKnown;
	 * }
	 */
	private StructureType propertyType = null;
	/**
	 * DTMC:
	 * struct SynCmdState {
	 * 	uint8_t numberOfTransitions;
	 *  bool[] flags;
	 *  }
	 */
	private StructureType synCmdState = null;
	/**
	 * Main kernel method.
	 * INPUT:
	 * RNG offset
	 * number of samples
	 * OUTPUT:
	 * global array of properties values
	 */
	private KernelMethod mainMethod = null;

	private enum MethodIndices {
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
		 * void performUpdate(StateVector * sv, int updateSelection);
		 * CTMC:
		 * void performUpdate(StateVector * sv, float sumSelection,bool * guardsTab);
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
		 * bool updateProperties(StateVector * sv,PropertyState * prop,int time);
		 * CTMC:
		 * bool updateProperties(StateVector * sv,PropertyState * prop,float time);
		 */
		UPDATE_PROPERTIES(4);
		public final int indice;

		private MethodIndices(int indice)
		{
			this.indice = indice;
		}

		public final static int SIZE = MethodIndices.values().length;
	}

	private Method helperMethods[] = new Method[MethodIndices.SIZE];
	private CLVariable stateVector = null;
	/**
	 * For CTMC - float. For DTMC - depends on MAX_ITERATIONS.
	 */
	private CLVariable timeCounter = null;

	private List<Expression> globalDeclarations = new ArrayList<>();

	public final static String KERNEL_TYPEDEFS = "typedef char int8_t;\n" + "typedef unsigned char uint8_t;\n" + "typedef unsigned short uint16_t;\n"
			+ "typedef short int16_t;\n" + "typedef unsigned int uint32_t;\n" + "typedef int int32_t;\n" + "typedef long int64_t;\n"
			+ "typedef unsigned long uint64_t;\n";

	public Kernel(KernelConfig config, AbstractAutomaton model, Property[] properties) throws KernelException
	{
		this.config = config;
		this.model = model;
		this.properties = properties;
		processInput();
		try {
			importStateVector();
			helperMethods[MethodIndices.CHECK_GUARDS.indice] = createGuardsMethodWithCounting();
			createMainMethod();
		} catch (KernelException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		generateSource();
	}

	public Kernel(String source)
	{
		kernelSource = source;
	}

	public static Kernel createTestKernel()
	{
		return new Kernel(TEST_KERNEL);
	}

	private void processInput()
	{
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
	}

	private Method createGuardsMethodWithCounting() throws KernelException
	{
		Preconditions.checkCondition(commands != null || synCommands != null, "Can not create helper methods before processing input data!");
		//no non-synchronized commands
		if (commands == null) {
			return null;
		}
		Method method = new Method("checkGuards", new StdVariableType(0, commands.length - 1));
		//StateVector * sv
		CLVariable sv = new CLVariable(stateVector.getPointer(), "sv");
		method.addArg(sv);
		method.registerStateVector(sv);
		//bool * guardsTab
		CLVariable guards = new CLVariable(new PointerType(new StdVariableType(StdType.BOOL)), "guardsTab");
		method.addArg(guards);
		//counter
		CLVariable counter = new CLVariable(new StdVariableType(0, commands.length - 1), "counter");
		counter.setInitValue(StdVariableType.initialize(0));
		method.addLocalVar(counter);
		for (int i = 0; i < commands.length; ++i) {
			CLVariable position = guards.varType.accessElement(guards.varName, ExpressionGenerator.postIncrement(counter));
			IfElse ifElse = new IfElse(new Expression(commands[i].getGuard().toString().replace("=", "==")));
			ifElse.addCommand(0, ExpressionGenerator.createAssignment(position, "true"));
			method.addExpression(ifElse);
			/*method.addExpression(ExpressionGenerator.createConditionalAssignment(
			//i-th element in array
					ExpressionGenerator.accessArrayElement(guards, i),
					//guard - Prism uses '=' as comparison operator
					commands[i].getGuard().toString().replace("=", "=="), "true", "false"));
			*/
		}
		method.addReturn(counter);
		return method;
	}

	private void createUpdateMethod() throws KernelException
	{
		Preconditions.checkCondition(commands != null || synCommands != null, "Can not create helper methods before processing input data!");
		//no non-synchronized commands
		if (commands == null) {
			return;
		}
		Method method = new Method("checkGuards", new StdVariableType(0, commands.length - 1));
		//StateVector * sv
		CLVariable sv = new CLVariable(stateVector.getPointer(), "sv");
		method.addArg(sv);
		method.registerStateVector(sv);
		//selection
		CLVariable guards = new CLVariable(new PointerType(new StdVariableType(StdType.BOOL)), "guardsTab");
		//bool * guardsTab
		method.addArg(guards);
		for (int i = 0; i < commands.length; ++i) {
			method.addExpression(ExpressionGenerator.createConditionalAssignment(
			//i-th element in array
					ExpressionGenerator.accessArrayElement(guards, i),
					//guard - Prism uses '=' as comparison operator
					commands[i].getGuard().toString().replace("=", "=="), "true", "false"));

		}
		helperMethods[MethodIndices.CHECK_GUARDS.indice] = method;
	}

	private void importStateVector()
	{
		StateVector sv = model.getStateVector();
		stateVectorType = new StructureType("StateVector");
		Integer[] init = new Integer[sv.size()];
		PrismVariable[] vars = sv.getVars();
		for (int i = 0; i < vars.length; ++i) {
			CLVariable var = new CLVariable(new StdVariableType(vars[i]), vars[i].name);
			stateVectorType.addVariable(var);
			init[i] = new Integer(vars[i].initValue);
		}
		stateVector = new CLVariable(stateVectorType, "stateVector");
		stateVector.setInitValue(stateVectorType.initializeStdStructure(init));
		globalDeclarations.add(stateVectorType.getDefinition());
	}

	private void createMainMethod() throws KernelException
	{
		mainMethod = new KernelMethod();
		mainMethod.addLocalVar(stateVector);
		ForLoop loop = new ForLoop("i", 0, config.maxPathLength);
		mainMethod.addExpression(loop);
		CLVariable rng = new CLVariable(new RNGType(), "rng");
		mainMethod.addLocalVar(rng);
		CLVariable temp = new CLVariable(new StdVariableType(StdType.INT8), "temp");
		mainMethod.addLocalVar(temp);
		temp.setInitValue(StdVariableType.initialize(0));
		CLVariable temp2 = new CLVariable(new StdVariableType(StdType.FLOAT), "temp2");
		mainMethod.addLocalVar(temp2);
		mainMethod.addExpression(RNGType.initializeGenerator(rng, temp, Long.toString(config.rngOffset)));
		mainMethod.addExpression(RNGType.assignRandomFloat(rng, temp2));
		mainMethod.addExpression(RNGType.assignRandomInt(rng, temp, 17));
		mainMethod.addExpression(new Expression("printf(\"%d\\n\",temp);"));
	}

	private void updateIncludes()
	{
		List<Include> addIncludes = mainMethod.getIncludes();
		if (addIncludes != null) {
			includes.addAll(addIncludes);
		}
		for (Method method : helperMethods) {
			if (method == null)
				break;
			addIncludes = method.getIncludes();
			if (addIncludes != null) {
				includes.addAll(addIncludes);
			}
		}
	}

	private MemoryTranslatorVisitor createTranslatorVisitor()
	{
		MemoryTranslatorVisitor visitor = new MemoryTranslatorVisitor(stateVectorType);
		for (PrismVariable var : model.getStateVector().getVars()) {
			visitor.addTranslation(var.name, var.name);
		}
		return visitor;
	}

	private void visitMethodsTranslator(MemoryTranslatorVisitor visitor) throws KernelException
	{
		visitor.setStateVector(stateVector);
		mainMethod.accept(visitor);
		for (Method method : helperMethods) {
			if (method == null)
				break;
			if (!method.hasDefinedSVAccess()) {
				throw new KernelException("Method " + method.methodName + " has not StateVector access!");
			}
			visitor.setStateVector(method.accessStateVector());
			method.accept(visitor);
		}
	}

	private void declareMethods(StringBuilder builder)
	{
		builder.append(mainMethod.getDeclaration()).append("\n");
		for (Method method : helperMethods) {
			if (method == null)
				break;
			builder.append(method.getDeclaration()).append("\n");
		}
	}

	private void defineMethods(StringBuilder builder)
	{
		builder.append(mainMethod.getSource()).append("\n");
		for (Method method : helperMethods) {
			if (method == null)
				break;
			builder.append(method.getSource()).append("\n");
		}
	}

	private void generateSource() throws KernelException
	{
		StringBuilder builder = new StringBuilder();
		updateIncludes();
		for (Include include : includes) {
			builder.append(include.getSource()).append("\n");
		}
		builder.append(KERNEL_TYPEDEFS).append("\n");
		for (Expression expr : globalDeclarations) {
			builder.append(expr.getSource()).append("\n");
		}
		visitMethodsTranslator(createTranslatorVisitor());
		declareMethods(builder);
		defineMethods(builder);
		kernelSource = builder.toString();
	}

	public String getSource()
	{
		return kernelSource;
	}
}