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
package simulator.opencl.kernel;

import java.util.List;

import simulator.RandomNumberGenerator;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.Include;
import simulator.opencl.kernel.expression.KernelComponent;
import simulator.opencl.kernel.memory.CLValue;
import simulator.opencl.kernel.memory.CLVariable;

import com.nativelibs4java.opencl.CLKernel;

public abstract class PRNGType
{
	/**
	 * Name of instance of PRNG object in code.
	 */
	protected final String varName;
	/**
	 * Includes for this PRNG.
	 */
	protected final List<Include> includes;
	/**
	 * Additional kernel arguments.
	 */
	protected final List<CLVariable> additionalArgs;
	/**
	 * PRISM PRNG, used for initialization of OpenCL PRNG.
	 */
	protected RandomNumberGenerator random;
	/**
	 * Construct PRNG with defined name for generator instance, files to include, additional arguments
	 * and seed for PRNG.
	 * @param varName
	 * @param includes
	 * @param additionalArgs
	 * @param seed
	 */
	protected PRNGType(String varName, List<Include> includes, List<CLVariable> additionalArgs, long seed)
	{
		this.varName = varName;
		this.includes = includes;
		this.additionalArgs = additionalArgs;
		random = new RandomNumberGenerator((int) seed);
	}
	/**
	 * 
	 * Construct PRNG with defined name for generator instance, files to include, additional arguments
	 * and seed for PRNG.
	 * @param varName
	 * @param includes
	 * @param additionalArgs
	 */
	protected PRNGType(String varName, List<Include> includes, List<CLVariable> additionalArgs)
	{
		this.varName = varName;
		this.includes = includes;
		this.additionalArgs = additionalArgs;
		random = new RandomNumberGenerator();
	}
	/**
	 * @return necessary includes
	 */
	public List<Include> getIncludes()
	{
		return includes;
	}
	/**
	 * @return additional arguments for kernel
	 */
	public List<CLVariable> getAdditionalInput()
	{
		return additionalArgs;
	}
	
	/**
	 * Methods which may be overridden in derived class.
	 */
	
	/**
	 * @return null when there are no additional definitions
	 */
	public List<KernelComponent> getAdditionalDefinitions()
	{
		return null;
	}

	/**
	 * @return number of kernel arguments used by the PRNG
	 */
	public int kernelArgsNumber()
	{
		return 1;
	}
	
	/**
	 * Not required for every PRNG.
	 * @return deinitialization code, executed after finishing computations
	 */
	public KernelComponent deinitializeGenerator()
	{
		return new Expression("");
	}
	
	/**
	 * Methods which must be defined in derived class.
	 */
	
	/**
	 * @return initialization code for the generator
	 */
	public abstract KernelComponent initializeGenerator();
	/**
	 * @return count of numbers generated for each "randomize()" call
	 */
	public abstract int numbersPerRandomize();
	/**
	 * @return
	 * @throws KernelException
	 */
	public abstract KernelComponent randomize() throws KernelException;
	/**
	 * Get randNumber-th integer from interval [0,max)
	 * @param randNumber
	 * @param max
	 * @return a rvalue
	 */
	public abstract CLValue getRandomInt(Expression randNumber, Expression max);
	/**
	 * Get randNumber-th float from interval [0,1)
	 * @param randNumber
	 * @return a rvalue
	 */
	public abstract CLValue getRandomUnifFloat(Expression randNumber);
	/**
	 * Get randNumber-th float from interval [0,max)
	 * @param randNumber
	 * @param max
	 * @return a rvalue
	 */
	public abstract CLValue getRandomFloat(Expression randNumber, Expression max);
	/**
	 * Configure OpenCL kernel call arguments.
	 * @param kernel
	 * @param argNumber argument position in kernel definition
	 * @param sampleOffset number of already processed samples (avoid repeating of numbers)
	 * @param globalWorkSize
	 * @param localWorkSize
	 */
	public abstract void setKernelArg(CLKernel kernel, int argNumber, int sampleOffset, int globalWorkSize, int localWorkSize);
}