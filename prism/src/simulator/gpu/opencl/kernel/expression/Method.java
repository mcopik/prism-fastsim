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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import simulator.gpu.opencl.kernel.KernelException;
import simulator.gpu.opencl.kernel.memory.CLVariable;
import simulator.gpu.opencl.kernel.memory.VariableInterface;

public class Method implements KernelComponent
{
	public final String methodName;
	public final VariableInterface methodType;
	protected Map<String, CLVariable> localVars = new HashMap<>();
	protected Map<String, CLVariable> args = new HashMap<>();
	List<Expression> source = new ArrayList<>();
	boolean sourceHasChanged = false;

	//protected CLVariable. returnType = new CLVariable(CLVariable.Type.VOID);

	public Method(String name, VariableInterface type)
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
		sourceHasChanged = true;
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

	protected void declareVariables(List<Expression> source)
	{
		for (Map.Entry<String, CLVariable> decl : localVars.entrySet()) {
			source.add(decl.getValue().getDefinition());
		}
	}

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
	public String getSource()
	{
		generateSource();
		StringBuilder builder = new StringBuilder();
		for (Expression expr : source) {
			builder.append(expr.getSource());
			builder.append("\n");
		}
		return builder.toString();
	}

	@Override
	public void accept(VisitorInterface v)
	{
		generateSource();
		v.visit(this);
	}

	private void generateSource()
	{
		if (sourceHasChanged) {
			source.clear();
			source.add(new Expression(createHeader() + "{\n"));
			//create method body
			declareVariables(source);
			source.add(new Expression("}"));
			sourceHasChanged = false;
		}
	}
}