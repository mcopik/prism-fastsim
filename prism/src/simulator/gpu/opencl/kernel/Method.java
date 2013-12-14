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
import java.util.Map;

import simulator.gpu.opencl.kernel.memory.CLVariable;

public class Method
{
	protected String methodName = null;
	protected Map<String, CLVariable> localVars = new HashMap<>();

	//protected CLVariable. returnType = new CLVariable(CLVariable.Type.VOID);

	public Method(String name)
	{
		methodName = name;
	}

	/*
		public void addVar(CLVariable var) throws KernelException
		{
			if (localVars.containsKey(var.varName)) {
				throw new KernelException("Variable " + var.varName + " already exists in method " + methodName);
			}
			localVars.put(var.varName, var);
		}
		public void addArg(CLVariable var)*/
	public int getVarsNum()
	{
		return localVars.size();
	}
	/*
		public void setReturnType(CLVariable type)
		{
			returnType = type;
		}

		public void setReturn(CLVariable var)
		{
			returnType = var;
		}*/
}