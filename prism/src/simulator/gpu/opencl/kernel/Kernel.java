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
import simulator.gpu.automaton.AbstractAutomaton.AutomatonType;
import simulator.gpu.automaton.PrismVariable;
import simulator.gpu.opencl.kernel.expression.Include;
import simulator.gpu.opencl.kernel.expression.KernelComponent;
import simulator.gpu.opencl.kernel.expression.MemoryTranslatorVisitor;
import simulator.gpu.opencl.kernel.expression.Method;
import simulator.gpu.opencl.kernel.memory.CLVariable;
import simulator.gpu.opencl.kernel.memory.StructureType;
import simulator.sampler.Sampler;

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
	@SuppressWarnings("unused")
	private KernelConfig config = null;
	private AbstractAutomaton model = null;
	@SuppressWarnings("unused")
	private List<Sampler> properties = null;
	/**
	 * Source components.
	 */
	private String kernelSource = null;
	private List<Include> includes = new ArrayList<>();
	private StructureType stateVectorType = null;
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
	private Method mainMethod = null;

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
		 * void performUpdate(StateVector * sv, float sumSelection, int allTransitions);
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

	private KernelGenerator methodsGenerator = null;
	private Method helperMethods[] = new Method[MethodIndices.SIZE];
	private CLVariable stateVector = null;
	/**
	 * For CTMC - float. For DTMC - depends on MAX_ITERATIONS.
	 */
	private CLVariable timeCounter = null;

	private List<KernelComponent> globalDeclarations = new ArrayList<>();

	public final static String KERNEL_TYPEDEFS = "typedef char int8_t;\n" + "typedef unsigned char uint8_t;\n" + "typedef unsigned short uint16_t;\n"
			+ "typedef short int16_t;\n" + "typedef unsigned int uint32_t;\n" + "typedef int int32_t;\n" + "typedef long int64_t;\n"
			+ "typedef unsigned long uint64_t;\n";

	public Kernel(KernelConfig config, AbstractAutomaton model, List<Sampler> properties) throws KernelException
	{
		this.config = config;
		this.model = model;
		this.properties = properties;
		if (model.getType() == AutomatonType.DTMC) {
			this.methodsGenerator = new KernelGeneratorDTMC(model, properties, config);
		} else {
			this.methodsGenerator = new KernelGeneratorCTMC(model, properties, config);
		}
		try {
			stateVectorType = methodsGenerator.getSVType();
			helperMethods[MethodIndices.CHECK_GUARDS.indice] = methodsGenerator.createNonsynGuardsMethod();
			helperMethods[MethodIndices.PERFORM_UPDATE.indice] = methodsGenerator.createNonsynUpdate();
			mainMethod = methodsGenerator.createMainMethod();
			globalDeclarations.addAll(methodsGenerator.getAdditionalDeclarations());
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
		visitor.setStateVector(mainMethod.accessStateVector());
		mainMethod.accept(visitor);
		for (Method method : helperMethods) {
			if (method == null)
				continue;
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
				continue;
			builder.append(method.getDeclaration()).append("\n");
		}
	}

	private void defineMethods(StringBuilder builder)
	{
		builder.append(mainMethod.getSource()).append("\n");
		for (Method method : helperMethods) {
			if (method == null)
				continue;
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
		for (KernelComponent expr : globalDeclarations) {
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