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

import prism.Preconditions;
import simulator.SMCRuntimeDeviceInterface;

import com.nativelibs4java.opencl.CLContext;
import com.nativelibs4java.opencl.CLDevice;
import com.nativelibs4java.opencl.CLPlatform;
import com.nativelibs4java.opencl.JavaCL;

public class CLDeviceWrapper implements SMCRuntimeDeviceInterface
{
	/**
	 * OpenCL device object.
	 */
	private CLDevice device = null;

	/**
	 * If true, then device name will be: "Platform_name : device_name".
	 * Use it to get a unique name, when the device is available at many platforms
	 * (e.g. Intel's CPU at AMD APP and Intel's OpenCL)
	 */
	private boolean extendedName = false;

	/**
	 * @param device JavaCL's object
	 */
	public CLDeviceWrapper(CLDevice device)
	{
		Preconditions.checkNotNull( device );
		this.device = device;
	}

	@Override
	public String getName()
	{
		return device.getName();
	}

	@Override
	public String getExtendedName()
	{
		if (extendedName) {
			return getPlatformName() + " : " + getName();
		} else {
			return getName();
		}
	}

	@Override
	public String getPlatformName()
	{
		return device.getPlatform().getName();
	}

	@Override
	public String getFrameworkVersion()
	{
		return device.getOpenCLCVersion();
	}

	/**
	 * @return JavaCL's context, created at this device
	 */
	public CLContext createDeviceContext()
	{
		return JavaCL.createContext(null, device);
	}

	/**
	 * @return JavaCL's platform object
	 */
	public CLPlatform getDevicePlatform()
	{
		return device.getPlatform();
	}

	/**
	 * @return JavaCL's device object
	 */
	public CLDevice getDevice()
	{
		return device;
	}

	@Override
	public String toString()
	{
		return getName();
	}

	/**
	 * When set, then device's name will be:
	 * "Platform: Device"
	 * Used when the device exists in many platforms, to get a unique identification.
	 */
	public void extendUniqueName()
	{
		extendedName = true;
	}

	@Override
	public boolean isCPU()
	{
		return device.getType().contains(CLDevice.Type.CPU);
	}

	@Override
	public boolean isGPU()
	{
		return device.getType().contains(CLDevice.Type.GPU);
	}
}