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

import java.util.EnumSet;

import prism.Preconditions;
import simulator.gpu.automaton.PrismVariable;
import simulator.gpu.opencl.kernel.expression.Expression;

public class StdVariableType implements VariableInterface
{
	private static class StdVariableValue<T extends Number> implements CLValue
	{
		private T value;

		public StdVariableValue(T value)
		{
			this.value = value;
		}

		@Override
		public boolean validateAssignmentTo(VariableInterface type)
		{
			if (!(type instanceof StdVariableType)) {
				return false;
			}
			StdVariableType other = (StdVariableType) type;
			if (other.varType.equals(StdType.BOOL)) {
				return true;
			} else if (other.varType.equals(StdType.DOUBLE)) {
				return true;
			} else if (other.varType.equals(StdType.FLOAT) && !(value instanceof Double)) {
				return true;
			} else if (other.varType.equals(StdType.VOID)) {
				return false;
			}
			//integers
			else {
				//risk losing information, in C it's only warning
				return true;
			}
		}

		@Override
		public Expression getSource()
		{
			if (value instanceof Long) {
				return new Expression(value.toString() + "L");
			} else {
				return new Expression(value.toString());
			}
		}
	}

	public enum StdType {
		VOID, BOOL, CHAR, INT8, UINT8, INT16, UINT16, INT32, UINT32, FLOAT, DOUBLE, INT64, UINT64;
		public boolean isInteger()
		{
			return this != VOID && this != BOOL && this != FLOAT && this != DOUBLE;
		}
	}

	private static final EnumSet<StdType> typesWithDirectName = EnumSet.of(StdType.BOOL, StdType.CHAR, StdType.VOID, StdType.FLOAT, StdType.DOUBLE);

	public final StdType varType;

	public StdVariableType(StdType type)
	{
		this.varType = type;
	}

	public StdVariableType(PrismVariable var)
	{
		varType = getIntType(var.bitsNumber, var.signFlag);
	}

	public StdVariableType(long minimal, long maximal)
	{
		Preconditions.checkCondition(minimal <= maximal, "Minimal > maximal!");
		long length = maximal - minimal;
		varType = getIntType(Long.SIZE - Long.numberOfLeadingZeros(length), minimal < 0);
	}

	private StdType getIntType(long bitsNumber, boolean signFlag)
	{
		if (bitsNumber == 1) {
			return StdType.BOOL;
		} else if (bitsNumber <= 8) {
			if (signFlag) {
				return StdType.INT8;
			} else {
				return StdType.UINT8;
			}
		} else if (bitsNumber <= 16) {
			if (signFlag) {
				return StdType.INT16;
			} else {
				return StdType.UINT16;
			}
		} else if (bitsNumber <= 32) {
			if (signFlag) {
				return StdType.INT32;
			} else {
				return StdType.UINT32;
			}
		} else {
			if (signFlag) {
				return StdType.INT64;
			} else {
				return StdType.UINT64;
			}
		}
	}

	static public <T extends Number> CLValue initialize(T value)
	{
		return new StdVariableValue<T>(value);
	}

	@Override
	public String getType()
	{
		if (typesWithDirectName.contains(varType)) {
			return varType.toString().toLowerCase();
		} else {
			return varType.toString().toLowerCase() + "_t";
		}
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
		return false;
	}

	@Override
	public CLVariable accessElement(CLVariable var, Expression index)
	{
		return null;
	}

	@Override
	public String declareVar(String varName)
	{
		return String.format("%s %s", getType(), varName);
	}
}