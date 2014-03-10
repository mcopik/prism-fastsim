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

import prism.Pair;
import prism.Preconditions;
import prism.PrismException;
import prism.PrismLog;
import simulator.gpu.automaton.AbstractAutomaton;
import simulator.gpu.opencl.kernel.Kernel;
import simulator.gpu.opencl.kernel.KernelException;
import simulator.method.ACIiterations;
import simulator.method.APMCMethod;
import simulator.method.CIMethod;
import simulator.method.CIiterations;
import simulator.method.SPRTMethod;
import simulator.method.SimulationMethod;
import simulator.sampler.Sampler;
import simulator.sampler.SamplerBoolean;

import com.nativelibs4java.opencl.CLBuffer;
import com.nativelibs4java.opencl.CLBuildException;
import com.nativelibs4java.opencl.CLContext;
import com.nativelibs4java.opencl.CLDevice.Type;
import com.nativelibs4java.opencl.CLEvent;
import com.nativelibs4java.opencl.CLKernel;
import com.nativelibs4java.opencl.CLMem;
import com.nativelibs4java.opencl.CLProgram;
import com.nativelibs4java.opencl.CLQueue;

public class RuntimeContext
{
	private abstract class ContextState
	{
		public int numberOfSamplesPerIteration = 25000;
		public List<Sampler> properties;
		protected int globalWorkSize;
		protected boolean finished = false;
		protected List<CLEvent> events = new ArrayList<>();
		protected int samplesProcessed = 0;

		protected ContextState(List<Sampler> properties, int gwSize, int lwSize)
		{
			this.properties = properties;
			this.globalWorkSize = roundUp(lwSize, gwSize);
		}

		protected abstract void createBuffers();

		public boolean hasFinished()
		{
			return finished;
		}

		protected abstract boolean processResults(CLQueue queue) throws PrismException;

		public abstract void enqueueKernel(CLQueue queue);

		protected void enqueueKernel(CLQueue queue, int gwSize)
		{

		}

		protected void readResults(CLQueue queue, int start, int samples) throws PrismException
		{
			List<Pair<SamplerBoolean, Pointer<Byte>>> bytes = new ArrayList<>();
			List<CLEvent> readEvents = new ArrayList<>();
			/**
			 * For each active sampler, add a pair of sampler and pointer to read data.
			 */
			for (int i = 0; i < properties.size(); ++i) {
				SamplerBoolean sampler = (SamplerBoolean) properties.get(i);
				if (!sampler.isCurrentValueKnown()) {
					bytes.add(new Pair<SamplerBoolean, Pointer<Byte>>(sampler, Pointer.allocateBytes(samples)));
					readEvents.add(resultBuffers.get(i).read(queue, start, samples, bytes.get(i).second, false));
				}
			}
			/**
			 * Wait for reading.
			 */
			CLEvent.waitFor(readEvents.toArray(new CLEvent[events.size()]));
			/**
			 * Add read data to sampler.
			 */
			for (Pair<SamplerBoolean, Pointer<Byte>> pair : bytes) {
				NativeList<Byte> results = pair.second.asList();
				for (int j = 0; j < results.size(); ++j) {
					Byte _byte = results.get(j);
					/**
					 * Property was not verified!
					 */
					if (_byte > 1) {
						throw new PrismException("Property was not verified of one of the samples!");
					}
					/**
					 * Deadlock at 
					 */
					else if (_byte < 0) {
						throw new PrismException("Deadlock occured on one of the samples!");
					}
					/**
					 * Normal result.
					 */
					else {
						pair.first.addSample(_byte == 1);
					}
				}
			}
		}

		protected void readPathLength(CLQueue queue, int start, int samples)
		{
			NativeList<Integer> lengths = pathLengths.read(queue, start, samples).asList();
			for (Integer i : lengths) {
				minPathLength = Math.min(minPathLength, i);
				maxPathLength = Math.max(maxPathLength, i);
				avgPathLength += i;
			}
		}

		public abstract long getKernelTime();
	}

	private class KnownIterationsState extends ContextState
	{
		private int numberOfSamples;

		public KnownIterationsState(List<Sampler> properties, int gwSize, int lwSize, int numberOfSamples)
		{
			super(properties, gwSize, lwSize);
			this.numberOfSamples = numberOfSamples;
			createBuffers();
		}

