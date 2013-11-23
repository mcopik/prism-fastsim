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


public class Variable
{
	/**
	 * Variable type, according to C/C++ language.
	 *
	 */
	public enum Type {
		BOOL,
		UINT8,
		INT8,
		UINT16,
		INT16,
		INT32,
		UINT32
	}
	public final String name;
	public final boolean signFlag;
	public final int initValue;
	public final int bitsNumber;
	public final Type varType;
	/**
	 * Constructor.
	 * @param name variable name
	 * @param low low boundary
	 * @param init initial value
	 * @param bits number of bits necessary to encode this var
	 */
	public Variable(String name,int low,int init,int bits)
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
	private Type getType()
	{
		 if(bitsNumber == 1) {
			 return Type.BOOL;
		 }
		 else if(bitsNumber <= 8) {
			 if(signFlag) {
				 return Type.UINT8;
			 }
			 else {
				 return Type.INT8;
			 }
		 }
		 else if(bitsNumber <= 16) {
			 if(signFlag) {
				 return Type.UINT16;
			 }
			 else {
				 return Type.INT16;
			 }
		 }
		 else {
			 if(signFlag) {
				 return Type.UINT32;
			 }
			 else {
				 return Type.INT32;
			 }
		 }
	}
	@Override
	public String toString()
	{
		StringBuilder builder = new StringBuilder();
		builder.append(varType.toString()).append(" variable ").
				append(name).append(" initial value: ").append(initValue).
				append(" encoded with ").append(bitsNumber).append(" bytes");
		return builder.toString();
	}
}