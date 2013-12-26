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
package simulator.gpu.opencl.kernel.expression;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import simulator.gpu.opencl.kernel.KernelException;
import simulator.gpu.opencl.kernel.memory.CLVariable;
import simulator.gpu.opencl.kernel.memory.VariableInterface;

public class Method extends ComplexKernelComponent
{
	public final String methodName;
	public final VariableInterface methodType;
	protected Map<String, CLVariable> args = new HashMap<>();

	//protected CLVariable. returnType = new CLVariable(CLVariable.Type.VOID);

	public Method(String name, VariableInterface returnType)
	{
		methodName = name;
		methodType = returnType;
	}

	public void addArg(CLVariable var) throws KernelException
	{
		if (args.containsKey(var.varName)) {
			throw new KernelException("Variable " + var.varName + " already exists in arg list of method " + methodName);
		}
		args.put(var.varName, var);
		sourceHasChanged = true;
	}

	public int getVarsNum()
	{
		return localVars.size();
	}

	@Override
	protected String createHeader()
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
			builder.replace(len - 2, len - 1, ")");
		} else {
			builder.append(")");
		}
		return builder.toString();
	}

	@Override
	public Expression getDeclaration()
	{
		return new Expression(createHeader() + ";");
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
	public void accept(VisitorInterface v)
	{
		v.visit(this);
	}
}