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
import simulator.gpu.opencl.kernel.memory.CLValue;
import simulator.gpu.opencl.kernel.memory.CLVariable;
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
	private KernelMethod mainMethod;
	private List<Method> otherMethods = new ArrayList<>();
	private StructureType stateVector;
	private CLValue stateVectorInit;

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
		stateVector = new StructureType("StateVector");
		Integer[] init = new Integer[sv.size()];
		PrismVariable[] vars = sv.getVars();
		for (int i = 0; i < vars.length; ++i) {
			CLVariable var = new CLVariable(new StdVariableType(vars[i]), vars[i].name);
			stateVector.addVariable(var);
			init[i] = new Integer(vars[i].initValue);
		}
		stateVectorInit = stateVector.initializeStdStructure(init);
		globalDeclarations.add(stateVector.getDefinition());
	}

	private void createMainMethod() throws KernelException
	{
		mainMethod = new KernelMethod();
		CLVariable var = new CLVariable(new StdVariableType(StdType.INT16), "stateVector2");
		var.setInitValue(StdVariableType.initialize(new Integer(15)));
		mainMethod.addLocalVar(var);
		CLVariable sv = new CLVariable(stateVector, "stateVector");
		sv.setInitValue(stateVectorInit);
		mainMethod.addLocalVar(sv);
		ForLoop loop = new ForLoop("i", 0, 1000);
		mainMethod.addExpression(loop);
	}

	private void generateSource()
	{
		StringBuilder builder = new StringBuilder();
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