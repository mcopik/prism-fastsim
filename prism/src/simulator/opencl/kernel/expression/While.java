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
package simulator.opencl.kernel.expression;

import prism.Preconditions;

public class While extends ComplexKernelComponent
{
	/**
	 * Condition to test in loop.
	 */
	private Expression condition;
	
	/**
	 * Create simple for loop without end value for counter.
	 * @param counter
	 * @param decreasing
	 */
	public While(Expression condition)
	{
		Preconditions.checkNotNull(condition);
		this.condition = condition;
	}

	@Override
	public boolean hasDeclaration()
	{
		return false;
	}

	@Override
	public KernelComponent getDeclaration()
	{
		return null;
	}

	@Override
	public void accept(VisitorInterface v)
	{
		for (Expression expr : variableDefinitions) {
			expr.accept(v);
		}
		for (KernelComponent component : body) {
			component.accept(v);
		}
	}

	@Override
	protected String createHeader()
	{
		StringBuilder builder = new StringBuilder("while( ");
		builder.append( condition.getSource() );
		builder.append( ")" );
		return builder.toString();
	}
}