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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import prism.PrismLangException;
import simulator.opencl.RuntimeConfig;
import simulator.opencl.automaton.AbstractAutomaton;
import simulator.opencl.automaton.AbstractAutomaton.AutomatonType;
import simulator.opencl.automaton.PrismVariable;
import simulator.opencl.kernel.expression.Include;
import simulator.opencl.kernel.expression.KernelComponent;
import simulator.opencl.kernel.expression.MemoryTranslatorVisitor;
import simulator.opencl.kernel.expression.Method;
import simulator.opencl.kernel.memory.StructureType;
import simulator.sampler.Sampler;

public class Kernel
{
	/**
	 * Input - three objects which determine kernel's source.
	 */
	@SuppressWarnings("unused")
	private RuntimeConfig config = null;

	/**
	 * PRISM automaton.
	 */
	private AbstractAutomaton model = null;
	@SuppressWarnings("unused")
	//TODO: why the hell this is unused?
	private List<Sampler> properties = null;

	/**
	 * Source components.
	 */
	private String kernelSource = null;

	/**
	 * List of includes.
	 */
	private List<Include> includes = new ArrayList<>();

	/**
	 * State vector structure.
	 */
	private StructureType stateVectorType = null;

	/**
	 * Main kernel method.
	 * INPUT:
	 * RNG offset
	 * number of samples
	 * OUTPUT:
	 * global array of properties values
	 */
	private Method mainMethod = null;

	public enum ArgsTypes {

	}

	/**
	 * Kernel generator.
	 */
	private KernelGenerator methodsGenerator = null;

	/**
	 * Helper methods, used for checking guards etc.
	 */
	private Collection<Method> helperMethods = null;

	/**
	 * Global declarations.
	 */
	private List<KernelComponent> globalDeclarations = new ArrayList<>();

	/**
	 * Not necessary right now, because Random123 provides these definition in include.
	 * May be helpful later.
	 */
	public final static String KERNEL_TYPEDEFS = "typedef char int8_t;\n" + "typedef unsigned char uint8_t;\n" + "typedef unsigned short uint16_t;\n"
			+ "typedef short int16_t;\n" + "typedef unsigned int uint32_t;\n" + "typedef int int32_t;\n" + "typedef long int64_t;\n"
			+ "typedef unsigned long uint64_t;\n";

	/**
	 * Create kernel for an automaton and properties, using also a configuration class.
	 * @param config
	 * @param model
	 * @param properties
	 * @throws KernelException
	 * @throws PrismLangException 
	 */
	public Kernel(RuntimeConfig config, AbstractAutomaton model, List<Sampler> properties) throws KernelException, PrismLangException
	{
		this.config = config;
		this.model = model;
		this.properties = properties;
		if (model.getType() == AutomatonType.DTMC) {
			this.methodsGenerator = new KernelGeneratorDTMC(model, properties, config);
		} else {
			this.methodsGenerator = new KernelGeneratorCTMC(model, properties, config);
		}
		stateVectorType = methodsGenerator.getSVType();
		mainMethod = methodsGenerator.createMainMethod();
		helperMethods = methodsGenerator.getHelperMethods();
		globalDeclarations.addAll(methodsGenerator.getAdditionalDeclarations());
		generateSource();
	}

	/**
		public Kernel(String source)
		{
			kernelSource = source;
		}
		*/
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

	@Deprecated
	private MemoryTranslatorVisitor createTranslatorVisitor()
	{
		MemoryTranslatorVisitor visitor = new MemoryTranslatorVisitor(stateVectorType);
		for (PrismVariable var : model.getStateVector().getVars()) {
			visitor.addTranslation(var.name, methodsGenerator.translateSVField(var.name));
		}
		return visitor;
	}

	@Deprecated
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

	private void generateSource() throws KernelException
	{
		StringBuilder builder = new StringBuilder();

		/**
		 * Structure of a kernel:
		 * - includes
		 * - declaration of methods
		 * - definition of methods
		 */

		updateIncludes();
		for (Include include : includes) {
			builder.append(include.getSource()).append("\n");
		}

		//builder.append(KERNEL_TYPEDEFS).append("\n");
		//builder.append("typedef unsigned char uchar;\n");

		for (KernelComponent expr : globalDeclarations) {
			builder.append(expr.getSource()).append("\n");
		}
		visitMethodsTranslator(createTranslatorVisitor());
		declareMethods(builder);
		defineMethods(builder);
		kernelSource = builder.toString();
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

	/**
	 * @return OpenCL source code of this kernel
	 */
	public String getSource()
	{
		return kernelSource;
	}
}