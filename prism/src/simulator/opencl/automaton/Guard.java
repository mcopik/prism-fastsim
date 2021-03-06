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

import parser.ast.Expression;

public class Guard
{
	/**
	 * Parser's expression.
	 */
	private Expression guard;

	/**
	 * Right now, doesn't do anything else except storing PRISM's Expression.
	 * Leave the class for future usage.
	 * @param guard
	 */
	public Guard(Expression guard)
	{
		this.guard = guard;
		simplify(guard);
	}

	@Override
	public String toString()
	{
		return guard.toString();
	}

	/**
	 * Leave for potential future usage.
	 */
	private void simplify(Expression guard)
	{

	}
}