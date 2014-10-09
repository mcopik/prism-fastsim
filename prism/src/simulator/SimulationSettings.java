/**
 * 
 */
package simulator;

import simulator.method.SimulationMethod;

/**
 * @author mcopik
 *
 */
public class SimulationSettings
{
	private SimulationMethod simMethod;

	private SMCRuntimeInterface simulator = null;
	private boolean simulatorTest = false;

	public SimulationSettings(SimulationMethod simMethod)
	{
		this.simMethod = simMethod;
	}

	public SimulationSettings(SimulationMethod simMethod, SMCRuntimeInterface simulator)
	{
		this.simMethod = simMethod;
		this.simulator = simulator;
	}

	public void runSimulatorTest()
	{
		simulatorTest = true;
	}

	public boolean isSimulatorTest()
	{
		return simulatorTest;
	}

	public void setMethod(SimulationMethod simMethod)
	{
		this.simMethod = simMethod;
	}

	public SimulationMethod getMethod()
	{
		return simMethod;
	}

	public boolean selectedStandardEngine()
	{
		return simulator == null;
	}

	public SMCRuntimeInterface getSimulatorFramework()
	{
		return simulator;
	}
}
