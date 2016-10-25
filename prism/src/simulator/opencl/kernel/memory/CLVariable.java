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

import prism.Preconditions;
import simulator.opencl.kernel.expression.Expression;

public class CLVariable implements CLValue
{
	/**
	 * OpenCL allows for declarating variables in:
	 * - private (registers)
	 * - local
	 * - global
	 * memory.
	 */
	public enum Location {
		REGISTER, LOCAL, GLOBAL
	}
	/**
	 * Variable name.
	 */
	public final String varName;
	/**
	 * Variable type.
	 */
	public final VariableTypeInterface varType;
	/**
	 * Init value, can be null (the default initialization is specified by OpenCL).
	 */
	private Expression initValue = null;
	/**
	 * DON'T declare this variable.
	 */
	private boolean dontDeclare = false;
	/**
	 * Variable location in memory.
	 */
	public Location memLocation = Location.REGISTER;
	/**
	 * Construct OpenCL variable with given name and type.
	 * @param varType
	 * @param varName
	 */
	public CLVariable(VariableTypeInterface varType, String varName)
	{
		Preconditions.checkNotNull(varType);
		this.varName = varName;
		this.varType = varType;
	}
	/**
	 * @param loc new location
	 */
	public void setMemoryLocation(Location loc)
	{
		memLocation = loc;
	}
	/**
	 * DON'T add declaration of this variable.
	 */
	public void dontDeclareVar()
	{
		dontDeclare = true;
	}
	/**
	 * @param value initialize with a value
	 */
	public void setInitValue(CLValue value)
	{
		initValue = value.getSource();
	}
	/**
	 * @param value initialize with an expression
	 */
	public void setInitValue(Expression value)
	{
		initValue = value;
	}
	/**
	 * @return get a pointer type to this variable
	 */
	public VariableTypeInterface getPointer()
	{
		if (varType.isArray()) {
			return new ArrayType((ArrayType) varType);
		} else {
			return new PointerType(varType);
		}
	}
	/**
	 * @return address of this variable
	 */
	public CLVariable convertToPointer()
	{
		return new CLVariable(getPointer(), "&" + varName);
	}
	/**
	 * @param index
	 * @return array element specified by an index; null when it's not an array
	 */
	public CLVariable accessElement(Expression index)
	{
		Preconditions.checkNotNull(index);
		return varType.accessElement(this, index);
	}
	/**
	 * @param fieldName
	 * @return structure field specified by its name; null when it's not structure
	 */
	public CLVariable accessField(String fieldName)
	{
		Preconditions.checkNotNull(fieldName);
		return varType.accessField(this.varName, fieldName);
	}
	/**
	 * Useful for assignments.
	 * @return variable name
	 */
	public Expression getName()
	{
		return new Expression(varName);
	}
	/**
	 * @param type
	 * @return C casting of the variable to given type
	 */
	public Expression cast(String type)
	{
		return new Expression(String.format("((%s)%s)", type, varName));
	}
	/**
	 * Works for pointers and arrays.
	 * @return dereferenced variable
	 */
	public CLVariable dereference()
	{
		Preconditions.checkCondition(varType instanceof PointerType || varType instanceof ArrayType, "Dereference on non-pointer!");
		if (varType instanceof PointerType) {
			return new CLVariable(((PointerType) varType).getInternalType(), (String.format("(*%s)", varName)));
		} else {
			return new CLVariable(((ArrayType) varType).getInternalType(), (String.format("(*%s)", varName)));
		}
	}
	
	/**
	 * @return variable declaration, i.e. without initialization
	 */
	public Expression getDeclaration()
	{
		if (dontDeclare) {
			return new Expression("");
		}
		StringBuilder builder = new StringBuilder();
		if (memLocation == Location.LOCAL) {
			builder.append("__local ");
		} else if (memLocation == Location.GLOBAL) {
			builder.append("__global ");
		}
		builder.append(varType.declareVar(varName));
		builder.append(";");
		return new Expression(builder.toString());
	}
	
	/** 
	 * @return variable definition, i.e. with initial value
	 */
	public Expression getDefinition()
	{
		if (dontDeclare) {
			return new Expression("");
		}
		StringBuilder builder = new StringBuilder();
		if (memLocation == Location.LOCAL) {
			builder.append("__local ");
		} else if (memLocation == Location.GLOBAL) {
			builder.append("__global ");
		}
		builder.append(varType.declareVar(varName));
		if (initValue != null) {
			builder.append(" = ").append(initValue.getSource());
		}
		builder.append(";");
		return new Expression(builder.toString());
	}

	@Override
	public boolean validateAssignmentTo(VariableTypeInterface type)
	{
		//TODO: implement
		return false;
	}

	@Override
	public Expression getSource()
	{
		return new Expression(varName);
	}
	
	public int getSize()
	{
		return varType.getSize();
	}
}