//==============================================================================
//	
//	Copyright (c) 2002-
//	Authors:
//	* Marcin Copik <mcopik@gmail.com> (RWTH Aachen, formerly Silesian University of Technology)
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

import java.util.ArrayList;
import java.util.List;

import parser.State;
import parser.Values;
import parser.ast.Expression;
import parser.ast.ExpressionFilter;
import parser.ast.ExpressionProb;
import parser.ast.ExpressionReward;
import parser.ast.ExpressionTemporal;
import parser.ast.LabelList;
import parser.ast.ModulesFile;
import parser.ast.PropertiesFile;
import prism.ModelType;
import prism.Preconditions;
import prism.PrismComponent;
import prism.PrismException;
import prism.Result;
import prism.ResultsCollection;
import prism.UndefinedConstants;
import simulator.method.SimulationMethod;
import simulator.sampler.Sampler;

/**
 * Implements the statistical verification of properties,
 * using the doSampling() method provided by SMC runtime. 
 */
public class StochasticModelChecker extends PrismComponent
{
	/**
	 * Simulator runtime which will be used.
	 * Currently two are implemented: SimulatorEngine and OpenCLSimulatorEngine
	 */
	private SMCRuntimeInterface simulatorRuntime = null;

	/**
	 * Properties converted to simulator's samplers.
	 */
	private List<Sampler> propertySamplers;

	/**
	 * Samplers for properties.
	 * null if the property cannot be handled
	 */
	private Sampler[] samplers = null;

	/**
	 * Results of simulation.
	 * Exception if the property cannot be handled.
	 */
	private Result[] results = null;

	/**
	 * Default constructor.
	 * @param simulator
	 * @param parent
	 */
	public StochasticModelChecker(SMCRuntimeInterface simulator, PrismComponent parent)
	{
		super(parent);
		Preconditions.checkNotNull(simulator);
		simulatorRuntime = simulator;
	}

	/**
	 * Change used simulator runtime.
	 * @param simulator
	 */
	public void selectSimulator(SMCRuntimeInterface simulator)
	{
		Preconditions.checkNotNull(simulator);
		this.simulatorRuntime = simulator;
	}

	/**
	 * Check whether a model is suitable for for approximate model checking.
	 * If not, an explanatory error message is thrown as an exception.
	 */
	public void checkModelForAMC(ModulesFile modulesFile) throws PrismException
	{
		// No support for PTAs yet
		if (modulesFile.getModelType() == ModelType.PTA) {
			throw new PrismException("Sorry - the simulator does not currently support PTAs");
		}
		// No support for system...endsystem yet
		if (modulesFile.getSystemDefn() != null) {
			throw new PrismException("Sorry - the simulator does not currently handle the system...endsystem construct");
		}
		// additional checking if the implementation has additional restrictions
		simulatorRuntime.checkModelForAMC(modulesFile);
	}

	/**
	 * Check whether a property is suitable for approximate model checking using the simulator.
	 */
	public boolean isPropertyOKForAMC(Expression expr)
	{
		return isPropertyOKForAMCString(expr) == null;
	}

	/**
	 * Check whether a property is suitable for approximate model checking using the simulator.
	 * If not, an explanatory error message is thrown as an exception.
	 */
	public void checkPropertyForAMC(Expression expr) throws PrismException
	{
		String errMsg = isPropertyOKForAMCString(expr);
		if (errMsg != null)
			throw new PrismException(errMsg);
	}

