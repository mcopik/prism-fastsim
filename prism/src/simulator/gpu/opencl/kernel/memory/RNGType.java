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

import java.util.ArrayList;
import java.util.List;

import simulator.gpu.opencl.kernel.expression.Expression;
import simulator.gpu.opencl.kernel.expression.ExpressionGenerator;
import simulator.gpu.opencl.kernel.expression.Include;

public class RNGType implements VariableInterface, UDType
{
	/**
	 * Because Java doesn't have unsigned int.
	 * Or unsigned long.
	 * Or just unsigned arithmetic.
	 * Why?
	 */
	public static final String RNG_MAX = "0xFFFFFFFF";

	static public Expression initializeGenerator(CLVariable rng, CLVariable offset, String baseOffset)
	{
		return new Expression(String.format("MWC64X_SeedStreams(&%s,%s,%s);", rng.varName, offset.varName, baseOffset));
	}

	static public Expression assignRandomInt(CLVariable rng, CLVariable dest, int max)
	{
		return ExpressionGenerator.createAssignment(dest, String.format("floor(((float)MWC64X_NextUint(&%s))*%d/%s)", rng.varName, max, RNG_MAX));
	}

	static public Expression assignRandomFloat(CLVariable rng, CLVariable dest)
	{
		return ExpressionGenerator.createAssignment(dest, String.format("((float)MWC64X_NextUint(&%s))/%s", rng.varName, RNG_MAX));
	}

	static public Expression assignRandomFloat(CLVariable rng, CLVariable dest, CLVariable max)
	{
		return ExpressionGenerator.createAssignment(dest, String.format("((float)MWC64X_NextUint(&%s))*%s/%s", rng.varName, max.varName, RNG_MAX));
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.memory.VariableInterface#getDeclaration()
	 */
	@Override
	public Expression getDeclaration()
	{
		return null;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.memory.VariableInterface#getDefinition()
	 */
	@Override
	public Expression getDefinition()
	{
		return null;
	}

	@Override
	public List<Include> getIncludes()
	{
		List<Include> list = new ArrayList<>();
		list.add(new Include("mwc64x_rng.cl", true));
		return list;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.memory.VariableInterface#getType()
	 */
	@Override
	public String getType()
	{
		return "mwc64x_state_t";
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