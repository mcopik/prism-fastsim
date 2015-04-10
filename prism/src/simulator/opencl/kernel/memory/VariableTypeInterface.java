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

import simulator.opencl.kernel.expression.Expression;

public interface VariableTypeInterface
{
	/**
	 * @return true when the variable is a structure
	 */
	boolean isStructure();
	
	/**
	 * @return true when the variable is an array
	 */
	boolean isArray();
	
	/**
	 * @param varName
	 * @param fieldName
	 * @return field in a structure, specified by a name
	 */
	public CLVariable accessField(String varName, String fieldName);
	
	/**
	 * @param var
	 * @param index
	 * @return element in an array, specified by the index
	 */
	public CLVariable accessElement(CLVariable var, Expression index);

	/**
	 * @param varName
	 * @return variable declaration
	 */
	String declareVar(String varName);
	
	/**
	 * @return type name 
	 */
	String getType();
}