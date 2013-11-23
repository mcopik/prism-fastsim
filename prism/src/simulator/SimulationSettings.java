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
	private SimulationPlatform simPlatform;
	public enum SimulationPlatform {
		CPU,
		OPENCL
	}
	public void setMethod(SimulationMethod simMethod)
	{
		this.simMethod = simMethod;
	}
	public SimulationMethod getMethod()
	{
		return simMethod;
	}
	public void setPlatform(SimulationPlatform simPlatform)
	{
		this.simPlatform = simPlatform;
	}
	public SimulationPlatform getPlatform()
	{
		return simPlatform;
	}
}
