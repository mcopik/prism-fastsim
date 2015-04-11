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

import simulator.opencl.automaton.Guard;
import simulator.opencl.automaton.update.Rate;
import simulator.opencl.automaton.update.Update;

public class Command implements CommandInterface
{
	private final Guard guard;
	private final Update update;

	/**
	 * Rate of whole command (1.0 for DTMC).
	 */
	private final Rate rate;

	/**
	 * Private constructor, create from static factory methods.
	 * @param guard
	 * @param update
	 * @param rate
	 */
	private Command(Guard guard, Update update, Rate rate)
	{
		this.guard = guard;
		this.update = update;
		this.rate = rate;
	}

	/**
	 * @param guard
	 * @param update
	 * @return command for discrete-time Markov chain
	 */
	static public Command createCommandDTMC(Guard guard, Update update)
	{
		return new Command(guard, update, new Rate(1));
	}

	/**
	 * Additional task: create the sum of rates
	 * @param guard
	 * @param update
	 * @return command for continuous-time Markov chain
	 */
	static public Command createCommandCTMC(Guard guard, Update update)
	{
		Rate rate = new Rate(0);
		for (int i = 0; i < update.getActionsNumber(); ++i) {
			rate.addRate(update.getRate(i));
		}
		return new Command(guard, update, rate);
	}

	@Override
	public Guard getGuard()
	{
		return guard;
	}

	@Override
	public Update getUpdate()
	{
		return update;
	}

	@Override
	public boolean isSynchronized()
	{
		return false;
	}

	@Override
	public String toString()
	{
		StringBuilder builder = new StringBuilder();
		builder.append(guard).append(" -> ").append(update).append("\n");
		return builder.toString();
	}

	@Override
	public Rate getRateSum()
	{
		return rate;
	}
}
