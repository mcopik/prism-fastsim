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
package simulator.gpu.opencl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import parser.State;
import prism.Preconditions;
import prism.PrismException;
import prism.PrismLog;
import prism.PrismSettings;
import simulator.gpu.RuntimeDeviceInterface;
import simulator.gpu.RuntimeFrameworkInterface;
import simulator.gpu.automaton.AbstractAutomaton;
import simulator.gpu.opencl.kernel.PRNGRandom123;
import simulator.sampler.Sampler;

import com.nativelibs4java.opencl.CLDevice;
import com.nativelibs4java.opencl.CLException;
import com.nativelibs4java.opencl.CLPlatform;
import com.nativelibs4java.opencl.CLPlatform.DeviceFeature;
import com.nativelibs4java.opencl.JavaCL;

public class RuntimeOpenCL implements RuntimeFrameworkInterface
{

	private final CLPlatform[] platforms;
	private final CLDeviceWrapper[] devices;
	private CLPlatform currentPlatform = null;
	private List<CLDeviceWrapper> currentDevices = new ArrayList<>();
	List<RuntimeContext> currentContexts = null;
	private long maxPathLength = 0;
	private State initialState = null;
	private PrismLog mainLog = null;
	private PrismSettings prismSettings = null;
	private int minPathFound = 0;
	private int maxPathFound = 0;
	private float avgPathFound = 0;

	/**
	 * Constructor. Throws an exception when OpenCL initialization failed.
	 * @throws PrismException
	 */
	public RuntimeOpenCL() throws PrismException
	{
		platforms = init();
		List<CLDeviceWrapper> devs = new ArrayList<>();
		for (CLPlatform platform : platforms) {
			CLDevice[] dev = platform.listAllDevices(true);
			for (CLDevice device : dev) {
				devs.add(new CLDeviceWrapper(device));
			}
		}
		devices = devs.toArray(new CLDeviceWrapper[devs.size()]);
	}

