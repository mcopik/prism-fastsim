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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import simulator.gpu.opencl.kernel.expression.Expression;
import simulator.gpu.opencl.kernel.memory.CLVariable;
import simulator.gpu.opencl.kernel.memory.VariableType;

public class Method implements KernelComponent
{
	public final String methodName;
	public final VariableType methodType;
	protected Map<String, CLVariable> localVars = new HashMap<>();
	protected Map<String, CLVariable> args = new HashMap<>();

	//protected CLVariable. returnType = new CLVariable(CLVariable.Type.VOID);

	public Method(String name, VariableType type)
	{
		methodName = name;
		methodType = type;
	}

	public void addLocalVar(CLVariable var) throws KernelException
	{
		if (localVars.containsKey(var.varName)) {
			throw new KernelException("Variable " + var.varName + " already exists in method " + methodName);
		}
		localVars.put(var.varName, var);
	}

	public void addArg(CLVariable var) throws KernelException
	{
		if (args.containsKey(var.varName)) {
			throw new KernelException("Variable " + var.varName + " already exists in arg list of method " + methodName);
		}
		args.put(var.varName, var);
	}

	public int getVarsNum()
	{
		return localVars.size();
	}

	protected void declareVariables(StringBuilder builder)
	{
		for (Map.Entry<String, CLVariable> decl : localVars.entrySet()) {
			builder.append(decl.getValue().getSource());
			builder.append("\n");
		}
	}

	@Override
	public String getDeclaration()
	{
		StringBuilder builder = new StringBuilder(methodType.getType());
		builder.append(" ").append(methodName).append("( ");
		for (Map.Entry<String, CLVariable> decl : args.entrySet()) {
			CLVariable var = decl.getValue();
			builder.append(var.varType.getType()).append(" ").append(var.varName);
			builder.append(", ");
		}
		if (args.size() != 0) {
			int len = builder.length();
			builder.replace(len - 2, len - 1, ");");
		} else {
			builder.append(");");
		}
		return builder.toString();
	}

	@Override
	public boolean hasInclude()
	{
		return false;
	}

	@Override
	public boolean hasDeclaration()
	{
		return true;
	}

	@Override
	public List<Include> getInclude()
	{
		return null;
	}

	@Override
	public Expression getSource()
	{
		StringBuilder builder = new StringBuilder();
		builder.append(getDeclaration());
		int len = builder.length();
		builder.deleteCharAt(len - 1);
		builder.append("{\n");
		//create method body
		declareVariables(builder);
		builder.append("\n").append("}");
		return new Expression(builder.toString());
	}
}