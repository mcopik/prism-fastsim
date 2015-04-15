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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import org.bridj.NativeList;
import org.bridj.Pointer;

import prism.Pair;
import prism.Preconditions;
import prism.PrismException;
import prism.PrismLog;
import simulator.method.ACIiterations;
import simulator.method.APMCMethod;
import simulator.method.CIMethod;
import simulator.method.CIiterations;
import simulator.method.SPRTMethod;
import simulator.method.SimulationMethod;
import simulator.opencl.automaton.AbstractAutomaton;
import simulator.opencl.kernel.Kernel;
import simulator.opencl.kernel.KernelException;
import simulator.sampler.Sampler;
import simulator.sampler.SamplerBoolean;

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
	/**
	 * Class contains strategy for generation of samples.
	 * Currently, there exists two approaches:
	 * a) when number of samples in known a priori
	 * b) when estimator and quantile determine if sampling should be stopped
	 */
	private abstract class ContextState
	{
		public List<Sampler> properties;
		public Boolean[] propertiesStatus;
		protected int globalWorkSize;
		protected int localWorkSize;
		protected boolean finished = false;
		protected CLQueue queue = null;
		/**
		 * Number of samples that has been processed or allocated on the device.
		 */
		protected int samplesProcessed = 0;

		protected ContextState(List<Sampler> properties, int gwSize, int lwSize)
		{
			this.properties = properties;
			propertiesStatus = new Boolean[properties.size()];
			Arrays.fill(propertiesStatus, false);
			this.globalWorkSize = roundUp(lwSize, gwSize);
			this.localWorkSize = lwSize;
		}

		protected abstract void createBuffers();

		public boolean hasFinished()
		{
			return finished;
		}

		public void setQueue(CLQueue queue)
		{
			this.queue = queue;
		}

		protected abstract void updateSampling();

		protected abstract boolean processResults() throws PrismException;

		protected abstract void reset();

		protected CLEvent enqueueKernel(int samplesToProcess, int resultsOffset, int pathsOffset)
		{
			int currentGWSize = samplesToProcess;
			if (currentGWSize != globalWorkSize) {
				currentGWSize = roundUp(localWorkSize, currentGWSize);
			}
			//configure PRNG 
			config.prngType.setKernelArg(programKernel, 0, samplesProcessed, globalWorkSize, localWorkSize);
			int argOffset = config.prngType.kernelArgsNumber();
			//number of samples to process 
			programKernel.setArg(argOffset, samplesToProcess);
			//sample offset for rng
			programKernel.setArg(1 + argOffset, samplesProcessed);
			//offset in result buffer
			programKernel.setArg(2 + argOffset, resultsOffset);
			//offset in path lengths buffer
			programKernel.setArg(3 + argOffset, pathsOffset);
			//OpenCL buffer where path lengths are saved
			programKernel.setArg(4 + argOffset, pathLengths);
			//OpenCL buffer with sampling results, one for each property
			for (int i = 0; i < properties.size(); ++i) {
				programKernel.setObjectArg(5 + argOffset + i, resultBuffers.get(i));
			}
			//samplesProcessed += samplesToProcess;
			return programKernel.enqueueNDRange(queue, new int[] { currentGWSize }, new int[] { localWorkSize });
		}

		protected void readResults(int start, int samples) throws PrismException
		{
			readResults(start, samples, -1);
		}

		protected void readResults(int start, int samples, int periodityOfSamplerCheck) throws PrismException
		{
			List<Pair<SamplerBoolean, Pointer<Byte>>> bytes = new ArrayList<>();
			List<CLEvent> readEvents = new ArrayList<>();
			/**
			 * For each active sampler, add a pair of sampler and pointer to read data.
			 */
			for (int i = 0; i < properties.size(); ++i) {
				SamplerBoolean sampler = (SamplerBoolean) properties.get(i);
				if (!propertiesStatus[i]) {
					bytes.add(new Pair<SamplerBoolean, Pointer<Byte>>(sampler, Pointer.allocateBytes(samples)));
					readEvents.add(resultBuffers.get(i).read(queue, start, samples, bytes.get(i).second, false));
				}
			}
			/**
			 * Wait for reading.
			 */
			CLEvent.waitFor(readEvents.toArray(new CLEvent[readEvents.size()]));
			/**
			 * Add read data to sampler.
			 */

			for (int i = 0; i < bytes.size(); ++i) {
				Pair<SamplerBoolean, Pointer<Byte>> pair = bytes.get(i);
				NativeList<Byte> results = pair.second.asList();
				for (int j = 0; j < results.size(); ++j) {
					Byte _byte = results.get(j);
					/**
					 * Property was not verified!
					 */
					if (_byte > 1) {
						throw new PrismException("Property was not verified on one of the samples!");
					}
					/**
					 * Deadlock in sample
					 */
					else if (_byte < 0) {
						throw new PrismException("Deadlock occured on one of the samples!");
					}
					/**
					 * Normal result.
					 */
					else {
						pair.first.addSample(_byte == 1);
						if (periodityOfSamplerCheck != -1 && j % periodityOfSamplerCheck == 0) {
							Sampler sampler = pair.first;
							if (sampler.getSimulationMethod().shouldStopNow(samplesProcessed + j, sampler)) {
								/**
								 * We're already finished with sampler, jump to next property.
								 */
								propertiesStatus[i] = true;
								break;
							}
						}
					}
				}
			}
		}

		protected void readPathLength(int start, int samples)
		{
			NativeList<Integer> lengths = pathLengths.read(queue, start, samples).asList();
			long sum = 0;
			for (Integer i : lengths) {
				minPathLength = Math.min(minPathLength, i);
				maxPathLength = Math.max(maxPathLength, i);
				sum += i;
			}
			avgPathLength = sum / lengths.size();
		}

		public abstract long getKernelTime();
	}

	private class KnownIterationsState extends ContextState
	{
		private int numberOfSamples;
		protected List<CLEvent> events = new ArrayList<>();

		public KnownIterationsState(List<Sampler> properties, int lwSize, int numberOfSamples)
		{
			super(properties, currentDevice.isGPU() ? config.directMethodGWSizeGPU : config.directMethodGWSizeCPU, lwSize);
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
		public boolean processResults() throws PrismException
		{
			Preconditions.checkNotNull(queue);
			if (finished) {
				return true;
			}
			queue.finish();
			readResults(0, numberOfSamples);
			readPathLength(0, numberOfSamples);
			samplesProcessed += numberOfSamples;
			finished = true;
			return true;
		}

		@Override
		protected void updateSampling()
		{
			Preconditions.checkNotNull(queue);
			int samplesProcessed = 0;
			while (samplesProcessed < numberOfSamples) {
				//determine how many samples allocate
				int currentGWSize = (int) Math.min(globalWorkSize, numberOfSamples - samplesProcessed);

				CLEvent kernelCompletion = enqueueKernel(currentGWSize, samplesProcessed, samplesProcessed);
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

		@Override
		protected void reset()
		{
			events.clear();
		}
	}

	private class NotKnownIterationsState extends ContextState
	{
		public static final int PERIODITY_OF_SAMPLER_STOP_CHECK = 50;
		public int resultCheckPeriod;
		public int pathCheckPeriod;
		private int samplesFinished = 0;
		private List<Integer> resultsCheckIndices = new LinkedList<>();
		private List<Integer> pathCheckIndices = new LinkedList<>();
		private SortedSet<Integer> freeResultIndices = new TreeSet<>();
		private SortedSet<Integer> freePathIndices = new TreeSet<>();
		/**
		 * Key: Integers in pair denote indices of buffers: result and path.
		 * Value: CLEvent connected to this NDRange.
		 */
		private Map<Pair<Integer, Integer>, CLEvent> events = new HashMap<>();

		public NotKnownIterationsState(List<Sampler> properties, int lwSize)
		{
			super(properties, currentDevice.isGPU() ? config.inDirectMethodGWSizeGPU : config.inDirectMethodGWSizeCPU, lwSize);
			//todo: remove, test!
			//globalWorkSize = 40960;
			resultCheckPeriod = config.inDirectResultCheckPeriod;
			pathCheckPeriod = config.inDirectPathCheckPeriod;
			createBuffers();
			for (int i = 0; i < resultCheckPeriod; ++i) {
				freeResultIndices.add(i);
			}
			for (int i = 0; i < pathCheckPeriod; ++i) {
				freePathIndices.add(i);
			}
		}

		@Override
		protected void createBuffers()
		{
			mainLog.println("Path Lengths " + globalWorkSize * pathCheckPeriod);
			pathLengths = context.createIntBuffer(CLMem.Usage.Output, globalWorkSize * pathCheckPeriod);
			for (int i = 0; i < properties.size(); ++i) {
				resultBuffers.add(context.createByteBuffer(CLMem.Usage.Output, globalWorkSize * resultCheckPeriod));
			}
		}

		@Override
		public boolean processResults() throws PrismException
		{
			Preconditions.checkNotNull(queue);
			if (finished) {
				return true;
			}

			//List<Integer> finishedEvents = new ArrayList<>();
			boolean kernelFinished = false;
			for (Map.Entry<Pair<Integer, Integer>, CLEvent> entry : events.entrySet()) {
				CLEvent event = entry.getValue();
				if (event.getCommandExecutionStatus() == CLEvent.CommandExecutionStatus.Complete) {
					//task completed, we can read the results and times
					//					finishedEvents.add();
					kernelTime += (event.getProfilingCommandEnd() - event.getProfilingCommandStart()) / 1000000;
					resultsCheckIndices.add(entry.getKey().first);
					pathCheckIndices.add(entry.getKey().second);
					//erase the event from map
					events.remove(entry.getKey());
					kernelFinished = true;
				}
			}
			//read results and add to samplers; check for deadlock/unverified property
			//number of finished kernels greater or equal than period?
			while (resultsCheckIndices.size() >= resultCheckPeriod) {
				readPartialResults(resultCheckPeriod);
			}
			//read path lengths
			while (pathCheckIndices.size() >= pathCheckPeriod) {
				readPartialPaths(pathCheckPeriod);
			}
			finished = true;
			//check if all sampler values are knowng
			for (Boolean value : propertiesStatus) {
				finished &= value;
			}
			//if all properties are computed, then we can simply wait and read last results
			if (finished) {
				Collection<CLEvent> lastEvents = events.values();
				//wait for kernels
				CLEvent.waitFor(lastEvents.toArray(new CLEvent[lastEvents.size()]));

				for (Map.Entry<Pair<Integer, Integer>, CLEvent> entry : events.entrySet()) {
					CLEvent event = entry.getValue();
					kernelTime += (event.getProfilingCommandEnd() - event.getProfilingCommandStart()) / 1000000;
					resultsCheckIndices.add(entry.getKey().first);
					pathCheckIndices.add(entry.getKey().second);
					events.remove(entry.getKey());
				}
				readPartialResults(resultsCheckIndices.size());
				readPartialPaths(pathCheckIndices.size());
			}
			return kernelFinished;
		}

		protected void readPartialResults(int resultBuffers) throws PrismException
		{
			//result buffers
			for (int i = 0; i < resultBuffers; ++i) {
				mainLog.println(String.format("Read results start %d count %d", resultsCheckIndices.get(i) * globalWorkSize, globalWorkSize));
				readResults(resultsCheckIndices.get(i) * globalWorkSize, globalWorkSize, PERIODITY_OF_SAMPLER_STOP_CHECK);
			}
			samplesFinished += resultBuffers * globalWorkSize;
			List<Integer> freedIndices = resultsCheckIndices.subList(0, resultBuffers);
			freeResultIndices.addAll(freedIndices);
			freedIndices.clear();
		}

		protected void readPartialPaths(int pathBuffers) throws PrismException
		{
			//path buffers
			for (int i = 0; i < pathBuffers; ++i) {
				mainLog.println(String.format("Read paths start %d count %d", pathCheckIndices.get(i) * globalWorkSize, globalWorkSize));
				readPathLength(pathCheckIndices.get(i) * globalWorkSize, globalWorkSize);
			}
			List<Integer> freedIndices = pathCheckIndices.subList(0, pathBuffers);
			freePathIndices.addAll(freedIndices);
			freedIndices.clear();
		}

		@Override
		public void updateSampling()
		{
			Preconditions.checkNotNull(queue);
			if (finished) {
				return;
			}

			SortedSet<Integer> set = resultCheckPeriod <= pathCheckPeriod ? freeResultIndices : freePathIndices;
			//for each free indice, enqueue an kernel
			//the indices are allocated and freed in pairs, so there will be always available indices
			//for the "bigger" period
			for (Integer indice : set) {
				Integer secondIndice = null;
				if (resultCheckPeriod <= pathCheckPeriod) {
					secondIndice = freePathIndices.first();
					mainLog.println(String.format("Enqeueue result %d path %d samples %d", indice, secondIndice, globalWorkSize));
					events.put(new Pair<Integer, Integer>(indice, secondIndice),
					//create and send the kernel
							enqueueKernel(globalWorkSize, indice * globalWorkSize, secondIndice * globalWorkSize));
					//set indices as not available
					set.remove(indice);
					freePathIndices.remove(secondIndice);
				} else {
					//identical as above, only for different combination of sets
					secondIndice = freeResultIndices.first();
					mainLog.println(String.format("Enqeueue result %d path %d samples %d", secondIndice, indice, globalWorkSize));
					events.put(new Pair<Integer, Integer>(secondIndice, indice),
							enqueueKernel(globalWorkSize, secondIndice * globalWorkSize, indice * globalWorkSize));
					set.remove(indice);
					freeResultIndices.remove(secondIndice);
				}
			}

			queue.flush();
		}

		@Override
		public void reset()
		{
			events.clear();
			resultsCheckIndices.clear();
			pathCheckIndices.clear();
			for (int i = 0; i < resultCheckPeriod; ++i) {
				freeResultIndices.add(i);
			}
			for (int i = 0; i < pathCheckPeriod; ++i) {
				freeResultIndices.add(i);
			}
		}

		@Override
		public long getKernelTime()
		{
			return kernelTime;
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
	//private int localWorkSize = 0;
	private CLKernel programKernel = null;
	private CLProgram program = null;
	double avgPathLength = 0.0f;
	int minPathLength = Integer.MAX_VALUE;
	int maxPathLength = 0;
	/**
	 * Updated by Strategy object.
	 */
	long kernelTime = 0;
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
			mainLog.println(kernel.getSource());

			mainLog.flush();
			//System.out.println(kernel.getSource());
			String str = kernel.getSource();
			program = context.createProgram(str);

			//add include directories for PRNG
			//has to work when applications is executed as Java class or as a jar
			String location = this.getClass().getPackage().getName().replace(".", "/") + "/includes";
			mainLog.flush();
			//when running a Java class
			program.addInclude("src/" + location);
			//when running a *.jar
			program.addInclude("src/" + location + "/Random123");
			// TODO: check why this is necessary
			program.addInclude("src/" + location + "/Random123/features");

			//program.addBuildOption("-cl-nv-verbose");
			program.addBuildOption("-w");
			program.build();

			programKernel = program.createKernel("main");
			int localWorkSize = programKernel.getWorkGroupSize().get(currentDevice.getDevice()).intValue();

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
			//compute the minimal, but reasonable number of samples
			if (numberOfSamplesNotKnown) {
				state = new NotKnownIterationsState(properties, localWorkSize);
			}
			//compute the exact number of samples
			else {
				state = new KnownIterationsState(properties, localWorkSize, numberOfSamples);
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
			state.setQueue(queue);
			do {
				state.updateSampling();
				while (!state.processResults()) {
					Thread.sleep(1);
				}
				mainLog.flush();
			} while (!state.hasFinished());
		} catch (PrismException exc) {
			throw exc;
		} catch (Exception exc) {
			mainLog.println(exc);
		} catch (Error exc) {
			mainLog.println(exc);
		} finally {
			mainLog.flush();
			queue.finish();
			if (queue != null) {
				queue.release();
			}
		}
		kernelTime = state.getKernelTime();
		samplesProcessed = state.samplesProcessed;
	}

	public double getAvgPathLength()
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
		return kernelTime;
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

	/**
	 * Acquire building info (warnings etc) from the compiler.
	 * Requires access to private method getProgramBuildInfo in class CLProgram - use reflection for that.
	 * Works with JavaCL 1.0 RC4
	 * @param deviceNumber
	 * @return compiler build info
	 * @throws KernelException
	 */
	public String getBuildInfo(int deviceNumber) throws KernelException
	{
		//TODO: Extend this for future usage of multiple devices
		Preconditions.checkIndex(deviceNumber, 1);
		Preconditions.checkCondition(program != null);
		try {
			/**
			 * private String CLProgram.getProgramBuildInfo(long programEntity, long deviceEntity)
			 */
			Method methodCLProgramBuildInfo = program.getClass().getDeclaredMethod("getProgramBuildInfo", new Class[] { Long.TYPE, Long.TYPE });
			/**
			 * protected long CLProgram.getEntity()
			 */
			Method methodCLProgramEntity = program.getClass().getDeclaredMethod("getEntity");
			/**
			 * protected long CLDevice.getEntity()
			 */
			Method methodCLDeviceEntity = program.getDevices()[deviceNumber].getClass().getSuperclass().getDeclaredMethod("getEntity");

			// private & protected
			methodCLProgramBuildInfo.setAccessible(true);
			methodCLProgramEntity.setAccessible(true);
			methodCLDeviceEntity.setAccessible(true);

			Long programEntity = (Long) methodCLProgramEntity.invoke(program);
			Long deviceEntity = (Long) methodCLDeviceEntity.invoke(program.getDevices()[deviceNumber]);
			return (String) methodCLProgramBuildInfo.invoke(program, programEntity, deviceEntity);

		} catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {

			mainLog.print(e);
			throw new KernelException("Unknown error when trying to obtain build info for CLProgram!");
		}
	}
}