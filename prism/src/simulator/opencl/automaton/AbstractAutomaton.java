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
package simulator.opencl.automaton;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import parser.VarList;
import parser.ast.Module;
import parser.ast.ModulesFile;
import prism.Preconditions;
import prism.PrismException;
import simulator.opencl.automaton.command.CommandBuilder;
import simulator.opencl.automaton.command.CommandInterface;

public abstract class AbstractAutomaton
{
	/**
	 * Right now supports only Markov chains.
	 */
	public enum AutomatonType {
		DTMC, CTMC
	}

	/**
	 * Parser files.
	 */
	protected ModulesFile modulesFile = null;

	/**
	 * Used for extraction of variables in model.
	 */
	protected VarList varList = null;

	/**
	 * Contains all commands, non-synchronized and synchronized.
	 */
	protected List<CommandInterface> commands = new ArrayList<>();

	/**
	 * Model's state vector - contains all variables.
	 */
	protected StateVector variables = new StateVector();

	/**
	 * Number of non-synchronized commands.
	 */
	protected int numOfCommands = 0;

	/**
	 * Number of synchronized commands.
	 */
	protected int numOfSyncCommands = 0;

	/**
	 * Represent all variables in PRISM model.
	 */
	public class StateVector
	{
		/**
		 * variable name -> variable object
		 */
		protected Map<String, PrismVariable> variables = new HashMap<>();

		/**
		 * Add new variable to SV. Assume that a variable with this name doesn't exist!
		 * @param var
		 */
		public void addVariable(PrismVariable var)
		{
			Preconditions.checkCondition(!variables.containsKey(var.name));
			variables.put(var.name, var);
		}

		/**
		 * Assumes that a variable with given name exists in model!
		 * @param name
		 * @return variable for given name
		 */
		public PrismVariable getVar(String name)
		{
			Preconditions.checkCondition(variables.containsKey(name));
			return variables.get(name);
		}

		/**
		 * @return all variables in model
		 */
		public PrismVariable[] getVars()
		{
			Collection<PrismVariable> vars = variables.values();
			return vars.toArray(new PrismVariable[vars.size()]);
		}

		/**
		 * @return number of variables
		 */
		public int size()
		{
			return variables.size();
		}

		@Override
		public String toString()
		{
			StringBuilder builder = new StringBuilder();
			for (Map.Entry<String, PrismVariable> var : variables.entrySet()) {
				builder.append(var.getValue().toString() + "\n");
			}
			return builder.toString();
		}
	}

	/**
	 * Default constructor.
	 * @param modulesFile
	 * @throws PrismException
	 */
	public AbstractAutomaton(ModulesFile modulesFile) throws PrismException
	{
		varList = modulesFile.createVarList();
		this.modulesFile = (ModulesFile) modulesFile.deepCopy().replaceConstants(modulesFile.getConstantValues()).simplify().accept(new ParsTreeModifier());
		this.modulesFile.tidyUp();
		extractVariables();
		extractUpdates();
	}

	/**
	 * @return number of non-synchronized commands.
	 */
	public int synchCmdsNumber()
	{
		return numOfSyncCommands;
	}

	/**
	 * @return number of synchronized commands.
	 */
	public int commandsNumber()
	{
		return commands.size();
	}

	/**
	 * @param number
	 * @return n-th command in model
	 */
	public CommandInterface getCommand(int number)
	{
		Preconditions.checkIndex(number, commands.size(), String.format("Command number %d is bigger than commands size", number));
		return commands.get(number);
	}

	/**
	 * Internal method - create PrismVariable objects for each variable in model.
	 */
	protected void extractVariables()
	{
		int varLen = varList.getNumVars(), i = 0;
		for (i = 0; i < varLen; ++i) {
			PrismVariable variable = new PrismVariable(varList.getName(i), varList.getLow(i), varList.getStart(i), varList.getRangeLogTwo(i));
			variables.addVariable(variable);
		}
	}

	/**
	 * Internal method - create PrismVariable objects for each variable in model.
	 */
	protected void extractUpdates()
	{
		CommandBuilder builder = new CommandBuilder(getType(), variables);
		for (String moduleName : modulesFile.getModuleNames()) {
			Module currentModule = modulesFile.getModule(modulesFile.getModuleIndex(moduleName));
			builder.setCurrentModule(currentModule.getName());
			for (int i = 0; i < currentModule.getNumCommands(); ++i) {
				builder.addCommand(currentModule.getCommand(i).getSynch(), currentModule.getCommand(i).getGuard(), currentModule.getCommand(i).getUpdates());
			}
		}
		numOfSyncCommands = builder.synchCmdsNumber();
		commands = builder.getResults();
	}

	/**
	 * @return type of automaton implemented in subclass
	 */
	public abstract AutomatonType getType();

	/**
	 * @return SV object
	 */
	public StateVector getStateVector()
	{
		return variables;
	}

	@Override
	public String toString()
	{
		StringBuilder builder = new StringBuilder("DTMC\n");
		builder.append("Variables: \n");
		builder.append(variables);
		builder.append("Commands: \n");
		for (CommandInterface cmd : commands) {
			builder.append(cmd).append("\n");
		}
		return builder.toString();
	}
}