		@Override
		protected void createBuffers()
		{
			pathLengths = context.createIntBuffer(CLMem.Usage.Output, numberOfSamples);
			for (int i = 0; i < properties.size(); ++i) {
				resultBuffers.add(context.createByteBuffer(CLMem.Usage.Output, numberOfSamples));
			}
		}

		@Override
		public boolean processResults(CLQueue queue) throws PrismException
		{
			if (finished) {
				return true;
			}
			queue.finish();
			readResults(queue, 0, numberOfSamples);
			readPathLength(queue, 0, numberOfSamples);
			samplesProcessed += numberOfSamples;
			finished = true;
			return true;
		}

		@Override
		public void enqueueKernel(CLQueue queue)
		{
			if (finished) {
				return;
			}
			int samplesProcessed = 0;
			while (samplesProcessed < numberOfSamples) {
				//determine how many samples allocate
				int currentGWSize = (int) Math.min(globalWorkSize, numberOfSamples - samplesProcessed);
				int samplesToProcess = currentGWSize;
				if (currentGWSize != globalWorkSize) {
					currentGWSize = roundUp(localWorkSize, currentGWSize);
				}

				config.prngType.setKernelArg(programKernel, 0, samplesProcessed, globalWorkSize, localWorkSize);
				int argOffset = config.prngType.kernelArgsNumber();
				programKernel.setArg(argOffset, samplesToProcess);
				programKernel.setArg(1 + argOffset, samplesProcessed);
				programKernel.setArg(2 + argOffset, pathLengths);
				for (int i = 0; i < properties.size(); ++i) {
					programKernel.setObjectArg(3 + argOffset + i, resultBuffers.get(i));
				}

				CLEvent kernelCompletion = programKernel.enqueueNDRange(queue, new int[] { currentGWSize }, new int[] { localWorkSize });
				events.add(kernelCompletion);
				samplesProcessed += currentGWSize;
			}
			queue.flush();
		}

		@Override
		public long getKernelTime()
		{
			long kernelTime = 0;
			for (CLEvent event : events) {
				kernelTime += (event.getProfilingCommandEnd() - event.getProfilingCommandStart()) / 1000000;
			}
			return kernelTime;
		}
	}

	private class NotKnownIterationsState extends ContextState
	{
		public int resultCheckPeriod = 1;
		public int pathCheckPeriod = 1;

		public NotKnownIterationsState(List<Sampler> properties, int gwSize, int lwSize, int resultCheckPeriod, int pathCheckPeriod)
		{
			super(properties, gwSize, lwSize);
			this.resultCheckPeriod = resultCheckPeriod;
			this.pathCheckPeriod = pathCheckPeriod;
			createBuffers();
		}

		@Override
		protected void createBuffers()
		{
			pathLengths = context.createIntBuffer(CLMem.Usage.Output, numberOfSamplesPerIteration * pathCheckPeriod);
			for (int i = 0; i < properties.size(); ++i) {
				resultBuffers.add(context.createByteBuffer(CLMem.Usage.Output, numberOfSamplesPerIteration * resultCheckPeriod));
			}
		}

		@Override
		public boolean processResults(CLQueue queue) throws PrismException
		{
			if (finished) {
				return true;
			}
			return false;
		}

		@Override
		public void enqueueKernel(CLQueue queue)
		{
			if (finished) {
				return;
			}

		}

		@Override
		public long getKernelTime()
		{
			return 0;
		}
	}

	CLDeviceWrapper currentDevice = null;
	CLContext context = null;
	Kernel kernel = null;
	RuntimeConfig config = null;
	List<Sampler> properties = null;
	int numberOfSamples = 0;
	private PrismLog mainLog = null;
	private List<CLBuffer<Byte>> resultBuffers = new ArrayList<>();
	private CLBuffer<Integer> pathLengths = null;
	private ContextState state = null;
	private int localWorkSize = 0;
	private CLKernel programKernel = null;
	float avgPathLength = 0.0f;
	int minPathLength = Integer.MAX_VALUE;
	int maxPathLength = 0;
	long time = 0;
	int samplesProcessed = 0;

	public RuntimeContext(CLDeviceWrapper device, PrismLog mainLog)
	{
		this.mainLog = mainLog;
		currentDevice = device;
		context = currentDevice.createDeviceContext();
	}

