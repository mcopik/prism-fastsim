//==============================================================================
//	
//	Copyright (c) 2002-
//	Authors:
//	* Dave Parker <d.a.parker@cs.bham.ac.uk> (University of Birmingham/Oxford)
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

package explicit;

import java.util.BitSet;
import java.util.Iterator;
import java.util.Map.Entry;

/**
 * Interface for (abstract) classes that provide (read-only) access to an explicit-state model with nondeterminism.
 */
public interface NondetModel extends Model
{
	// Accessors

	/**
	 * Get the number of nondeterministic choices in state s.
	 */
	public int getNumChoices(int s);

	/**
	 * Get the maximum number of nondeterministic choices in any state.
	 */
	public int getMaxNumChoices();

	/**
	 * Get the total number of nondeterministic choices over all states.
	 */
	public int getNumChoices();

	/**
	 * Get the action label (if any) for choice {@code i} of state {@code s}.
	 */
	public Object getAction(int s, int i);

	/**
	 * Check if all the successor states from choice {@code i} of state {@code s} are in the set {@code set}.
	 * @param s The state to check
	 * @param i Choice index
	 * @param set The set to test for inclusion
	 */
	public boolean allSuccessorsInSet(int s, int i, BitSet set);
	
	/**
	 * Check if some successor state from choice {@code i} of state {@code s} is in the set {@code set}.
	 * @param s The state to check
	 * @param i Choice index
	 * @param set The set to test for inclusion
	 */
	public boolean someSuccessorsInSet(int s, int i, BitSet set);
	
	/**
	 * Get an iterator over the transitions of state s and action i.
	 */
	public Iterator<Entry<Integer, Double>> getTransitionsIterator(int s, int i);
}