	/**
	 * Check whether a property is suitable for approximate model checking using the simulator.
	 * If yes, return null; if not, return an explanatory error message.
	 */
	private String isPropertyOKForAMCString(Expression expr)
	{
		// Simulator can only be applied to P or R properties (without filters)
		if (!(expr instanceof ExpressionProb || expr instanceof ExpressionReward)) {
			if (expr instanceof ExpressionFilter) {
				if (((ExpressionFilter) expr).getOperand() instanceof ExpressionProb || ((ExpressionFilter) expr).getOperand() instanceof ExpressionReward)
					return "Simulator cannot handle P or R properties with filters";
			}
			return "Simulator can only handle P or R properties";
		}
		// Check that there are no nested probabilistic operators
		try {
			if (expr.computeProbNesting() > 1) {
				return "Simulator cannot handle nested P, R or S operators";
			}
		} catch (PrismException e) {
			return "Simulator cannot handle this property: " + e.getMessage();
		}
		// Simulator cannot handle cumulative reward properties without a time bound
		if (expr instanceof ExpressionReward) {
			Expression exprTemp = ((ExpressionReward) expr).getExpression();
			if (exprTemp instanceof ExpressionTemporal) {
				if (((ExpressionTemporal) exprTemp).getOperator() == ExpressionTemporal.R_C) {
					if (((ExpressionTemporal) exprTemp).getUpperBound() == null) {
						return "Simulator cannot handle cumulative reward properties without time bounds";
					}
				}
			}
		}
		// No errors - check additional restrictions given by the implementation
		try {
			simulatorRuntime.checkPropertyForAMC(expr);
		} catch (PrismException e) {
			return e.getMessage();
		}
		return null;
	}

	/**
	 * Create samplers for properties using selected simulation method.
	 * @param properties
	 * @param pf
	 * @param simMethod
	 * @throws PrismException
	 */
	private void createSamplers(ModulesFile modulesFile, List<Expression> properties, PropertiesFile pf, SimulationMethod simMethod) throws PrismException
	{
		simMethod.computeMissingParameterBeforeSim();
		samplers = new Sampler[properties.size()];
		results = new Result[properties.size()];

		/**
		 * Code taken from SimulatorEngine.java
		 */
		for (int i = 0; i < properties.size(); ++i) {
			// Take a copy
			Expression propNew = properties.get(i).deepCopy();
			// Combine label lists from model/property file, then expand property refs/labels in property 
			LabelList combinedLabelList = (pf == null) ? modulesFile.getLabelList() : pf.getCombinedLabelList();
			// formulas must be expanded before replacing constants!!!
			propNew = (Expression) propNew.expandFormulas(modulesFile.getFormulaList());
			propNew = (Expression) propNew.expandPropRefsAndLabels(pf, combinedLabelList);
			// Then get rid of any constants and simplify
			propNew = (Expression) propNew.replaceConstants(modulesFile.getConstantValues());
			if (pf != null) {
				propNew = (Expression) propNew.replaceConstants(pf.getConstantValues());
			}
			//TODO: removing parentheses breaks things
			//propNew = (Expression) propNew.simplify();
			Sampler sampler = Sampler.createSampler(propNew, modulesFile);
			SimulationMethod simMethodNew = simMethod.clone();
			sampler.setSimulationMethod(simMethodNew);
			// Pass property details to SimuationMethod
			// (note that we use the copy stored in properties, which has been processed)
			try {
				// check if this property can be processed by this runtime
				checkPropertyForAMC(propNew);
				simMethodNew.setExpression(propNew);
			} catch (PrismException e) {
				results[i] = new Result(e);
				samplers[i] = null;
				continue;
			}
			results[i] = null;
			samplers[i] = sampler;
		}
	}

	/**
	 * Perform approximate model checking of a property on a model, using the simulator.
	 * Sampling starts from the initial state provided or, if null, the default
	 * initial state is used, selecting randomly (each time) if there are more than one.
	 * Returns a Result object, except in case of error, where an Exception is thrown.
	 * Note: All constants in the model/property files must have already been defined.
	 * @param modulesFile Model for simulation, constants defined
	 * @param propertiesFile Properties file containing property to check, constants defined
	 * @param expr The property to check
	 * @param initialState Initial state (if null, is selected randomly)
	 * @param maxPathLength The maximum path length for sampling
	 * @param simMethod Object specifying details of method to use for simulation
	 */
	public Result modelCheckSingleProperty(ModulesFile modulesFile, PropertiesFile propertiesFile, Expression expr, State initialState, long maxPathLength,
			SimulationMethod simMethod) throws PrismException
	{
		ArrayList<Expression> exprs;
		Result res[];

		// Just do this via the 'multiple properties' method
		exprs = new ArrayList<Expression>();
		exprs.add(expr);
		res = modelCheckMultipleProperties(modulesFile, propertiesFile, exprs, initialState, maxPathLength, simMethod);

		if (res[0].getResult() instanceof PrismException)
			throw (PrismException) res[0].getResult();
		else
			return res[0];
	}

