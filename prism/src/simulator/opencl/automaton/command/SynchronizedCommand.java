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
package simulator.opencl.automaton.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import prism.Preconditions;
import prism.PrismException;
import simulator.opencl.automaton.Guard;
import simulator.opencl.automaton.PrismVariable;
import simulator.opencl.automaton.update.Action;
import simulator.opencl.automaton.update.Rate;
import simulator.opencl.automaton.update.Update;

public class SynchronizedCommand implements CommandInterface
{
	/**
	 * Represents the synchronized part for one module.
	 */
	public class ModuleGroup
	{
		public final String moduleName;

		/**
		 * Sum of rates of synchronized commands in the module.
		 */
		Rate rate = new Rate();

		/**
		 * All synchronized commands in the module.
		 */
		private List<Command> cmds = new ArrayList<>();

		public ModuleGroup(String name)
		{
			moduleName = name;
		}

		public void addCommand(Command cmd)
		{
			cmds.add(cmd);
			rate.addRate(cmd.getRateSum());
		}

		public int getCommandsNum()
		{
			return cmds.size();
		}

		/**
		 * @return string containing all new values, for all updates in this module
		 */
		private String getAllUpdates()
		{
			StringBuilder builder = new StringBuilder();

			// for every command
			for (Command cmd : cmds) {
				Update up = cmd.getUpdate();

				//for every action
				for (int i = 0; i < up.getActionsNumber(); ++i) {
					Action action = up.getAction(i);
					//for every updated value
					for (int j = 0; j < action.getUpdatesNumber(); ++j) {
						builder.append(action.getUpdateExpression(j)).append(" ");
					}
				}
			}

			return builder.toString();
		}
	}

	/**
	 * Synchronization label for this command.
	 */
	public final String synchLabel;

	/**
	 * module_name -> commands at module
	 */
	private Map<String, ModuleGroup> synchronizedCommands = new HashMap<>();

	/**
	 * All modules containing commands with given label.
	 */
	private List<String> moduleNames = new ArrayList<>();

	/**
	 * @param label synchronization label
	 */
	public SynchronizedCommand(String label)
	{
		synchLabel = label;
	}

	/**
	 * @return number of modules activated in synchronization
	 */
	public int getModulesNum()
	{
		return synchronizedCommands.size();
	}

	/**
	 * @param moduleName
	 * @param cmd
	 */
	public void addCommand(String moduleName, Command cmd)
	{
		getModule(moduleName).addCommand(cmd);
		if (moduleNames.size() != synchronizedCommands.size()) {
			moduleNames.add(moduleName);
		}
	}

	/**
	 * Internal helper method. Return or create&return module group.
	 * @param moduleName
	 * @return module group with given name
	 */
	private ModuleGroup getModule(String moduleName)
	{
		ModuleGroup group = null;
		if (synchronizedCommands.containsKey(moduleName)) {
			group = synchronizedCommands.get(moduleName);
		} else {
			group = new ModuleGroup(moduleName);
			synchronizedCommands.put(moduleName, group);
		}
		return group;
	}

	/**
	 * @param module
	 * @param command
	 * @return command in given module
	 */
	public Command getCommand(int module, int command)
	{
		Preconditions.checkIndex(module, moduleNames.size());
		List<Command> cmds = synchronizedCommands.get(moduleNames.get(module)).cmds;
		Preconditions.checkIndex(command, cmds.size());
		return cmds.get(command);
	}

	/**
	 * @param module
	 * @return number of commands in given module
	 */
	public int getCommandNumber(int module)
	{
		return synchronizedCommands.get(moduleNames.get(module)).getCommandsNum();
	}

	@Override
	public Guard getGuard()
	{
		throw new IllegalAccessError("Method getGuard is not " + "defined for type SynchronizedCommand");
	}

	@Override
	public Update getUpdate()
	{
		throw new IllegalAccessError("Method getUpdate is not " + "defined for type SynchronizedCommand");
	}

	@Override
	public boolean isSynchronized()
	{
		return true;
	}

