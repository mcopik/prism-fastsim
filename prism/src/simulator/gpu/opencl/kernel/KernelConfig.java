package simulator.gpu.opencl.kernel;

import java.util.EnumSet;

import parser.State;
import simulator.gpu.opencl.CLDeviceWrapper;

import com.nativelibs4java.opencl.CLDevice;
import com.nativelibs4java.opencl.CLDevice.Type;

public class KernelConfig
{
	static public final int DEFAULT_MAX_PATH_LENGTH = 1000;
	public int maxPathLength = DEFAULT_MAX_PATH_LENGTH;
	static public final long DEFAULT_RNG_OFFSET = 1099511627776L;
	public long rngOffset = DEFAULT_RNG_OFFSET;
	public State initialState = null;
	public Type deviceType = Type.CPU;
	public int registerCount = 0;
	public long localMemorySize = 0;

	public void configDevice(CLDeviceWrapper dev) throws KernelException
	{
		CLDevice device = dev.getDevice();
		EnumSet<Type> types = device.getType();
		if (types.contains(Type.GPU)) {
			deviceType = Type.GPU;
			localMemorySize = device.getLocalMemSize();
		} else if (!types.contains(Type.CPU)) {
			throw new KernelException(String.format("Currently OpenCL Simulator does not support device %s which is not GPU or CPU", dev.getName()));
		}
	}
}
