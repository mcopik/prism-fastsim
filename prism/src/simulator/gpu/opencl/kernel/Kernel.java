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
package simulator.gpu.opencl.kernel;

import simulator.gpu.automaton.AbstractAutomaton;

public class Kernel
{
	/**
	 * BasicDebug_Kernel.cl from AMDAPP's samples.
	 */
	public final static String TEST_KERNEL = "__kernel void main() { \n" + "uint globalID = get_global_id(0); \n" + "uint groupID = get_group_id(0);  \n"
			+ "uint localID = get_local_id(0); \n" + "printf(\"the global ID of this thread is : %d\\n\",globalID); \n" + "}";
	public String kernelSource;

	public Kernel(AbstractAutomaton model)
	{

	}

	private Kernel(String source)
	{
		kernelSource = source;
	}

	public static Kernel createTestKernel()
	{
		return new Kernel(TEST_KERNEL);
	}

	public String getSource()
	{
		return kernelSource;
	}
}