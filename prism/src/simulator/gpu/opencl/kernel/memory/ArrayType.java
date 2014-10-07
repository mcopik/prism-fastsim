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

import prism.Preconditions;
import simulator.gpu.opencl.kernel.expression.Expression;

public class ArrayType implements VariableTypeInterface
{
	/**
	 * Used only for initialization of the array.
	 */ 
	private static class ArrayValue implements CLValue
	{
		/**
		 * Values, used also for verification of assignment correctness.
		 */
		private CLValue[] values = null;
		
		/**
		 * @param values
		 */
		public ArrayValue(CLValue[] values)
		{
			this.values = values;
		}

		@Override
		public boolean validateAssignmentTo(VariableTypeInterface type)
		{
			if (type instanceof ArrayType) {
				ArrayType array = (ArrayType) type;
				if (array.length != values.length) {
					return false;
				}
				return values[0].validateAssignmentTo(type);
			} else {
				return false;
			}
		}

		@Override
		public Expression getSource()
		{
			StringBuilder builder = new StringBuilder("{\n");
			for (CLValue value : values) {
				builder.append(value.getSource().toString().replace("\n", " "));
				builder.append(",\n");
			}
			int len = builder.length();
			builder.delete(len - 2, len - 1);
			builder.append("}");
			return new Expression(builder.toString());
		}
	}
	
	/**
	 * Type of values stored in this array.
	 */
	private final VariableTypeInterface varType;
	/**
	 * Length of this array.
	 */
	public final int length;

	/**
	 * Default constructor.
	 * @param type
	 * @param length
	 */
	public ArrayType(VariableTypeInterface type, int length)
	{
		this.varType = type;
		this.length = length;
	}
	
	/**
	 * Copy constructor.
	 * @param copy
	 */
	public ArrayType(ArrayType copy)
	{
		this.varType = copy.varType;
		this.length = copy.length;
	}

	@Override
	public String getType()
	{
		return varType.getType() + "*";
	}

	@Override
	public boolean isStructure()
	{
		return false;
	}

	@Override
	public CLVariable accessField(String varName, String fieldName)
	{
		return null;
	}

	@Override
	public boolean isArray()
	{
		return true;
	}

	@Override
	public CLVariable accessElement(CLVariable var, Expression index)
	{
		Preconditions.checkNotNull(var);
		Preconditions.checkNotNull(index);
		return new CLVariable(varType, String.format("%s[%s]", var.varName, index.getSource()));
	}

	@Override
	public String declareVar(String varName)
	{
		return String.format("%s %s[%d]", varType.getType(), varName, length);
	}
	
	/**
	 * @return type of values stored in the array
	 */
	public VariableTypeInterface getInternalType()
	{
		return varType;
	}
	
	/**
	 * @param values
	 * @return value used for initialization of this array
	 */
	public CLValue initializeArray(CLValue[] values)
	{
		Preconditions.checkNotNull(values);
		Preconditions.checkCondition(values.length > 0);
		Preconditions.checkCondition(values[0].validateAssignmentTo(varType), "Initialization value can't be assigned to array type!");
		return new ArrayValue(values);
	}
}