	public void createKernel(AbstractAutomaton automaton, List<Sampler> properties, RuntimeConfig config) throws PrismException
	{
		try {
			this.config = config;
			this.config.configDevice(currentDevice);
			this.properties = properties;
			kernel = new Kernel(this.config, automaton, properties);
			CLProgram program = context.createProgram(kernel.getSource());
			//TODO: for others rng
			program.addInclude("src/gpu/");
			program.addInclude("gpu/Random123/features");
			program.addInclude("gpu/Random123");
			program.addInclude("gpu/");
			program.build();
			programKernel = program.createKernel("main");
			localWorkSize = programKernel.getWorkGroupSize().get(currentDevice.getDevice()).intValue();

			//check if we have some properties that are unknown
			boolean numberOfSamplesNotKnown = false;
			int numberOfSamples = 0;
			for (Sampler property : properties) {
				SimulationMethod method = property.getSimulationMethod();
				if (method instanceof ACIiterations || method instanceof CIiterations || method instanceof SPRTMethod) {
					numberOfSamplesNotKnown = true;
					break;
				} else if (method instanceof APMCMethod) {
					numberOfSamples = Math.max(numberOfSamples, ((APMCMethod) method).getNumberOfSamples());
				} else if (method instanceof CIMethod) {
					numberOfSamples = Math.max(numberOfSamples, ((CIMethod) method).getNumberOfSamples());
				}
			}
			int globalWorkSize;
			//compute the minimal, but reasonable number of samples
			//the configDevice method gives a guarantee, that we're dealing only with GPU/CPU
			if (numberOfSamplesNotKnown) {
				if (config.deviceType == Type.CPU) {
					globalWorkSize = config.inDirectMethodGWSizeCPU;
				} else {
					globalWorkSize = config.inDirectMethodGWSizeGPU;
				}
				state = new NotKnownIterationsState(properties, globalWorkSize, localWorkSize, config.inDirectResultCheckPeriod,
						config.inDirectResultCheckPeriod);
			} else {
				if (config.deviceType == Type.CPU) {
					globalWorkSize = config.directMethodGWSizeCPU;
				} else {
					globalWorkSize = config.directMethodGWSizeGPU;
				}
				state = new KnownIterationsState(properties, globalWorkSize, localWorkSize, numberOfSamples);
			}
		} catch (CLBuildException exc) {
			mainLog.println("Program build error: " + exc.getMessage());
			throw new PrismException("Program build error!");
		} catch (KernelException exc) {
			mainLog.println("Kernel generation error: " + exc.getMessage());
			throw new PrismException("Kernel generation error!");
		}
	}

	public void runSimulation() throws PrismException
	{
		CLQueue queue = null;
		try {
			queue = context.createDefaultProfilingQueue();
			do {
				state.enqueueKernel(queue);
				while (!state.processResults(queue)) {
					Thread.sleep(1);
				}
			} while (!state.hasFinished());
		} catch (PrismException exc) {
			throw exc;
		} catch (Exception exc) {
			//TODO - kernel abort
			mainLog.println(exc.toString());
			mainLog.println(exc.getStackTrace());
		} catch (Error exc) {
			mainLog.println(exc.toString());
			mainLog.println(exc.getStackTrace());
		} finally {
			queue.finish();
			if (queue != null) {
				queue.release();
			}
		}
		avgPathLength /= state.samplesProcessed;
		time = state.getKernelTime();
		samplesProcessed = state.samplesProcessed;
	}

	public float getAvgPathLength()
	{
		return avgPathLength;
	}

	public int getMinPathLength()
	{
		return minPathLength;
	}

	public int getMaxPathLength()
	{
		return maxPathLength;
	}

	public long getTime()
	{
		return time;
	}

	public int getSamplesProcessed()
	{
		return samplesProcessed;
	}

	public void release()
	{
		for (CLBuffer<Byte> buffer : resultBuffers) {
			buffer.release();
		}
		pathLengths.release();
		context.release();
		state = null;
	}

	private int roundUp(int groupSize, int globalSize)
	{
		Preconditions.checkCondition(groupSize != 0, "Division by zero!");
		int r = globalSize % groupSize;
		if (r == 0) {
			return globalSize;
		} else {
			return globalSize + groupSize - r;
		}
	}
}