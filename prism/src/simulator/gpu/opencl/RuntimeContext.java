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
import org.bridj.Pointer;

import prism.PrismLog;
import simulator.gpu.automaton.AbstractAutomaton;
import simulator.gpu.opencl.kernel.Kernel;
import simulator.gpu.opencl.kernel.KernelConfig;
import simulator.gpu.opencl.kernel.KernelException;
import simulator.sampler.Sampler;

import com.nativelibs4java.opencl.CLBuffer;
import com.nativelibs4java.opencl.CLBuildException;
import com.nativelibs4java.opencl.CLContext;
import com.nativelibs4java.opencl.CLEvent;
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
		numberOfSamples = 1000000;
		//config.globalWorkSize = 10000;
		mainLog.println(kernel.getSource());
		mainLog.flush();
		try {
			int samplesProcessed = 0;
			CLProgram program = context.createProgram(kernel.getSource());
			List<CLBuffer<Byte>> resultBuffers = new ArrayList<>();
			//CLBuffer<Integer> pathLenghts = context.createIntBuffer(CLMem.Usage.Output, numberOfSamples);
			Pointer<Integer> pathLengthsData = Pointer.allocateInts((long) numberOfSamples);
			CLBuffer<Integer> pathLengths = context.createBuffer(CLMem.Usage.Output, pathLengthsData, true);
			program.addInclude("src/gpu/");
			program.addInclude("classes/gpu/");
			program.build();
			CLKernel programKernel = program.createKernel("main");
			CLQueue queue = context.createDefaultProfilingQueue();
			int localWorkSize = programKernel.getWorkGroupSize().get(currentDevice.getDevice()).intValue();
			localWorkSize = 128;
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
				//programKernel.setArg(0, offset);

				config.prngType.setKernelArg(programKernel, 0, samplesProcessed, globalWorkSize, localWorkSize);
				int argOffset = config.prngType.kernelArgsNumber();
				programKernel.setArg(argOffset, currentGWSize);
				programKernel.setArg(1 + argOffset, samplesProcessed);
				programKernel.setArg(2 + argOffset, pathLengths);
				/*
					for (int i = 0; i < properties.size(); ++i) {
						programKernel.setObjectArg(i + 4, resultBuffers.get(i));
					}*/
				long start = System.nanoTime();
				/*
				programKernel.enqueueNDRange(queue,
				//global work size
				new int[] { roundUp(localWorkSize, currentGWSize) },
				//local work size
				new int[] { (int) localWorkSize });*/

				CLEvent kernelCompletion = programKernel.enqueueNDRange(queue,
				//global work size
						new int[] { globalWorkSize }, new int[] { localWorkSize });
				//new int[] { roundUp(localWorkSize, currentGWSize) });
				kernelCompletion.waitFor();
				mainLog.println((kernelCompletion.getProfilingCommandEnd() - kernelCompletion.getProfilingCommandStart()) / 100000);
				queue.finish();
				mainLog.println(String.format("%d samples done - time %d ms", roundUp(localWorkSize, currentGWSize), (System.nanoTime() - start) / 1000000));
				samplesProcessed += currentGWSize;
			}
			/*
			for (int i = 0; i < resultBuffers.size(); ++i) {
				NativeList<Byte> bytes = resultBuffers.get(i).read(queue).asList();
				SamplerBoolean sampler = (SamplerBoolean) properties.get(i);
				for (int j = 0; j < bytes.size(); ++j) {
					sampler.addSample(bytes.get(j) == 1);
				}
			}*/
			NativeList<Integer> lengths = pathLengths.read(queue).asList();
			int minPathFound = 1000000;
			int maxPathFound = 0;
			long sum = 0;
			for (Integer i : lengths) {
				sum += i;
				minPathFound = Math.min(minPathFound, i);
				maxPathFound = Math.max(maxPathFound, i);
			}
			mainLog.println(((float) sum) / numberOfSamples);
			mainLog.println(maxPathFound);
			mainLog.println(minPathFound);
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