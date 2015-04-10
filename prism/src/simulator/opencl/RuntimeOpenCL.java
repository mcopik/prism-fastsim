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
package simulator.opencl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import parser.State;
import parser.ast.Expression;
import parser.ast.ExpressionReward;
import parser.ast.ModulesFile;
import prism.ModelType;
import prism.Preconditions;
import prism.PrismComponent;
import prism.PrismException;
import prism.PrismLog;
import prism.PrismSettings;
import simulator.SMCRuntimeDeviceInterface;
import simulator.SMCRuntimeInterface;
import simulator.opencl.automaton.AbstractAutomaton;
import simulator.opencl.automaton.CTMC;
import simulator.opencl.automaton.DTMC;
import simulator.sampler.Sampler;

import com.nativelibs4java.opencl.CLDevice;
import com.nativelibs4java.opencl.CLException;
import com.nativelibs4java.opencl.CLPlatform;
import com.nativelibs4java.opencl.CLPlatform.DeviceFeature;
import com.nativelibs4java.opencl.JavaCL;

public class RuntimeOpenCL extends PrismComponent implements SMCRuntimeInterface
{

	/**
	 * Represents Prism's object of current automaton.
	 */
	private ModulesFile modulesFile;
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
	public RuntimeOpenCL(PrismComponent prism) throws PrismException
	{
		super(prism);
		platforms = init();
		List<CLDeviceWrapper> devs = new ArrayList<>();
		//detect the same device in different platforms
		Map<String, CLDeviceWrapper> devices = new HashMap<>();
		for (CLPlatform platform : platforms) {
			CLDevice[] dev = platform.listAllDevices(true);
			for (CLDevice device : dev) {
				CLDeviceWrapper wrapper = new CLDeviceWrapper(device);
				devs.add(wrapper);
				if (devices.containsKey(wrapper.getName())) {
					devices.get(wrapper.getName()).extendUniqueName();
					wrapper.extendUniqueName();
				} else {
					devices.put(wrapper.getName(), wrapper);
				}
			}
		}
		this.devices = devs.toArray(new CLDeviceWrapper[devs.size()]);

		mainLog = prism.getLog();
		prismSettings = prism.getSettings();
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
	public SMCRuntimeDeviceInterface[] getDevices()
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
	 * @see simulator.gpu.RuntimeFrameworkInterface#getDevicesExtendedNames()
	 */
	@Override
	public String[] getDevicesExtendedNames()
	{
		String[] result = new String[devices.length];
		for (int i = 0; i < devices.length; ++i) {
			result[i] = devices[i].getExtendedName();
		}
		return result;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.RuntimeFrameworkInterface#getMaxFlopsDevice()
	 */
	@Override
	public SMCRuntimeDeviceInterface getMaxFlopsDevice()
	{
		return new CLDeviceWrapper(JavaCL.getBestDevice());
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.RuntimeFrameworkInterface#getMaxFlopsDevice()
	 */
	@Override
	public SMCRuntimeDeviceInterface getMaxFlopsDevice(DeviceType type)
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
	public void selectDevice(SMCRuntimeDeviceInterface device)
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

	/**
	 * Create automaton object using PRISM object
	 * @param modulesFile
	 * @throws PrismException
	 */
	private AbstractAutomaton loadModel(ModulesFile modulesFile) throws PrismException
	{
		checkModelForAMC(modulesFile);
		this.modulesFile = modulesFile;
		if (modulesFile.getModelType() == ModelType.DTMC) {
			return new DTMC(modulesFile);
		} else {
			return new CTMC(modulesFile);
		}
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.RuntimeFrameworkInterface#simulateProperty()
	 */
	@Override
	public int doSampling(ModulesFile modulesFile, List<Sampler> properties, State initialState, long maxPathLength) throws PrismException
	{
		Preconditions.checkCondition(maxPathLength > 0, "");
		AbstractAutomaton model = loadModel(modulesFile);
		/**
		 * Configure simulator.
		 */
		RuntimeConfig config = new RuntimeConfig(prismSettings);
		if (initialState != null) {
			config.initialState = initialState;
		}
		config.maxPathLength = maxPathLength;
		int samplesProcessed = 0;
		try {
			currentContexts = new ArrayList<>();
			mainLog.println("Using " + currentDevices.size() + " OpenCL device(s):");
			int counter = 1;
			for (CLDeviceWrapper device : currentDevices) {
				mainLog.println(Integer.toString(counter++) + " : " + device.getName());
				RuntimeContext currentContext = new RuntimeContext(device, mainLog);
				currentContext.createKernel(model, properties, config);
				currentContexts.add(currentContext);
			}
			mainLog.flush();
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

	@Override
	public void checkModelForAMC(ModulesFile modulesFile) throws PrismException
	{
		if (modulesFile.getModelType() != ModelType.DTMC && modulesFile.getModelType() != ModelType.CTMC) {
			throw new PrismException("Currently only DTMC/CTMC is supported!");
		}
	}

	@Override
	public void checkPropertyForAMC(Expression expr) throws PrismException
	{
		if (expr instanceof ExpressionReward) {
			throw new PrismException("Currently reward properties are not supported in OpenCL simulator.");
		}
	}
}