	/**
	 * Perform approximate model checking of properties on a model, using the simulator.
	 * Sampling starts from the initial state provided or, if null, the default
	 * initial state is used, selecting randomly (each time) if there are more than one.
	 * Returns an array of results, some of which may be Exception objects if there were errors.
	 * In the case of an error which affects all properties, an exception is thrown.
	 * Note: All constants in the model/property files must have already been defined.
	 * @param modulesFile Model for simulation, constants defined
	 * @param propertiesFile Properties file containing property to check, constants defined
	 * @param exprs The properties to check
	 * @param initialState Initial state (if null, is selected randomly)
	 * @param maxPathLength The maximum path length for sampling
	 * @param simMethod Object specifying details of method to use for simulation
	 */
	public Result[] modelCheckMultipleProperties(ModulesFile modulesFile, PropertiesFile propertiesFile, List<Expression> exprs, State initialState,
			long maxPathLength, SimulationMethod simMethod) throws PrismException
	{
		// Make sure any missing parameters that can be computed before simulation
		// are computed now (sometimes this has been done already, e.g. for GUI display).
		simMethod.computeMissingParameterBeforeSim();

		// Print details to log
		mainLog.println("\nSimulation method: " + simMethod.getName() + " (" + simMethod.getFullName() + ")");
		mainLog.println("Simulation method parameters: " + simMethod.getParametersString());
		mainLog.println("Simulation parameters: max path length=" + maxPathLength);

		// Add the properties to the simulator (after a check that they are valid)
		createSamplers(modulesFile, exprs, propertiesFile, simMethod);
		List<Sampler> validSamplers = new ArrayList<>();
		for (Sampler sampler : samplers) {
			if (sampler != null) {
				validSamplers.add(sampler);
			}
		}

		// As long as there are at least some valid props, do sampling
		if (validSamplers.size() > 0) {
			int samplesProcessed = simulatorRuntime.doSampling(modulesFile, validSamplers, initialState, maxPathLength);
			for (int i = 0; i < samplers.length; i++) {
				Sampler sampler = samplers[i];
				if (sampler != null) {
					SimulationMethod sm = sampler.getSimulationMethod();
					//TODO: temporal fix to avoid wrong width computation
					sm.shouldStopNow(samplesProcessed, sampler);
					sm.computeMissingParameterAfterSim();
					results[i] = new Result();
					results[i].setSimulationMethod(sm);
					try {
						results[i].setResult(sm.getResult(sampler));
					} catch (PrismException e) {
						results[i].setResult(e);
					}
				}
			}

		}

		// Display results to log
		if (results.length == 1) {
			mainLog.print("\nSimulation method parameters: ");
			mainLog.println((samplers[0] == null) ? "no simulation" : samplers[0].getSimulationMethod().getParametersString());
			mainLog.print("\nSimulation result details: ");
			mainLog.println((samplers[0] == null) ? "no simulation" : samplers[0].getSimulationMethodResultExplanation());
			if (!(results[0].getResult() instanceof PrismException))
				mainLog.println("\nResult: " + results[0]);
		} else {
			mainLog.println("\nSimulation method parameters:");
			for (int i = 0; i < results.length; i++) {
				mainLog.print(exprs.get(i) + " : ");
				mainLog.println((samplers[i] == null) ? "no simulation" : samplers[i].getSimulationMethod().getParametersString());
			}
			mainLog.println("\nSimulation result details:");
			for (int i = 0; i < results.length; i++) {
				mainLog.print(exprs.get(i) + " : ");
				mainLog.println((samplers[i] == null) ? "no simulation" : samplers[i].getSimulationMethodResultExplanation());
			}
			mainLog.println("\nResults:");
			for (int i = 0; i < results.length; i++)
				mainLog.println(exprs.get(i) + " : " + results[i]);
		}

		return results;
	}

