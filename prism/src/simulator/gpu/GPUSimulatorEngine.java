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
import simulator.gpu.automaton.CTMC;
import simulator.gpu.automaton.DTMC;
import simulator.method.SimulationMethod;


public class GPUSimulatorEngine implements ModelCheckInterface
{
	/**
	 * Prism main log.
	 */
	protected PrismLog mainLog;
	/**
	 * Represents Prism's object of current automaton.
	 */
	@SuppressWarnings("unused")
	private ModulesFile modulesFile;
	/**
	 * Current automaton structure for GPU.
	 */
	private AbstractAutomaton automaton;
	/**
	 * Main constructor
	 * @param log Prism main log
	 */
	public GPUSimulatorEngine(PrismLog log)
	{
		mainLog = log;
	}
	/**
	 * Create automaton object using PRISM object
	 * @param modulesFile
	 * @throws PrismException
	 */
	private void loadModel(ModulesFile modulesFile) throws PrismException
	{
		if(modulesFile.getModelType() == ModelType.DTMC)
		{
			automaton = new DTMC(modulesFile);
		}
		else if(modulesFile.getModelType() == ModelType.CTMC)
		{
			automaton = new CTMC(modulesFile);
		}
	}
	@Override
	public void checkModelForSimulation(ModulesFile modulesFile) throws PrismException
	{
		if(modulesFile.getModelType() != ModelType.DTMC ||
				modulesFile.getModelType() != ModelType.CTMC)
		{
			throw new PrismException("Currently only DTMC/CTMC is supported!");
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
		loadModel(modulesFile);
		System.out.println(automaton);
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