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
package simulator;

import java.util.List;

import parser.State;
import parser.ast.Expression;
import parser.ast.ModulesFile;
import prism.PrismException;
import simulator.sampler.Sampler;

/**
 * Interface for runtime implementations of stochastic model checking.
 * Supports browsing and selecting of platforms, devices and generation of samples.
 * 
 * Currently PRISM provides two implementations:
 * - SimulatorEngine - default, serial runtime in Java
 * - OpenCLSimulatorEngine - highly parallel, runs on CPU and GPU
 */
public interface SMCRuntimeInterface
{
	/**
	 * Supported device types.
	 */
	public enum DeviceType {
		CPU, GPU
	}

	/**
	 * @return name of the framework
	 */
	String getFrameworkName();

	/**
	 * @return name of available platforms; null if the runtime doesn't support platforms
	 */
	String[] getPlatformNames();

	/**
	 * @return number of available platforms
	 */
	int getPlatformNumber();

	/**
	 * @param i number of platform
	 * @return string with detailed description of i-th platform; null if the runtime doesn't support platforms
	 */
	String getPlatformInfo(int i);

	/**
	 * @return array with available devices; null if the runtime doesn't support devices
	 */
	SMCRuntimeDeviceInterface[] getDevices();

	/**
	 * @return array with device names; null if the runtime doesn't support devices
	 */
	String[] getDevicesNames();

	/**
	 * @return array with device extended names; null if the runtime doesn't support devices
	 */
	String[] getDevicesExtendedNames();

	/**
	 * Get the device with maximum FLOPS.
	 * @return theoretically "best" device; null if the runtime doesn't support devices
	 */
	SMCRuntimeDeviceInterface getMaxFlopsDevice();

	/**
	 * Get the device of selected type with maximum FLOPS.
	 * @param type type of device
	 * @return theoretically "best" device; null if the runtime doesn't support devices
	 */
	SMCRuntimeDeviceInterface getMaxFlopsDevice(DeviceType type);

	/**
	 * Select device used by the framework.
	 * @param device
	 */
	void selectDevice(SMCRuntimeDeviceInterface device);

	/**
	 * Check if this model can be processed by this runtime.
	 * Used for additional restrictions, independent from general stochastic model checker.
	 * @param modulesFile
	 * @throws PrismException
	 */
	void checkModelForAMC(ModulesFile modulesFile) throws PrismException;

	/**
	 * Check if this property can be processed by this runtime.
	 * Used for additional restrictions, independent from general stochastic model checker.
	 * @param expr
	 * @throws PrismException
	 */
	void checkPropertyForAMC(Expression expr) throws PrismException;

	/**
	 * Execute sampling for the set of currently loaded properties.
	 * Sample paths are from the specified initial state and maximum length.
	 * Termination of the sampling process occurs when the SimulationMethod object
	 * for all properties indicate that it is finished.
	 * @param modulesFile
	 * @param properties
	 * @param initialState
	 * @param maxPathLength
	 * @return number of processed samples
	 * @throws PrismException
	 */
	int doSampling(ModulesFile modulesFile, List<Sampler> properties, State initialState, long maxPathLength) throws PrismException;
}