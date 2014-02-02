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

import java.util.List;

import simulator.RandomNumberGenerator;
import simulator.gpu.opencl.kernel.expression.Expression;
import simulator.gpu.opencl.kernel.expression.Include;
import simulator.gpu.opencl.kernel.expression.KernelComponent;
import simulator.gpu.opencl.kernel.memory.CLVariable;

import com.nativelibs4java.opencl.CLKernel;

public abstract class PRNGType
{
	protected final String varName;
	protected final List<Include> includes;
	protected final List<CLVariable> additionalArgs;
	protected RandomNumberGenerator random = new RandomNumberGenerator();

	protected PRNGType(String varName, List<Include> includes, List<CLVariable> additionalArgs)
	{
		this.varName = varName;
		this.includes = includes;
		this.additionalArgs = additionalArgs;
	}

	public abstract KernelComponent initializeGenerator();

	public KernelComponent deinitializeGenerator()
	{
		return new Expression("");
	}

	public List<Include> getIncludes()
	{
		return includes;
	}

	public abstract int numbersPerRandomize();

	public abstract Expression randomize() throws KernelException;

	public Expression assignRandomInt(CLVariable dest, int max)
	{
		return assignRandomInt(0, dest, max);
	}

	public abstract Expression assignRandomInt(int randNumber, CLVariable dest, int max);

	public Expression assignRandomFloat(CLVariable dest, CLVariable max)
	{
		return assignRandomFloat(0, dest, max);
	}

	public abstract Expression assignRandomFloat(int randNumber, CLVariable dest, CLVariable max);

	public Expression assignRandomFloat(CLVariable dest)
	{
		return assignRandomFloat(0, dest);
	}

	public abstract Expression assignRandomFloat(int randNumber, CLVariable dest);

	public List<KernelComponent> getAdditionalDefinitions()
	{
		return null;
	}

	public List<CLVariable> getAdditionalInput()
	{
		return additionalArgs;
	}

	public abstract void setKernelArg(CLKernel kernel, int argNumber, long sampleOffset, int globalWorkSize, int localWorkSize);
}