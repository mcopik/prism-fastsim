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

import java.util.ArrayList;
import java.util.List;

import simulator.gpu.opencl.kernel.Include;
import simulator.gpu.opencl.kernel.KernelComponent;
import simulator.gpu.opencl.kernel.expression.Expression;

/**
 * 
 *
 */
public class StructureType implements VariableType, KernelComponent
{
	private static class StructureValue implements CLValue
	{

		@Override
		public boolean validateAssignmentTo(VariableType type)
		{
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public Expression getSource()
		{
			// TODO Auto-generated method stub
			return null;
		}

	}

	private List<CLVariable> fields = new ArrayList<>();
	public final String typeName;

	public StructureType(String typeName)
	{
		this.typeName = typeName;
	}

	public void addVariable(CLVariable var)
	{
		fields.add(var);
	}

	public List<CLVariable> getFields()
	{
		return fields;
	}

	@Override
	public String getDeclaration()
	{
		StringBuilder builder = new StringBuilder();
		builder.append("typedef struct _").append(typeName).append(" ");
		builder.append(typeName).append(";");
		return builder.toString();
	}

	@Override
	public String getType()
	{
		return typeName;
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
		builder.append("typedef struct _").append(typeName).append("{\n");
		for (CLVariable var : fields) {
			builder.append(var.getSource());
			builder.append("\n");
		}
		builder.append("} ").append(typeName).append(";\n");
		return new Expression(builder.toString());
	}
}