	/**
	 * Perform an approximate model checking experiment on a model, using the simulator
	 * (specified by values for undefined constants from the property only).
	 * Sampling starts from the initial state provided or, if null, the default
	 * initial state is used, selecting randomly (each time) if there are more than one.
	 * Results are stored in the ResultsCollection object passed in,
	 * some of which may be Exception objects if there were errors.
	 * In the case of an error which affects all properties, an exception is thrown.
	 * Note: All constants in the model file must have already been defined.
	 * @param modulesFile Model for simulation, constants defined
	 * @param propertiesFile Properties file containing property to check, constants defined
	 * @param undefinedConstants Details of constant ranges defining the experiment
	 * @param resultsCollection Where to store the results
	 * @param expr The property to check
	 * @param initialState Initial state (if null, is selected randomly)
	 * @param maxPathLength The maximum path length for sampling
	 * @param simMethod Object specifying details of method to use for simulation
	 * @throws PrismException if something goes wrong with the sampling algorithm
	 * @throws InterruptedException if the thread is interrupted
	 */
	public void modelCheckExperiment(ModulesFile modulesFile, PropertiesFile propertiesFile, UndefinedConstants undefinedConstants,
			ResultsCollection resultsCollection, Expression expr, State initialState, long maxPathLength, SimulationMethod simMethod) throws PrismException,
			InterruptedException
	{
		// Make sure any missing parameters that can be computed before simulation
		// are computed now (sometimes this has been done already, e.g. for GUI display).
		simMethod.computeMissingParameterBeforeSim();

		// Print details to log
		mainLog.println("\nSimulation method: " + simMethod.getName() + " (" + simMethod.getFullName() + ")");
		mainLog.println("Simulation method parameters: " + simMethod.getParametersString());
		mainLog.println("Simulation parameters: max path length=" + maxPathLength);

		// Add the properties to the simulator (after a check that they are valid)
		int n = undefinedConstants.getNumPropertyIterations();
		Values definedPFConstants = new Values();
		Result[] results = new Result[n];
		Values[] pfcs = new Values[n];
		int[] indices = new int[n];
		List<Expression> exprs = new ArrayList<>();
		exprs.add(expr);
		List<Sampler> validSamplers = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			definedPFConstants = undefinedConstants.getPFConstantValues();
			pfcs[i] = definedPFConstants;
			propertiesFile.setSomeUndefinedConstants(definedPFConstants);
			createSamplers(modulesFile, exprs, propertiesFile, simMethod);
			for (Sampler sampler : samplers) {
				if (sampler != null) {
					validSamplers.add(sampler);
				}
			}
			undefinedConstants.iterateProperty();
		}

		// As long as there are at least some valid props, do sampling
		if (validSamplers.size() > 0) {
			simulatorRuntime.doSampling(modulesFile, validSamplers, initialState, maxPathLength);
		}

		// Process the results
		for (int i = 0; i < n; i++) {
			// If there was an earlier error, nothing to do
			if (samplers[i] != null) {
				Sampler sampler = samplers[i];
				//mainLog.print("Simulation results: mean: " + sampler.getMeanValue());
				//mainLog.println(", variance: " + sampler.getVariance());
				SimulationMethod sm = sampler.getSimulationMethod();
				// Compute/print any missing parameters that need to be done after simulation
				sm.computeMissingParameterAfterSim();
				// Extract result from SimulationMethod and store
				results[i] = new Result();
				results[i].setSimulationMethod(sm);
				try {
					results[i].setResult(sm.getResult(sampler));
				} catch (PrismException e) {
					results[i].setResult(e);
				}
			}
			// Store result in the ResultsCollection
			resultsCollection.setResult(undefinedConstants.getMFConstantValues(), pfcs[i], results[i]);
		}

		// Display results to log
		mainLog.println("\nSimulation method parameters:");
		for (int i = 0; i < results.length; i++) {
			mainLog.print(pfcs[i] + " : ");
			mainLog.println((indices[i] == -1) ? "no simulation" : propertySamplers.get(indices[i]).getSimulationMethod().getParametersString());
		}
		mainLog.println("\nSimulation result details:");
		for (int i = 0; i < results.length; i++) {
			mainLog.print(pfcs[i] + " : ");
			mainLog.println((indices[i] == -1) ? "no simulation" : propertySamplers.get(indices[i]).getSimulationMethodResultExplanation());
		}
		mainLog.println("\nResults:");
		mainLog.print(resultsCollection.toStringPartial(undefinedConstants.getMFConstantValues(), true, " ", " : ", false));
	}
}