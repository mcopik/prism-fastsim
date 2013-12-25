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

import java.util.List;

import simulator.gpu.opencl.kernel.Include;
import simulator.gpu.opencl.kernel.KernelComponent;
import simulator.gpu.opencl.kernel.expression.Expression;

public class CLVariable implements KernelComponent
{
	public enum Location {
		REGISTER, LOCAL, GLOBAL
	}

	public final String varName;
	public final VariableType varType;
	private CLValue initValue = null;
	public Location memLocation = Location.REGISTER;

	public CLVariable(VariableType varType, String varName)
	{
		this.varName = varName;
		this.varType = varType;
	}

	public void setMemoryLocation(Location loc)
	{
		memLocation = loc;
	}

	public void setInitValue(CLValue value)
	{
		initValue = value;
	}

	public VariableType getPointer()
	{
		return new PointerType(varType);
	}

	@Override
	public String getDeclaration()
	{
		return null;
	}

	@Override
	public boolean hasInclude()
	{
		return false;
	}

	@Override
	public boolean hasDeclaration()
	{
		return false;
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
		builder.append(varType.getType()).append(" ").append(varName);
		if (initValue != null) {
			builder.append(" = ").append(initValue.getSource());
		}
		builder.append(";");
		return new Expression(builder.toString());
	}
}