	/**
	 * @param module
	 * @param update
	 * @return sum of rates for specific update in given module
	 * @throws PrismException
	 */
	public Rate getRateSumUpdate(int module, int update)
	{
		Preconditions.checkIndex(module, synchronizedCommands.size());
		List<Command> cmds = synchronizedCommands.get(module).cmds;
		Preconditions.checkIndex(update, cmds.size());
		return cmds.get(update).getRateSum();
	}

	/**
	 * @param i
	 * @return sum of rates for specific module
	 * @throws PrismException
	 */
	public Rate getRateSumModule(int i)
	{
		Preconditions.checkIndex(i, synchronizedCommands.size());
		return synchronizedCommands.get(i).rate;
	}

	/**
	 * @param i
	 * @return number of commands in a module
	 * @throws PrismException
	 */
	public int getCmdsNumberModule(int i)
	{
		Preconditions.checkIndex(i, synchronizedCommands.size());
		return synchronizedCommands.get(i).getCommandsNum();
	}

	@Override
	public String toString()
	{
		StringBuilder builder = new StringBuilder();
		builder.append("SYNCHRONIZED COMMAND: ").append(synchLabel).append("\n");
		for (Map.Entry<String, ModuleGroup> group : synchronizedCommands.entrySet()) {
			builder.append(group.getKey()).append(" - sum: ").append(group.getValue().rate).append("\n");
			for (Command cmd : group.getValue().cmds) {
				builder.append("Update sum: ").append(cmd.getRateSum()).append("; ").append(cmd).append("\n");
			}
		}
		return builder.toString();
	}

	@Override
	public Rate getRateSum()
	{
		Rate sum = new Rate(0);
		for (Map.Entry<String, ModuleGroup> group : synchronizedCommands.entrySet()) {
			sum.addRate(group.getValue().rate);
		}
		return sum;
	}

	/**
	 * @return maximal number of commands generated in this synchronized update (when every command is active)
	 */
	public int getMaxCommandsNum()
	{
		int cmdNumber = 0, sum = 1;
		for (int i = 0; i < getModulesNum(); ++i) {
			cmdNumber = getCommandNumber(i);
			sum *= cmdNumber;
		}
		return sum;
	}

	/**
	 * @return number of commands in all modules
	 */
	public int getCmdsNum()
	{
		int cmdNumber = 0;
		for (int i = 0; i < getModulesNum(); ++i) {
			cmdNumber += getCommandNumber(i);
		}
		return cmdNumber;
	}

	/**
	 * Generate set of variables which values need to be preserved before making the synchronized update.
	 * For example, for update:
	 * M_1
	 * [s] z = 0
	 * M_2
	 * [s] zx = z
	 * One need to save value of variable 'z' before processing the update.
	 * 
	 * For this procedure, just save every variable written in M_i and read in M_j, where i < j
	 * (don't make any difference between actions - variables will be put in a structure, for the simplicity of argument
	 * passing between helper methods). 
	 * @return set of variables required for saving
	 */
	public Set<PrismVariable> variablesCopiedBeforeUpdate()
	{
		// variables to save
		Set<PrismVariable> vars = new HashSet<>();
		Set<PrismVariable> updatedVars = new HashSet<>();
		for (Map.Entry<String, ModuleGroup> entry : synchronizedCommands.entrySet()) {
			ModuleGroup module = entry.getValue();

			Iterator<PrismVariable> it = updatedVars.iterator();
			String updates = module.getAllUpdates();

			while (it.hasNext()) {
				PrismVariable var = it.next();

				int index = updates.indexOf(var.name);
				//check for existence, if it is a suffix or a prefix
				if (index != -1) {

					//check if it is a prefix (of longer variable)
					if (updates.length() > index + var.name.length()) {
						Character c = updates.charAt(index + var.name.length());
						if (c == '_' || Character.isAlphabetic(c) || Character.isDigit(c))
							continue;
					}
					//check if it is a suffix
					if (index != 0) {
						Character c = updates.charAt(index - 1);
						if (c == '_' || Character.isAlphabetic(c) || Character.isDigit(c))
							continue;
					}

					vars.add(var);
					// no need to check it for other expressions, it's already saved
					it.remove();
				}
			}

			// add updated vars
			for (Command cmd : entry.getValue().cmds) {
				updatedVars.addAll(cmd.getUpdate().updatedVariables());
			}
		}

		return vars;
	}
}