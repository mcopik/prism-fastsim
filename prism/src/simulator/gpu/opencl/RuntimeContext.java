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

import org.bridj.NativeList;

import prism.PrismLog;
import simulator.gpu.automaton.AbstractAutomaton;
import simulator.gpu.opencl.kernel.Kernel;
import simulator.gpu.opencl.kernel.KernelConfig;
import simulator.gpu.opencl.kernel.KernelException;
import simulator.sampler.Sampler;
import simulator.sampler.SamplerBoolean;

import com.nativelibs4java.opencl.CLBuffer;
import com.nativelibs4java.opencl.CLBuildException;
import com.nativelibs4java.opencl.CLContext;
import com.nativelibs4java.opencl.CLKernel;
import com.nativelibs4java.opencl.CLMem;
import com.nativelibs4java.opencl.CLProgram;
import com.nativelibs4java.opencl.CLQueue;

public class RuntimeContext
{
	CLDeviceWrapper currentDevice = null;
	CLContext context = null;
	Kernel kernel = null;
	KernelConfig config = null;
	List<Sampler> properties = null;
	int samplesOffset = 0;

	public RuntimeContext(CLDeviceWrapper device)
	{
		currentDevice = device;
		context = currentDevice.createDeviceContext();
	}

	public void createKernel(AbstractAutomaton automaton, List<Sampler> properties, KernelConfig config) throws KernelException
	{
		this.config = config;
		this.config.configDevice(currentDevice);
		this.properties = properties;
		kernel = new Kernel(this.config, automaton, properties);
	}

	public void createTestKernel()
	{
		kernel = Kernel.createTestKernel();
	}

	public void setSamplesOffset(int numberOfSamples)
	{
		samplesOffset = numberOfSamples;
	}

	public void runSimulation(int numberOfSamples, PrismLog mainLog)
	{
		//TODO: ERASE THIS!
		numberOfSamples = 1;
		mainLog.println(kernel.getSource());
		try {
			int samplesProcessed = 0;
			CLProgram program = context.createProgram(kernel.getSource());
			long offset = config.sampleOffset * config.rngOffset;
			List<CLBuffer<Byte>> resultBuffers = new ArrayList<>();

			program.addInclude("include/");
			program.addInclude("classes/include/");
			program.build();
			CLKernel programKernel = program.createKernel("main");
			CLQueue queue = context.createDefaultQueue();
			int localWorkSize = programKernel.getWorkGroupSize().get(currentDevice.getDevice()).intValue();
			int globalWorkSize = 0;

			if (config.globalWorkSize > numberOfSamples) {
				globalWorkSize = roundUp(localWorkSize, numberOfSamples);
			} else {
				globalWorkSize = roundUp(localWorkSize, config.globalWorkSize);
			}
			for (int i = 0; i < properties.size(); ++i) {
				resultBuffers.add(context.createByteBuffer(CLMem.Usage.Output, numberOfSamples));
			}

			while (samplesProcessed < numberOfSamples) {
				int currentGWSize = (int) Math.min(globalWorkSize, numberOfSamples - samplesProcessed);
				programKernel.setArg(0, offset);
				programKernel.setArg(1, currentGWSize);
				programKernel.setArg(2, samplesProcessed);
				for (int i = 0; i < properties.size(); ++i) {
					programKernel.setObjectArg(i + 3, resultBuffers.get(i));
				}
				programKernel.enqueueNDRange(queue,
				//global work size
						new int[] { roundUp(localWorkSize, currentGWSize) },
						//local work size
						new int[] { (int) localWorkSize });
				queue.finish();
				offset += config.rngOffset * currentGWSize;
				samplesProcessed += currentGWSize;
			}
			for (int i = 0; i < resultBuffers.size(); ++i) {
				NativeList<Byte> bytes = resultBuffers.get(i).read(queue).asList();
				SamplerBoolean sampler = (SamplerBoolean) properties.get(i);
				for (int j = 0; j < bytes.size(); ++j) {
					sampler.addSample(bytes.get(j) == 1);
				}
			}
			for (CLBuffer<Byte> buffer : resultBuffers) {
				buffer.release();
			}
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

	private int roundUp(int groupSize, int globalSize)
	{
		int r = globalSize % groupSize;
		if (r == 0) {
			return globalSize;
		} else {
			return globalSize + groupSize - r;
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