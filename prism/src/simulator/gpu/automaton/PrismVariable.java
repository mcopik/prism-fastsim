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
package simulator.gpu.automaton;

import simulator.gpu.opencl.kernel.memory.VariableType;

public class PrismVariable
{
	public final String name;
	public final boolean signFlag;
	public final int initValue;
	public final int bitsNumber;
	public final VariableType.Type varType;

	/**
	 * Constructor.
	 * @param name variable name
	 * @param low low boundary
	 * @param init initial value
	 * @param bits number of bits necessary to encode this var
	 */
	public PrismVariable(String name, int low, int init, int bits)
	{
		this.name = name;
		signFlag = low < 0;
		initValue = init;
		bitsNumber = bits;
		varType = getType();
	}

	/**
	 * Return type of this variable in C.
	 * @return enum value
	 */
	private VariableType.Type getType()
	{
		VariableType.Type type;
		if (bitsNumber == 1) {
			type = VariableType.Type.BOOL;
		} else if (bitsNumber <= 8) {
			if (signFlag) {
				type = VariableType.Type.UINT8;
			} else {
				type = VariableType.Type.INT8;
			}
		} else if (bitsNumber <= 16) {
			if (signFlag) {
				type = VariableType.Type.UINT16;
			} else {
				type = VariableType.Type.INT16;
			}
		} else {
			if (signFlag) {
				type = VariableType.Type.UINT32;
			} else {
				type = VariableType.Type.INT32;
			}
		}
		return type;
	}

	@Override
	public String toString()
	{
		StringBuilder builder = new StringBuilder();
		builder.append(varType.toString()).append(" variable ").append(name).append(" initial value: ").append(initValue).append(" encoded with ")
				.append(bitsNumber).append(" bytes");
		return builder.toString();
	}
}