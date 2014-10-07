package simulator.gpu.opencl;

import java.util.Date;
import java.util.EnumSet;

import parser.State;
import prism.PrismException;
import prism.PrismSettings;
import simulator.gpu.opencl.kernel.KernelException;
import simulator.gpu.opencl.kernel.PRNGRandom123;
import simulator.gpu.opencl.kernel.PRNGType;
import simulator.gpu.opencl.kernel.PRNGmwc64x;

import com.nativelibs4java.opencl.CLDevice;
import com.nativelibs4java.opencl.CLDevice.Type;

public class RuntimeConfig
{
	//	static public final int DEFAULT_GLOBAL_WORK_SIZE = 100000;
	//	public int globalWorkSize = DEFAULT_GLOBAL_WORK_SIZE;
	static public final long DEFAULT_MAX_PATH_LENGTH = 1000;
	public long maxPathLength = DEFAULT_MAX_PATH_LENGTH;
	//	static public final int DEFAULT_SAMPLE_OFFSET = 0;
	//	public int sampleOffset = DEFAULT_SAMPLE_OFFSET;
	public State initialState = null;
	public Type deviceType = Type.CPU;
	public PRNGType prngType = null;
	public long prngSeed = 0;

	/**
	 * Direct method - known number of iterations.
	*/
	static public final int DEFAULT_DIRECT_CPU_GWSIZE = 100000;
	public int directMethodGWSizeCPU = DEFAULT_DIRECT_CPU_GWSIZE;
	static public final int DEFAULT_DIRECT_GPU_GWSIZE = 25000;
	public int directMethodGWSizeGPU = DEFAULT_DIRECT_GPU_GWSIZE;
	/**
	 * Number of iterations not known.
	 */
	static public final int DEFAULT_INDIRECT_CPU_GWSIZE = 25000;
	public int inDirectMethodGWSizeCPU = DEFAULT_INDIRECT_CPU_GWSIZE;
	static public final int DEFAULT_INDIRECT_GPU_GWSIZE = 25000;
	public int inDirectMethodGWSizeGPU = DEFAULT_INDIRECT_GPU_GWSIZE;
	static public final int DEFAULT_INDIRECT_RESULT_PERIOD = 1;
	public int inDirectResultCheckPeriod = DEFAULT_INDIRECT_RESULT_PERIOD;
	static public final int DEFAULT_INDIRECT_PATH_PERIOD = 3;
	public int inDirectPathCheckPeriod = DEFAULT_INDIRECT_PATH_PERIOD;

	//	public enum PRNG {
	//		@Deprecated
	//		MERSENNE_TWISTER, MWC64X, RANDOM123
	//	}

	public RuntimeConfig(PrismSettings settings) throws PrismException
	{
		String seed = settings.getString(PrismSettings.OPENCL_SIMULATOR_PRNG_SEED);
		//use user provided by seed or current time
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

	public RuntimeConfig(RuntimeConfig config)
	{
		//		this.globalWorkSize = config.globalWorkSize;
		this.maxPathLength = config.maxPathLength;
		this.initialState = config.initialState;
		this.deviceType = config.deviceType;
		//		this.registerCount = config.registerCount;
		//		this.localMemorySize = config.localMemorySize;
		this.prngType = config.prngType;
		this.prngSeed = config.prngSeed;
	}

	public void configDevice(CLDeviceWrapper dev) throws KernelException
	{
		CLDevice device = dev.getDevice();
		EnumSet<Type> types = device.getType();
		if (types.contains(Type.GPU)) {
			deviceType = Type.GPU;
			//			localMemorySize = device.getLocalMemSize();
		} else if (!types.contains(Type.CPU)) {
			throw new KernelException(String.format("Currently OpenCL Simulator does not support device %s which is not GPU or CPU", dev.getName()));
		}
	}
}