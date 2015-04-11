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

public interface CommandInterface
{
	/**
	 * @return command's guard
	 */
	Guard getGuard();

	/**
	 * @return command's update
	 */
	Update getUpdate();

	/**
	 * @return sum of rates for the whole command; used in CTMC for transition selection
	 */
	Rate getRateSum();

	/**
	 * @return true for synchronized command
	 */
	boolean isSynchronized();
}