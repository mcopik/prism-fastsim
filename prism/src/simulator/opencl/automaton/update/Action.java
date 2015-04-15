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
package simulator.opencl.automaton.update;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import parser.ast.Expression;
import prism.Pair;
import prism.Preconditions;
import simulator.opencl.automaton.PrismVariable;

public class Action
{
	/**
	 * List of updates: var = new value
	 */
	public List<Pair<PrismVariable, Expression>> expressions = new ArrayList<>();

	/**
	 * Add new variable update
	 * @param var
	 * @param expr
	 */
	public void addExpr(PrismVariable var, Expression expr)
	{
		expressions.add(new Pair<>(var, expr));
	}

	/**
	 * @return number of updates in this action
	 */
	public int getUpdatesNumber()
	{
		return expressions.size();
	}

	/**
	 * @param updateNumber
	 * @return updated variable in given update
	 */
	public PrismVariable getUpdateDestination(int updateNumber)
	{
		Preconditions.checkIndex(updateNumber, expressions.size());
		return expressions.get(updateNumber).first;
	}

	/**
	 * @param updateNumber
	 * @return new value in given update
	 */
	public Expression getUpdateExpression(int updateNumber)
	{
		Preconditions.checkIndex(updateNumber, expressions.size());
		return expressions.get(updateNumber).second;
	}

	/**
	 * @return all variables updated in this action
	 */
	public Set<PrismVariable> updatedVariables()
	{
		Set<PrismVariable> vars = new HashSet<>();

		for (Pair<PrismVariable, Expression> pair : expressions) {
			vars.add(pair.first);
		}

		return vars;
	}

	/**
	 * Generate set of variables which values need to be preserved before making an update.
	 * For example, for update:
	 * z = 0, zx = z
	 * One need to save value of variable 'z' before processing the update.
	 * @return set of variables required for saving
	 */
	public Set<PrismVariable> variablesCopiedBeforeUpdate()
	{
		Set<PrismVariable> vars = new HashSet<>();
		Set<PrismVariable> updatedVars = new HashSet<>();
		for (Pair<PrismVariable, Expression> pair : expressions) {

			//easier rather than walking through the tree
			Iterator<PrismVariable> it = updatedVars.iterator();
			while (it.hasNext()) {
				PrismVariable var = it.next();
				// The update contains variable which has been already updated
				// We need to have a copy
				String varName = pair.second.toString();
				int index = varName.indexOf(var.name);
				//check for existence, if it is a suffix or a prefix
				if (index != -1) {

					//check if it is a prefix (of longer variable)
					if (varName.length() > index + var.name.length()) {
						Character c = varName.charAt(index + var.name.length());
						if (c == '_' || Character.isAlphabetic(c) || Character.isDigit(c))
							continue;
					}
					//check if it is a suffix
					if (index != 0) {
						Character c = varName.charAt(index - 1);
						if (c == '_' || Character.isAlphabetic(c) || Character.isDigit(c))
							continue;
					}

					vars.add(var);
					// no need to check it for other expressions, it's already saved
					it.remove();
				}
			}
			updatedVars.add(pair.first);
		}
		return vars;
	}

	@Override
	public String toString()
	{
		StringBuilder builder = new StringBuilder();
		for (Pair<PrismVariable, Expression> expr : expressions) {
			builder.append("(").append(expr.first.name).append("=").append(expr.second).append(")");
		}
		return builder.toString();
	}
}