	private CLPlatform[] init() throws PrismException
	{
		CLPlatform[] platforms;
		try {
			platforms = JavaCL.listPlatforms();
		} catch (CLException exc) {
			throw new PrismException("An error has occured!\n" + exc.getMessage());
		} catch (Exception exc) {
			throw new PrismException("An error has occured!\n" + exc.getMessage());
		} catch (Error err) {
			if (err.getCause() instanceof CLException) {
				CLException exc = (CLException) err.getCause();
				// CL_PLATFORM_NOT_FOUND_KHR
				if (exc.getCode() == -1001) {
					throw new PrismException("None OpenCL platform has not been found!");
				} else {
					throw new PrismException("An error has occured!\n" + exc.getMessage());
				}
			} else {
				throw new PrismException("An error has occured!\n" + err.getMessage());
			}
		}
		return platforms;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.RuntimeFrameworkInterface#getFrameworkName()
	 */
	@Override
	public String getFrameworkName()
	{
		return "OpenCL";
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.RuntimeFrameworkInterface#getPlatformInfo()
	 */
	@Override
	public String getPlatformInfo(int platformNumber)
	{
		Preconditions.checkIndex(platformNumber, platforms.length, String.format("%d is not valid platform number", platformNumber));
		return currentPlatform.getName() + " " + currentPlatform.getVendor();
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.RuntimeFrameworkInterface#getPlatformNames()
	 */
	public String[] getPlatformNames()
	{
		String[] names = new String[platforms.length];
		for (int i = 0; i < platforms.length; ++i) {
			names[i] = platforms[i].getName();
		}
		return names;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.RuntimeFrameworkInterface#getDevices()
	 */
	@Override
	public RuntimeDeviceInterface[] getDevices()
	{
		return devices;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.RuntimeFrameworkInterface#getDevicesNames()
	 */
	@Override
	public String[] getDevicesNames()
	{
		String[] result = new String[devices.length];
		for (int i = 0; i < devices.length; ++i) {
			result[i] = devices[i].getName();
		}
		return result;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.RuntimeFrameworkInterface#getMaxFlopsDevice()
	 */
	@Override
	public RuntimeDeviceInterface getMaxFlopsDevice()
	{
		return new CLDeviceWrapper(JavaCL.getBestDevice());
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.RuntimeFrameworkInterface#getMaxFlopsDevice()
	 */
	@Override
	public RuntimeDeviceInterface getMaxFlopsDevice(DeviceType type)
	{
		if (type == DeviceType.CPU) {
			return new CLDeviceWrapper(JavaCL.getBestDevice(DeviceFeature.CPU));
		} else {
			return new CLDeviceWrapper(JavaCL.getBestDevice(DeviceFeature.GPU));
		}
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.RuntimeFrameworkInterface#selectDevice()
	 */
	@Override
	public void selectDevice(RuntimeDeviceInterface device)
	{
		Preconditions.checkCondition(device instanceof CLDeviceWrapper, "RuntimeOpenCL can't select non-opencl device");
		currentDevices.add((CLDeviceWrapper) device);
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.RuntimeFrameworkInterface#getPlatformNumber()
	 */
	@Override
	public int getPlatformNumber()
	{
		return platforms.length;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.RuntimeFrameworkInterface#simulateProperty()
	 */
	@Override
	public int simulateProperty(AbstractAutomaton model, List<Sampler> properties) throws PrismException
	{
		Preconditions.checkNotNull(mainLog, "");
		Preconditions.checkCondition(maxPathLength > 0, "");
		RuntimeConfig config = new RuntimeConfig();
		if (initialState != null) {
			config.initialState = initialState;
		}
		config.maxPathLength = maxPathLength;
		if (prismSettings == null) {
			config.prngType = new PRNGRandom123("rng");
			Date date = new Date();
			config.prngSeed = date.getTime();
		} else {
			config.prngType = new PRNGRandom123("rng");
			Date date = new Date();
			config.prngSeed = date.getTime();
			//TODO:
		}
		int samplesProcessed = 0;
		try {
			currentContexts = new ArrayList<>();
			for (CLDeviceWrapper device : currentDevices) {
				RuntimeContext currentContext = new RuntimeContext(device, mainLog);
				currentContext.createKernel(model, properties, config);
				currentContexts.add(currentContext);
			}
			mainLog.println("Using " + currentContexts.size() + " OpenCL devices.");
			for (RuntimeContext context : currentContexts) {
				context.runSimulation();
			}
			for (RuntimeContext context : currentContexts) {
				mainLog.println(String.format("Sampling: %d samples in %d miliseconds.", context.getSamplesProcessed(), context.getTime()));
				mainLog.println(String.format("Path length: min %d, max %d, avg %f", context.getMinPathLength(), context.getMaxPathLength(),
						context.getAvgPathLength()));
				samplesProcessed += context.getSamplesProcessed();
			}
		} finally {
			for (RuntimeContext context : currentContexts) {
				context.release();
				//context.currentDevice.getDevice().release();
			}
		}
		return samplesProcessed;
	}

	//	@Override
	//	public void simulateTest(PrismLog mainLog)
	//	{
	//		currentContexts = createContexts();
	//		mainLog.println("Using " + currentContexts.size() + " OpenCL devices.");
	//		for (RuntimeContext context : currentContexts) {
	//			mainLog.println(context);
	//		}
	//		for (RuntimeContext context : currentContexts) {
	//			context.createTestKernel();
	//		}
	//		for (RuntimeContext context : currentContexts) {
	//			context.runTestSimulation(mainLog);
	//		}
	//	}

	@Override
	public void setInitialState(State initialState)
	{
		this.initialState = initialState;
	}

	@Override
	public void setMaxPathLength(long maxPathLength)
	{
		this.maxPathLength = maxPathLength;
	}

	@Override
	public void setMainLog(PrismLog mainLog)
	{
		this.mainLog = mainLog;
	}

	@Override
	public void setPrismSettings(PrismSettings settings)
	{
		this.prismSettings = settings;
	}
}