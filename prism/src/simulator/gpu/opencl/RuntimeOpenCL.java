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
import java.util.List;

import prism.PrismException;

import com.nativelibs4java.opencl.CLContext;
import com.nativelibs4java.opencl.CLDevice;
import com.nativelibs4java.opencl.CLException;
import com.nativelibs4java.opencl.CLPlatform;
import com.nativelibs4java.opencl.JavaCL;

import simulator.gpu.Preconditions;
import simulator.gpu.RuntimeDeviceInterface;
import simulator.gpu.RuntimeFrameworkInterface;
import simulator.gpu.automaton.AbstractAutomaton;

public class RuntimeOpenCL implements RuntimeFrameworkInterface
{
	private CLPlatform[] platforms = null;
	private CLDeviceWrapper[] devices = null;
	private CLPlatform currentPlatform = null;
	private CLDeviceWrapper currentDevice = null;
	private CLContext currentContext = null;
	private AbstractAutomaton model = null;
	/**
	 * Constructor. Throws an exception when OpenCL initialization failed.
	 * @throws PrismException
	 */
	public RuntimeOpenCL() throws PrismException
	{
		try {
			platforms = JavaCL.listPlatforms();
			List<CLDeviceWrapper> devs = new ArrayList<>();
			for(CLPlatform platform : platforms)
			{
				CLDevice[] dev = platform.listAllDevices(true);
				for(CLDevice device : dev)
				{
					devs.add(new CLDeviceWrapper(device));
				}
			}
			devices = devs.toArray(new CLDeviceWrapper[devs.size()]);
		}
		catch(CLException exc)
		{
			throw new PrismException("An error has occured!\n" + exc.getMessage());
		}
		catch(Exception exc)
		{
			throw new PrismException("An error has occured!\n" + exc.getMessage());
		}
		catch(Error err)
		{
			if(err.getCause() instanceof CLException) {
				CLException exc = (CLException)err.getCause();
				// CL_PLATFORM_NOT_FOUND_KHR
				if(exc.getCode() == -1001) {
					throw new PrismException("None OpenCL platform has not been found!");
				}
				else {
					throw new PrismException("An error has occured!\n" + exc.getMessage());
				}
			}
			else {
				throw new PrismException("An error has occured!\n" + err.getMessage());
			}
		}
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
		Preconditions.checkNotNull(currentPlatform);
		return currentPlatform.getName() + " " + currentPlatform.getVendor();
	}
	/* (non-Javadoc)
	 * @see simulator.gpu.RuntimeFrameworkInterface#getPlatformNames()
	 */
	public String[] getPlatformNames()
	{
		Preconditions.checkNotNull(platforms);
		String[] names = new String[platforms.length];
		for(int i = 0;i < platforms.length;++i)
		{
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
		for(int i = 0;i < devices.length;++i)
		{
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
	 * @see simulator.gpu.RuntimeFrameworkInterface#selectDevice()
	 */
	@Override
	public void selectDevice(int number)
	{
		Preconditions.checkPositionIndex(number, devices.length);
		currentDevice = devices[number];
	}
	/* (non-Javadoc)
	 * @see simulator.gpu.RuntimeFrameworkInterface#selectDevice()
	 */
	@Override
	public void selectDevice(RuntimeDeviceInterface device)
	{
		Preconditions.checkArgument(device instanceof CLDeviceWrapper);
		currentDevice = (CLDeviceWrapper)device;
	}
	/* (non-Javadoc)
	 * @see simulator.gpu.RuntimeFrameworkInterface#provideModel()
	 */
	@Override
	public void provideModel(AbstractAutomaton model)
	{
		this.model = model;
	}
	/* (non-Javadoc)
	 * @see simulator.gpu.RuntimeFrameworkInterface#getPlatformNumber()
	 */
	@Override
	public int getPlatformNumber()
	{
		return platforms.length;
	}
}