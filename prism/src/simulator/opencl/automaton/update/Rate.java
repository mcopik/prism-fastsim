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
import java.util.List;

import parser.ast.Expression;
import prism.PrismLangException;

public class Rate
{
	/**
	 * Value of constant part.
	 */
	public double rate = 0.0;

	/**
	 * Non-constant parts of the rate.
	 */
	public List<Expression> expressions = null;

	/**
	 * When false, then expressions contains list of volatile parts of rate.
	 */
	private boolean isConst = true;

	/**
	 * Default constructor.
	 */
	public Rate()
	{

	}

	/**
	 * Copy constructor.
	 * @param rate
	 */
	public Rate(Rate rate)
	{
		this.rate = rate.rate;
		this.isConst = rate.isConst;
		if (rate.expressions != null) {
			for (Expression expr : rate.expressions) {
				this.expressions = new ArrayList<>();
				this.expressions.add(expr.deepCopy());
			}
		}
	}

	/**
	 * Constructor initializing rate with constant value.
	 * @param initial
	 */
	public Rate(double initial)
	{
		rate = initial;
	}

	/**
	 * Add an expression to rate (not necessarily constant.
	 * @param expr
	 */
	public void addRate(Expression expr)
	{
		if (expr == null) {
			rate += 1.0;
			return;
		}
		if (expr.isConstant()) {
			add(expr);
		} else {
			expressions = new ArrayList<>();
			expressions.add(expr);
			this.isConst = false;
		}
	}

	/**
	 * Add the whole rate.
	 * @param rate
	 */
	public void addRate(Rate rate)
	{
		this.rate += rate.rate;
		if (!rate.isConst) {
			if (isConst) {
				expressions = new ArrayList<>();
			}
			expressions.addAll(rate.expressions);
			this.isConst = false;
		}
	}

	/**
	 * Internal method - add ONLY constant expression to rate (evaluate and sum).
	 * @param expr
	 */
	private void add(Expression expr)
	{
		try {
			rate += expr.evaluateDouble();
		} catch (PrismLangException e) {
			// should not happen, this method will be called only for constant expressions
			e.printStackTrace();
		}
	}

	/**
	 * @return true for constant expressions (no volatile expressions)
	 */
	public boolean isRateConst()
	{
		return isConst;
	}

	@Override
	public String toString()
	{
		StringBuilder builder = new StringBuilder();
		if (rate != 0.0) {
			builder.append("(").append(rate).append(") +");
		}
		if (expressions != null) {
			for (Expression expr : expressions) {
				builder.append("(").append(expr).append(") +");
			}
		}
		builder.deleteCharAt(builder.length() - 1);
		return builder.toString();
	}
}