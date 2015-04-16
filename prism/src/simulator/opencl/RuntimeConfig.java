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
package simulator.opencl;

import java.util.Date;
import java.util.EnumSet;

import parser.State;
import prism.PrismException;
import prism.PrismSettings;
import simulator.opencl.kernel.KernelException;
import simulator.opencl.kernel.PRNGRandom123;
import simulator.opencl.kernel.PRNGType;
import simulator.opencl.kernel.PRNGmwc64x;

import com.nativelibs4java.opencl.CLDevice;
import com.nativelibs4java.opencl.CLDevice.Type;

public class RuntimeConfig
{
	/**
	 * Default maximal path length.
	 */
	static public final long DEFAULT_MAX_PATH_LENGTH = 10000;
	public long maxPathLength = DEFAULT_MAX_PATH_LENGTH;
	
	/**
	 * Initial state for the model.
	 */
	public State initialState = null;
	
	/**
	 * Type of device selected for sampling.
	 */
	public Type deviceType = Type.CPU;
	
	/**
	 * Initialize with PRNG's object.
	 * Currently, use only Random123.
	 * mwc64x is too slow.
	 */
	public PRNGType prngType = null;
	
	/**
	 * PRNG's seed - take from settings or from current time.
	 */
	public long prngSeed = 0;

	/**
	 * Direct method - known number of iterations.
	*/
	/**
	 * CPU's global work size (number of samples in one kernel call).
	 */
	static public final int DEFAULT_DIRECT_CPU_GWSIZE = 100000;
	public int directMethodGWSizeCPU = DEFAULT_DIRECT_CPU_GWSIZE;
	
	/**
	 * GPU's global work size (number of samples in one kernel call).
	 */
	static public final int DEFAULT_DIRECT_GPU_GWSIZE = 25000;
	public int directMethodGWSizeGPU = DEFAULT_DIRECT_GPU_GWSIZE;
	
	/**
	 * Number of iterations not known.
	 */
	/**
	 * CPU's global work size (number of samples in one kernel call).
	 */
	static public final int DEFAULT_INDIRECT_CPU_GWSIZE = 25000;
	public int inDirectMethodGWSizeCPU = DEFAULT_INDIRECT_CPU_GWSIZE;
	
	/**
	 * GPU's global work size (number of samples in one kernel call).
	 */
	static public final int DEFAULT_INDIRECT_GPU_GWSIZE = 25000;
	public int inDirectMethodGWSizeGPU = DEFAULT_INDIRECT_GPU_GWSIZE;
	
	/**
	 * The period of reading and analyzing of results buffer.
	 * The bigger the period is, then reading is more rare (performance!),
	 * but the solver is more likely to generate more samples than it is necessary.
	 */
	static public final int DEFAULT_INDIRECT_RESULT_PERIOD = 1;
	public int inDirectResultCheckPeriod = DEFAULT_INDIRECT_RESULT_PERIOD;
	
	/**
	 * The period of reading and analyzing of path's buffer (min/max/avg)
	 * The bigger the period is, then reading is more rare (performance!),
	 * but the buffer becomes much bigger.
	 */
	static public final int DEFAULT_INDIRECT_PATH_PERIOD = 3;
	public int inDirectPathCheckPeriod = DEFAULT_INDIRECT_PATH_PERIOD;

	/**
	 * Configure using only PrismSettings.
	 * @param settings
	 * @throws PrismException
	 */
	public RuntimeConfig(PrismSettings settings) throws PrismException
	{
		String seed = settings.getString(PrismSettings.OPENCL_SIMULATOR_PRNG_SEED);
		//use seed provided by user or current time
		if (seed.isEmpty()) {
			Date date = new Date();
			prngSeed = date.getTime();
		} else {
			try {
				long parsedSeed = Long.parseLong(seed);
				prngSeed = parsedSeed;
			} catch (NumberFormatException e) {
				throw new PrismException("PRNG seed provided in settings is malformed! Description of the problem: " + e.getMessage());
			}
		}
		
		// configure PRNG
		int choice = settings.getChoice(PrismSettings.OPENCL_SIMULATOR_PRNG);
		if (choice == PrismSettings.OPENCL_SIMULATOR_PRNG_CHOICES.RANDOM123.id) {
			prngType = new PRNGRandom123("prng", prngSeed);
		} else {
			prngType = new PRNGmwc64x("prng", prngSeed);
		}
		
		//load other parameters
		directMethodGWSizeCPU = settings.getInteger(PrismSettings.OPENCL_SIMULATOR_DEFAULT_NUM_SAMPLES_CPU);
		directMethodGWSizeGPU = settings.getInteger(PrismSettings.OPENCL_SIMULATOR_DEFAULT_NUM_SAMPLES_GPU);
		inDirectMethodGWSizeCPU = settings.getInteger(PrismSettings.OPENCL_SIMULATOR_DEFAULT_NUM_SAMPLES_CPU_INDIRECT);
		inDirectMethodGWSizeGPU = settings.getInteger(PrismSettings.OPENCL_SIMULATOR_DEFAULT_NUM_SAMPLES_GPU_INDIRECT);
		inDirectPathCheckPeriod = settings.getInteger(PrismSettings.OPENCL_SIMULATOR_PATH_PERIOD);
		inDirectResultCheckPeriod = settings.getInteger(PrismSettings.OPENCL_SIMULATOR_RESULT_PERIOD);
	}
	
	/**
	 * Set proper parameters in config class, for selected device.
	 * @param dev
	 * @throws KernelException
	 */
	public void configDevice(CLDeviceWrapper dev) throws KernelException
	{
		CLDevice device = dev.getDevice();
		EnumSet<Type> types = device.getType();
		if (types.contains(Type.GPU)) {
			deviceType = Type.GPU;
		} else if (!types.contains(Type.CPU)) {
			throw new KernelException(String.format("Currently OpenCL Simulator does not support device %s which is not GPU or CPU", dev.getName()));
		}
	}
}