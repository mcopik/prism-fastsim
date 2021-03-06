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
import java.util.List;
import java.util.Set;

import parser.ast.Expression;
import parser.ast.ExpressionVar;
import parser.ast.Updates;
import prism.Pair;
import prism.Preconditions;
import simulator.opencl.automaton.AbstractAutomaton.StateVector;
import simulator.opencl.automaton.PrismVariable;

public class Update
{
	/**
	 * Contains pairs of updates (variable and new value) with a rate.
	 */
	private List<Pair<Rate, Action>> updatesList = new ArrayList<>();

	/**
	 * Requires parser Updates object and created StateVector.
	 * @param updates
	 * @param variables
	 */
	public Update(Updates updates, StateVector variables)
	{
		int updatesCount = updates.getNumUpdates();
		for (int i = 0; i < updatesCount; ++i) {
			parser.ast.Update cur = updates.getUpdate(i);
			Expression prob = updates.getProbability(i);
			Rate rate = new Rate();
			Action action = new Action();
			rate.addRate(prob);
			int exprCount = cur.getNumElements();
			for (int j = 0; j < exprCount; ++j) {
				action.addExpr(variables.getVar(cur.getVar(j)), cur.getExpression(j));
			}
			updatesList.add(new Pair<>(rate, action));
		}
	}

	/**
	 * @return number of actions in this update
	 */
	public int getActionsNumber()
	{
		return updatesList.size();
	}

	/**
	 * @param updateNumber
	 * @return rate of specified update
	 */
	public Rate getRate(int updateNumber)
	{
		Preconditions.checkIndex(updateNumber, updatesList.size());
		return updatesList.get(updateNumber).first;
	}

	/**
	 * @param updateNumber
	 * @return list of actions of specified update
	 */
	public Action getAction(int updateNumber)
	{
		Preconditions.checkIndex(updateNumber, updatesList.size());
		return updatesList.get(updateNumber).second;
	}

	/**
	 * Find actions labeled "true" or e.g. "q=q"
	 * @param updateNumber
	 * @return true if action does not change state vector
	 */
	public boolean isActionTrue(int updateNumber)
	{
		Action action = updatesList.get(updateNumber).second;
		if (action.expressions.size() == 0) {
			return true;
		} else if (action.expressions.size() == 1) {
			Expression expr = action.expressions.get(0).second;
			PrismVariable var = action.expressions.get(0).first;
			if (expr instanceof ExpressionVar) {
				ExpressionVar varExpr = (ExpressionVar) expr;
				if (varExpr.getName().equals(var.name)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * @return all variables updated in this update, for all rates
	 */
	public Set<PrismVariable> updatedVariables()
	{
		Set<PrismVariable> vars = new HashSet<>();

		for (Pair<Rate, Action> pair : updatesList) {
			vars.addAll(pair.second.updatedVariables());
		}

		return vars;
	}

	@Override
	public String toString()
	{
		StringBuilder builder = new StringBuilder();
		for (Pair<Rate, Action> pair : updatesList) {
			builder.append(pair.first).append(":").append(pair.second).append(" ");
		}
		return builder.toString();
	}
}