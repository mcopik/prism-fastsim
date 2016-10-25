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
package simulator.opencl.kernel.memory;

import java.util.EnumSet;

import prism.Preconditions;
import simulator.opencl.automaton.PrismVariable;
import simulator.opencl.kernel.KernelException;
import simulator.opencl.kernel.expression.Expression;

public class StdVariableType implements VariableTypeInterface
{
	/**
	 * Used mainly for initialization.
	 * @param <T> type of variable, bool/int/float etc.
	 */
	private static class StdVariableValue<T extends Number> implements CLValue
	{
		/**
		 * Variable value.
		 */
		private T value;
		
		/**
		 * @param value
		 */
		public StdVariableValue(T value)
		{
			this.value = value;
		}

		@Override
		public boolean validateAssignmentTo(VariableTypeInterface type)
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

	/**
	 * Standard variable types in C.
	 */
	public enum StdType {
		VOID, BOOL, INT8, UINT8, INT16, UINT16, INT32, UINT32, FLOAT, DOUBLE, INT64, UINT64;
		/**
		 * @return true for ints, excluding bool
		 */
		public boolean isInteger()
		{
			return this != VOID && this != BOOL && this != FLOAT && this != DOUBLE;
		}
		
		/**
		 * @return true for unsigned integers
		 */
		public boolean isUnsigned()
		{
			return this == UINT8 || this == UINT16 || this == UINT32 || this == UINT64;
		}
		
		public int getSize()
		{
			switch(this)
			{
				case VOID:
					return 8;
				case BOOL:
					return 1;
				case INT8:
				case UINT8:
					return 1;
				case INT16:
				case UINT16:
					return 4;
				case INT32:
				case UINT32:
					return 8;
				case FLOAT:
					return 4;
				case DOUBLE:
					return 8;
			}
			throw new RuntimeException("");
		}
	}

	private static final EnumSet<StdType> typesWithDirectName = EnumSet.of(StdType.BOOL, StdType.VOID, StdType.FLOAT, StdType.DOUBLE);
	/**
	 * Vector size, typical OpenCL variable type.
	 */
	private final int vectorSize;
	/**
	 * Type of the variable.
	 */
	public final StdType varType;

	/**
	 * @param type variable type
	 */
	public StdVariableType(StdType type)
	{
		this.varType = type;
		this.vectorSize = 1;
	}

	/**
	 * Create vector variable type.
	 * @param type
	 * @param vectorSize
	 * @throws KernelException thrown when the vector size is incorrect
	 */
	public StdVariableType(StdType type, int vectorSize) throws KernelException
	{
		if (!(vectorSize >= 1 && vectorSize <= 4) && vectorSize != 8 && vectorSize != 16) {
			throw new KernelException(String.format("%d is not a valid size for vector type in OpenCL!", vectorSize));
		}
		this.varType = type;
		this.vectorSize = vectorSize;
	}

	/**
	 * Create standard variable type from a PRISM variable.
	 * @param var
	 */
	public StdVariableType(PrismVariable var)
	{
		varType = getIntType(var.bitsNumber, var.signFlag, var.initValue);
		this.vectorSize = 1;
	}

	/**
	 * Create standard variable type which can contain
	 * values from interval [minimal,maximal]
	 * @param minimal
	 * @param maximal
	 */
	public StdVariableType(long minimal, long maximal)
	{
		Preconditions.checkCondition(minimal <= maximal, "Minimal > maximal!");
		long length = maximal - minimal;
		varType = getIntType(Long.SIZE - Long.numberOfLeadingZeros(length), minimal < 0, minimal);
		this.vectorSize = 1;
	}
	
	private StdType getIntType(long bitsNumber, boolean signFlag, long init)
	{
		//for situations like [1,2], where bitsNumber gives bool -> 1 bit
		long value = (long) Math.pow(2, bitsNumber) + Math.abs(init);
		bitsNumber = Long.SIZE - Long.numberOfLeadingZeros(value);
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
	
	/**
	 * @param value
	 * @return initialize value for variable of type T
	 */
	static public <T extends Number> CLValue initialize(T value)
	{
		return new StdVariableValue<T>(value);
	}

	@Override
	public String getType()
	{
		if (typesWithDirectName.contains(varType)) {
			if (vectorSize > 1) {
				return String.format("%s%d", varType.toString().toLowerCase(), vectorSize);
			} else {
				return varType.toString().toLowerCase();
			}
		} else {
			String type = null;
			switch (varType) {
			case UINT8:
			case INT8:
				type = "char";
				break;
			case INT16:
			case UINT16:
				type = "short";
				break;
			case INT32:
			case UINT32:
				type = "int";
				break;
			case INT64:
			case UINT64:
				type = "long";
				break;
			default:
				break;
			}
			if (varType.isUnsigned()) {
				type = "u" + type;
			}
			if (vectorSize > 1) {
				type += Integer.toString(vectorSize);
			}
			return type;
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
	
	@Override
	public int getSize()
	{
		return varType.getSize();
	}
}