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

import simulator.gpu.automaton.AbstractAutomaton;
import simulator.gpu.automaton.AbstractAutomaton.StateVector;
import simulator.gpu.automaton.PrismVariable;
import simulator.gpu.opencl.CLDeviceWrapper;
import simulator.gpu.opencl.kernel.expression.Expression;
import simulator.gpu.opencl.kernel.expression.ForLoop;
import simulator.gpu.opencl.kernel.expression.KernelMethod;
import simulator.gpu.opencl.kernel.expression.Method;
import simulator.gpu.opencl.kernel.memory.CLVariable;
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

	public final static String KERNEL_TYPEDEFS = "typedef char int8_t;\n" + "typedef unsigned char uint8_t;\n" + "typedef unsigned short uint16_t;\n"
			+ "typedef short int16_t;\n" + "typedef unsigned int uint32_t;\n" + "typedef int int32_t;\n" + "typedef long int64_t;\n"
			+ "typedef unsigned long uint64_t;\n";

	private String kernelSource = null;
	/**
	 * #define NUMBER_OF_ITERATIONS
	 * #define NUMBER_OF_PROPERTIES
	 */
	private List<Expression> defines = new ArrayList<>();
	/**
	 * struct StateVector {
	 * 	each_variable;
	 * }
	 */
	private StructureType stateVectorType;
	/**
	 * struct PropertyState {
	 *  bool value;
	 *  bool valueKnown;
	 * }
	 */
	private StructureType propertyType;
	/**
	 * DTMC:
	 * struct SynCmdState {
	 * 	uint8_t numberOfTransitions;
	 *  bool[] flags;
	 *  }
	 */
	private StructureType synCmdState;
	private KernelMethod mainMethod;
	/**
	 * DTMC:
	 * Return value is number of concurrent transitions.
	 * int checkGuards(StateVector * sv, bool * guardsTab);
	 * CTMC:
	 * Return value is rates sum of transitions in race condition.
	 * float checkGuards(StateVector * sv, bool * guardsTab);
	 */
	private Method checkGuards;
	/**
	 * DTMC:
	 * Return value is number of concurrent transitions.
	 * int checkGuardsSyn(StateVector * sv, SynCmdState * tab);
	 * CTMC:
	 * Return value is rates sum of transitions in race condition.
	 * float checkGuardsSyn(StateVector * sv, SynCmdState * tab);
	 */
	private Method checkGuardsSyn;
	/**
	 * DTMC:
	 * void performUpdate(StateVector * sv, int updateSelection);
	 * CTMC:
	 * void performUpdate(StateVector * sv, float sumSelection,bool * guardsTab);
	 */
	private Method performUpdate;
	/**
	 * DTMC:
	 * void performUpdateSyn(StateVector * sv, int updateSelection,SynCmdState * tab);
	 * CTMC:
	 * void performUpdateSyn(StateVector * sv, float sumSelection,SynCmdState * tab);
	 */
	private Method performUpdateSyn;
	/**
	 * Return value determines is we can stop simulation(we know all values).
	 * DTMC:
	 * bool updateProperties(StateVector * sv,PropertyState * prop,int time);
	 * CTMC:
	 * bool updateProperties(StateVector * sv,PropertyState * prop,float time);
	 */
	private Method updateProperties;
	private CLVariable stateVector;
	/**
	 * For CTMC - float. For DTMC - depends on MAX_ITERATIONS.
	 */
	private CLVariable time;

	private List<Expression> globalDeclarations = new ArrayList<>();

	public Kernel(CLDeviceWrapper wrapper, AbstractAutomaton model, Property[] properties)
	{
		importStateVector(model.getStateVector());
		try {
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

	private void importStateVector(StateVector sv)
	{
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
		ForLoop loop = new ForLoop("i", 0, 1000);
		mainMethod.addExpression(loop);
		CLVariable rng = new CLVariable(new RNGType(), "rng");
		mainMethod.addLocalVar(rng);
		CLVariable temp = new CLVariable(new StdVariableType(StdType.INT8), "temp");
		mainMethod.addLocalVar(temp);
		temp.setInitValue(StdVariableType.initialize(0));
		CLVariable temp2 = new CLVariable(new StdVariableType(StdType.FLOAT), "temp2");
		mainMethod.addLocalVar(temp2);
		mainMethod.addExpression(RNGType.initializeGenerator(rng, temp, "1099511627776L"));
		mainMethod.addExpression(RNGType.assignRandomFloat(rng, temp2));
		mainMethod.addExpression(RNGType.assignRandomInt(rng, temp, 17));
		mainMethod.addExpression(new Expression("printf(\"%d\\n\",temp);"));
	}

	private void createMethods(AbstractAutomaton automaton)
	{

	}

	private void generateSource()
	{
		StringBuilder builder = new StringBuilder();
		builder.append("#include \"mwc64x_rng.cl\"").append("\n");
		builder.append(KERNEL_TYPEDEFS).append("\n");
		builder.append(mainMethod.getDeclaration()).append("\n");
		for (Expression expr : globalDeclarations) {
			builder.append(expr.getSource()).append("\n");
		}
		builder.append(mainMethod.getSource());
		kernelSource = builder.toString();
	}

	public String getSource()
	{
		return kernelSource;
	}
}