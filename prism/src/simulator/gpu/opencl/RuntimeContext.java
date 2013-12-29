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

import prism.PrismLog;
import simulator.gpu.automaton.AbstractAutomaton;
import simulator.gpu.opencl.kernel.Kernel;
import simulator.gpu.opencl.kernel.KernelConfig;
import simulator.gpu.opencl.kernel.KernelException;
import simulator.gpu.property.Property;

import com.nativelibs4java.opencl.CLBuildException;
import com.nativelibs4java.opencl.CLContext;
import com.nativelibs4java.opencl.CLKernel;
import com.nativelibs4java.opencl.CLProgram;
import com.nativelibs4java.opencl.CLQueue;

/**
 * @author mcopik
 *
 */
public class RuntimeContext
{
	CLDeviceWrapper currentDevice = null;
	CLContext context = null;
	Kernel kernel = null;

	public RuntimeContext(CLDeviceWrapper device)
	{
		currentDevice = device;
		context = currentDevice.createDeviceContext();
	}

	public void createKernel(AbstractAutomaton automaton, Property[] properties) throws KernelException
	{
		KernelConfig config = new KernelConfig();
		config.configDevice(currentDevice);
		kernel = new Kernel(config, automaton, properties);
	}

	public void createTestKernel()
	{
		kernel = Kernel.createTestKernel();
	}

	public void runSimulation(PrismLog mainLog)
	{
		mainLog.println(kernel.getSource());
		try {
			CLProgram program = context.createProgram(kernel.getSource());
			program.addInclude("include/");
			program.addInclude("classes/include/");
			program.build();
			CLKernel programKernel = program.createKernel("main");
			CLQueue queue = context.createDefaultQueue();
			programKernel.enqueueNDRange(queue, new int[] { 100, 100 });
			queue.finish();
		} catch (CLBuildException exc) {
			mainLog.println("Program build error: " + exc.getMessage());
		} catch (Exception exc) {
			mainLog.println(exc.getMessage());
		} catch (Error exc) {
			mainLog.println(exc.getMessage());
		}
	}

	public void runTestSimulation(PrismLog mainLog)
	{
		mainLog.println(kernel.getSource());
		try {
			CLProgram program = context.createProgram(kernel.getSource()).build();
			CLKernel programKernel = program.createKernel("main");
			CLQueue queue = context.createDefaultQueue();
			programKernel.enqueueNDRange(queue, new int[] { 100, 100 });
			queue.finish();
		} catch (CLBuildException exc) {
			mainLog.println("Program build error: " + exc.getMessage());
		}

	}

	public String toString()
	{
		StringBuilder builder = new StringBuilder();
		builder.append("Runtime context on platform: ").append(currentDevice.getDevicePlatform().getName());
		builder.append(" with device: ").append(currentDevice).append("\n");
		return builder.toString();
	}
}