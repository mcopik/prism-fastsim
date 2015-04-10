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
package simulator.opencl.automaton;

public class PrismVariable
{
	/**
	 * Var name.
	 */
	public final String name;

	/**
	 * True when variable has to support negative values.
	 */
	public final boolean signFlag;

	/**
	 * Initial value for the variable.
	 */
	public final int initValue;

	/**
	 * Number of bits which is necessary to support all possible values of variable.
	 */
	public final int bitsNumber;

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
	}

	@Override
	public String toString()
	{
		StringBuilder builder = new StringBuilder();
		builder.append("Variable ").append(name).append(" initial value: ").append(initValue).append(" encoded with ").append(bitsNumber).append(" bytes");
		return builder.toString();
	}
}