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
package simulator.gpu.automaton.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import parser.ast.Expression;
import parser.ast.Updates;
import simulator.gpu.automaton.AbstractAutomaton;
import simulator.gpu.automaton.AbstractAutomaton.AutomatonType;
import simulator.gpu.automaton.AbstractAutomaton.StateVector;
import simulator.gpu.automaton.Guard;
import simulator.gpu.automaton.update.Update;

public class CommandBuilder
{
	private AbstractAutomaton.AutomatonType type = null;
	private List<CommandInterface> commands = new ArrayList<>();
	private Map<String, SynchronizedCommand> synchronizedCommands = new HashMap<>();
	private StateVector variables = null;
	private String currentModule = null;

	public CommandBuilder(AbstractAutomaton.AutomatonType type, StateVector vars)
	{
		this.type = type;
		this.variables = vars;
	}

	public void addCommand(String synch, Expression expr, Updates updates)
	{
		Command cmd = new Command(new Guard(expr), new Update(updates, variables));
		if (synch.isEmpty()) {
			commands.add(cmd);
		} else {
			SynchronizedCommand synCmd = null;
			if (synchronizedCommands.containsKey(synch)) {
				synCmd = synchronizedCommands.get(synch);
			} else {
				synCmd = new SynchronizedCommand(currentModule);
				synchronizedCommands.put(synch, synCmd);
			}
			if (type == AutomatonType.CTMC) {
				synCmd.addCommandCTMC(currentModule, cmd);
			} else {
				synCmd.addCommandDTMC(currentModule, cmd);
			}
		}
	}

	public void setCurrentModule(String name)
	{
		currentModule = name;
	}

	public List<CommandInterface> getResults()
	{
		commands.addAll(synchronizedCommands.values());
		return commands;
	}

	public int synchCmdsNumber()
	{
		return synchronizedCommands.size();
	}
}