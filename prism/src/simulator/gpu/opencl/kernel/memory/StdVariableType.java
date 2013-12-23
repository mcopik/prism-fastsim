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

import simulator.gpu.automaton.PrismVariable;

public class StdVariableType implements VariableType
{
	public enum StdType {
		VOID, BOOL, INT8, UINT8, INT16, UINT16, INT32, UINT32, FLOAT, DOUBLE, INT64, UINT64
	}

	public final StdType varType;

	public StdVariableType(StdType type)
	{
		this.varType = type;
	}

	public StdVariableType(PrismVariable var)
	{
		if (var.bitsNumber == 1) {
			varType = StdType.BOOL;
		} else if (var.bitsNumber <= 8) {
			if (var.signFlag) {
				varType = StdType.INT8;
			} else {
				varType = StdType.UINT8;
			}
		} else if (var.bitsNumber <= 16) {
			if (var.signFlag) {
				varType = StdType.INT16;
			} else {
				varType = StdType.UINT16;
			}
		} else if (var.bitsNumber <= 32) {
			if (var.signFlag) {
				varType = StdType.INT32;
			} else {
				varType = StdType.UINT32;
			}
		} else {
			if (var.signFlag) {
				varType = StdType.INT64;
			} else {
				varType = StdType.UINT64;
			}
		}
	}

	@Override
	public String getDeclaration()
	{
		return null;
	}

	@Override
	public String getType()
	{
		return varType.toString().toLowerCase();
	}
}