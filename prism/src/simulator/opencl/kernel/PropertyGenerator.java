//==============================================================================
//	
//	Copyright (c) 2002-
//	Authors:
//	* Marcin Copik <mcopik@gmail.com> (RWTH Aachen, formerly Silesian University of Technology)
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
package simulator.opencl.kernel;

public abstract class PropertyGenerator implements KernelComponentGenerator
{
	/**
	 * Main generator.
	 */
	protected KernelGenerator generator = null;
	
	protected StateVector stateVector = null;

	/**
	 * True if there is at least one property.
	 */
	protected boolean activeGenerator = false;
	
	PropertyGenerator(KernelGenerator generator, boolean active)
	{
		this.generator = generator;
		stateVector = generator.getSV();
		activeGenerator = active;
	}
	
	/**
	 * @return true iff generator is active and produces any code
	 */
	public boolean isGeneratorActive()
	{
		return activeGenerator;
	}
	
}
