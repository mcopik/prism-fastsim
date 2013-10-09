/**
 * 
 */
package simulator.gpu;

import java.util.List;

import parser.State;
import parser.ast.Expression;
import parser.ast.ModulesFile;
import parser.ast.PropertiesFile;
import prism.ModelType;
import prism.PrismException;
import prism.PrismLog;
import prism.ResultsCollection;
import prism.UndefinedConstants;
import simulator.ModelCheckInterface;
import simulator.gpu.automaton.AbstractAutomaton;
import simulator.gpu.automaton.DTMC;
import simulator.method.SimulationMethod;

/**
 * @author mcopik
 *
 */
public class GPUSimulatorEngine implements ModelCheckInterface
{
	protected PrismLog mainLog;
	private ModulesFile modulesFile;
	private AbstractAutomaton automaton;
	public GPUSimulatorEngine(PrismLog log)
	{
		mainLog = log;
	}
	private void loadModel(ModulesFile modulesFile) throws PrismException
	{
		checkModelForSimulation(modulesFile);
		if(modulesFile.getModelType() == ModelType.DTMC)
		{
			automaton = new DTMC(modulesFile);
		}
	}
	@Override
	public void checkModelForSimulation(ModulesFile modulesFile) throws PrismException
	{
		if(modulesFile.getModelType() != ModelType.DTMC)
		{
			throw new PrismException("Currently only DTMC is supported!");
		}
	}
	@Override
	public boolean isPropertyOKForSimulation(Expression expr)
	{
		//TODO: implement!
		return false;
	}
	@Override
	public void checkPropertyForSimulation(Expression expr) throws PrismException
	{
		throw new PrismException("Not implemented yet!");
	}
	@Override
	public Object modelCheckSingleProperty(ModulesFile modulesFile, PropertiesFile propertiesFile, 
			Expression expr, State initialState, int maxPathLength,
			SimulationMethod simMethod) throws PrismException
	{
		throw new PrismException("Not implemented yet!");
	}
	@Override
	public Object[] modelCheckMultipleProperties(ModulesFile modulesFile, PropertiesFile propertiesFile, 
			List<Expression> exprs, State initialState, int maxPathLength, 
			SimulationMethod simMethod) throws PrismException
	{
		throw new PrismException("Not implemented yet!");
	}
	@Override
	public void modelCheckExperiment(ModulesFile modulesFile, PropertiesFile propertiesFile, 
			UndefinedConstants undefinedConstants, ResultsCollection resultsCollection, 
			Expression expr, State initialState, int maxPathLength, SimulationMethod simMethod) 
			throws PrismException, InterruptedException
	{
		throw new PrismException("Not implemented yet!");
	}
}