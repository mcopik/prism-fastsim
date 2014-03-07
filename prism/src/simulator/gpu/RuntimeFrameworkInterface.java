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
package simulator.gpu;

import java.util.List;

import parser.State;
import prism.PrismException;
import prism.PrismLog;
import simulator.gpu.automaton.AbstractAutomaton;
import simulator.sampler.Sampler;

public interface RuntimeFrameworkInterface
{
	public enum DeviceType {
		CPU, GPU
	}

	//FRAMEWORK
	/**
	 * Get name of framework
	 * @return string with name
	 */
	String getFrameworkName();

	//PLATFORM
	/**
	 *
	 * @return
	 */
	String[] getPlatformNames();

	int getPlatformNumber();

	String getPlatformInfo(int i);

	//DEVICE
	RuntimeDeviceInterface[] getDevices();

	String[] getDevicesNames();

	RuntimeDeviceInterface getMaxFlopsDevice();

	RuntimeDeviceInterface getMaxFlopsDevice(DeviceType type);

	void selectDevice(RuntimeDeviceInterface device);

	//MODEL

	void simulateProperty(AbstractAutomaton model, List<Sampler> properties, int numberOfSamples) throws PrismException;

	void simulateTest(PrismLog mainLog);

	void setInitialState(State initialState);

	void setMaxPathLength(long maxPathLength);

	void setMainLog(PrismLog mainLog);
}