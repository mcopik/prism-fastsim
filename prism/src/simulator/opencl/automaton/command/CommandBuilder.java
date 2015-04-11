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
import java.util.List;
import java.util.Map;

import parser.ast.Expression;
import parser.ast.Updates;
import prism.Preconditions;
import simulator.opencl.automaton.AbstractAutomaton;
import simulator.opencl.automaton.AbstractAutomaton.AutomatonType;
import simulator.opencl.automaton.AbstractAutomaton.StateVector;
import simulator.opencl.automaton.Guard;
import simulator.opencl.automaton.update.Update;

public class CommandBuilder
{
	/**
	 * Rates are summed differently for DTMC and CTMC.
	 */
	private final AbstractAutomaton.AutomatonType type;

	/**
	 * Contains generated non-synchronized commands.
	 */
	private List<CommandInterface> commands = new ArrayList<>();

	/**
	 * Contains generated synchronized commands.
	 */
	private Map<String, SynchronizedCommand> synchronizedCommands = new HashMap<>();

	/**
	 * StateVector for this model.
	 */
	private final StateVector variables;

	/**
	 * Currently processed module - used for generation of synchronized commands.
	 */
	private String currentModule = null;

	/**
	 * Requires completely build StateVector for the model.
	 * @param type
	 * @param vars
	 */
	public CommandBuilder(AbstractAutomaton.AutomatonType type, StateVector vars)
	{
		//may add something in future
		Preconditions.checkCondition(type == AutomatonType.DTMC || type == AutomatonType.CTMC);
		this.type = type;
		this.variables = vars;
	}

	/**
	 * Add command from PRISM model.
	 * @param synch synchronization label
	 * @param expr guard
	 * @param updates updates
	 */
	public void addCommand(String synch, Expression expr, Updates updates)
	{
		Command cmd = null;
		if (type == AutomatonType.CTMC) {
			cmd = Command.createCommandCTMC(new Guard(expr), new Update(updates, variables));
		} else {
			cmd = Command.createCommandDTMC(new Guard(expr), new Update(updates, variables));
		}
		if (synch.isEmpty()) {
			commands.add(cmd);
		} else {
			SynchronizedCommand synCmd = null;
			if (synchronizedCommands.containsKey(synch)) {
				synCmd = synchronizedCommands.get(synch);
			} else {
				synCmd = new SynchronizedCommand(synch);
				synchronizedCommands.put(synch, synCmd);
			}
			synCmd.addCommand(currentModule, cmd);
		}
	}

	/**
	 * Change currently processed module (used only for synchronization commands)
	 * @param name
	 */
	public void setCurrentModule(String name)
	{
		currentModule = name;
	}

	/**
	 * @return non-synchronized first, then synchronized commands
	 */
	public List<CommandInterface> getResults()
	{
		commands.addAll(synchronizedCommands.values());
		return commands;
	}

	/**
	 * @return number of synchronized commands
	 */
	public int synchCmdsNumber()
	{
		return synchronizedCommands.size();
	}
}