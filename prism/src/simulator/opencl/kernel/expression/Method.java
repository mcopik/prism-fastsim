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
package simulator.opencl.kernel.expression;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import prism.Preconditions;
import simulator.opencl.kernel.KernelException;
import simulator.opencl.kernel.memory.CLValue;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.VariableTypeInterface;

public class Method extends ComplexKernelComponent
{
	/**
	 * Method name.
	 */
	public final String methodName;
	/**
	 * Return type.
	 */
	public final VariableTypeInterface methodType;
	/**
	 * Arguments.
	 */
	protected Map<String, CLVariable> args = new LinkedHashMap<>();
	/**
	 * Current instance of state vector (if exists).
	 */
	@Deprecated
	protected CLVariable stateVectorAccess = null;
	
	/**
	 * Default constructor from name and return type.
	 * @param name
	 * @param returnType
	 */
	public Method(String name, VariableTypeInterface returnType)
	{
		methodName = name;
		methodType = returnType;
	}
	
	/**
	 * Add method argument.
	 * @param var
	 * @throws KernelException when a variable with this name already exists
	 */
	public void addArg(CLVariable var) throws KernelException
	{
		Preconditions.checkNotNull(var);
		if (args.containsKey(var.varName) || localVars.containsKey(var.varName)) {
			throw new KernelException("Variable " + var.varName + " already exists in arg list of method " + methodName);
		}
		args.put(var.varName, var);
		updateIncludes(var);
	}
	
	/**
	 * Add collection of arguments.
	 * @param vars
	 * @throws KernelException when a variable with this name already exists
	 */
	public void addArg(Collection<CLVariable> vars) throws KernelException
	{
		for (CLVariable var : vars) {
			addArg(var);
		}
	}
	
	/**
	 * @return number of arguments
	 */
	public int getArgsSize()
	{
		return args.size();
	}
	
	/**
	 * @param name
	 * @return argument with this name; null if it doesn't exist
	 */
	public CLVariable getArg(String name)
	{
		return args.get(name);
	}

	@Override
	public void addLocalVar(CLVariable var) throws KernelException
	{
		Preconditions.checkNotNull(var);
		// additional checking for duplicate names in local variables and method arguments
		if (args.containsKey(var.varName)) {
			throw new KernelException("Variable " + var.varName + " already exists!");
		}
		super.addLocalVar(var);
	}

	@Deprecated
	public void registerStateVector(CLVariable var)
	{
		Preconditions.checkCondition(args.containsValue(var) || localVars.containsValue(var),
		//global variables do not exist in OpenCL - it has to be arg/local var
				"StateVector reference has to be a local variable or function argument!");
		stateVectorAccess = var;
	}

	@Override
	protected String createHeader()
	{
		StringBuilder builder = new StringBuilder(methodType.getType());
		builder.append(" ").append(methodName).append("( ");
		for (Map.Entry<String, CLVariable> decl : args.entrySet()) {
			CLVariable var = decl.getValue();
			builder.append(var.getDeclaration());
			builder.deleteCharAt(builder.length() - 1);
			builder.append(", ");
		}
		if (args.size() != 0) {
			int len = builder.length();
			builder.replace(len - 2, len - 1, ")");
		} else {
			builder.append(")");
		}
		return builder.toString();
	}

	@Override
	public KernelComponent getDeclaration()
	{
		return new Expression(createHeader() + ";");
	}

	/**
	 * Generate an expression of calling this method,
	 * using given values for method arguments.
	 * Important: the method doesn't check the correctness of arguments!
	 * @param args
	 * @return
	 */
	public Expression callMethod(CLValue... args)
	{
		StringBuilder builder = new StringBuilder(methodName);
		builder.append("(");
		for (CLValue arg : args) {
			builder.append(arg.getSource()).append(",");
		}
		builder.deleteCharAt(builder.length() - 1);
		builder.append(")");
		return new Expression(builder.toString());
	}

	@Override
	public boolean hasDeclaration()
	{
		return true;
	}

	@Override
	public void accept(VisitorInterface v)
	{
		for (Expression expr : variableDefinitions) {
			expr.accept(v);
		}
		for (KernelComponent component : body) {
			System.out.println("Visit: " + component.getClass().getName());
			component.accept(v);
		}
	}
	
	/**
	 * Add return statement with given variable.
	 * Important: the method doesn't check the compatibility between variable and method type!
	 * @param var
	 */
	public void addReturn(CLVariable var)
	{
		body.add(new Expression(String.format("return %s;", var.varName)));
	}
	
	/**
	 * Add return statement with given expression.
	 * Important: the method doesn't check the compatibility between variable and method type!
	 * @param expr
	 */
	public void addReturn(Expression expr)
	{
		body.add(new Expression(String.format("return %s;", expr)));
	}
	
	@Deprecated
	public boolean hasDefinedSVAccess()
	{
		return stateVectorAccess != null;
	}
	
	@Deprecated
	public CLVariable accessStateVector()
	{
		return stateVectorAccess;
	}
}