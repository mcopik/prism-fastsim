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

package simulator.gpu.opencl.kernel.memory;

import simulator.gpu.opencl.kernel.KernelException;

public class Scalar implements CLVariable
{

	public final String varName;
	public final VariableType type;
	public final boolean isPointer;

	public Scalar(VariableType type)
	{
		this.varName = null;
		this.type = type;
		this.isPointer = false;
	}

	public Scalar(String name, VariableType type)
	{
		this.varName = name;
		this.type = type;
		this.isPointer = false;
	}

	public Scalar(String name, VariableType type, boolean isPtr, boolean isConst)
	{
		this.varName = name;
		this.type = type;
		this.isPointer = isPtr;
	}

	@Override
	public Pointer getPointer()
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isArray()
	{
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public int length() throws KernelException
	{
		throw new KernelException("Calling length() on non-array variable " + varName);
	}

	@Override
	public boolean isPointer()
	{
		return isPointer;
	}

	@Override
	public void setMemoryLocation(Location loc)
	{
		// TODO Auto-generated method stub

	}

	@Override
	public VariableType getType()
	{
		// TODO Auto-generated method stub
		return null;
	}
}