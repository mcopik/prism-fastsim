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
package simulator.opencl.kernel.expression;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import prism.Preconditions;

public class ExpressionList implements KernelComponent,Iterable<Expression>
{
	/**
	 * List containing all expressions.
	 */
	private List<Expression> exprs = new ArrayList<>();
	/**
	 * Does one or more of the expression have includes?
	 */
	private boolean hasIncludes = false;
	/**
	 * Does one or more of the expression have declarations?
	 */
	private boolean hasDeclarations = false;
	
	/**
	 * Add expression to the list.
	 * @param expr
	 */
	public void addExpression(Expression expr)
	{
		Preconditions.checkNotNull(expr);
		exprs.add(expr);
		if (!hasIncludes && expr.hasIncludes()) {
			hasIncludes = true;
		}
		if (!hasDeclarations && expr.hasDeclaration()) {
			hasDeclarations = true;
		}
	}
	
	/**
	 * Add all expressions in list to the object.
	 * @param expr list
	 */
	public void addExpression(ExpressionList expr)
	{
		Preconditions.checkNotNull(expr);
		Iterator<Expression> it = expr.iterator();
		while (it.hasNext()) {
			addExpression(it.next());
		}
	}
	
	@Override
	public Iterator<Expression> iterator()
	{
		return exprs.iterator();
	}

	@Override
	public boolean hasIncludes()
	{
		return hasIncludes;
	}

	@Override
	public boolean hasDeclaration()
	{
		return hasDeclarations;
	}

	@Override
	public List<Include> getIncludes()
	{
		if (!hasIncludes) {
			return null;
		}
		List<Include> includes = new ArrayList<>();
		for (Expression expr : exprs) {
			if (expr.hasIncludes()) {
				includes.addAll(expr.getIncludes());
			}
		}
		return includes;
	}

	@Override
	public KernelComponent getDeclaration()
	{
		if (!hasDeclarations) {
			return null;
		}
		ExpressionList declarations = new ExpressionList();
		for (Expression expr : exprs) {
			if (expr.hasDeclaration()) {
				KernelComponent decl = expr.getDeclaration();
				if (decl instanceof Expression) {
					addExpression((Expression) decl);
				} else {
					addExpression((ExpressionList) decl);
				}
			}
		}
		return declarations;
	}

	@Override
	public void accept(VisitorInterface v)
	{
		for (Expression expr : exprs) {
			v.visit(expr);
		}
	}

	@Override
	public String getSource()
	{
		StringBuilder builder = new StringBuilder();
		for (Expression expr : exprs) {
			builder.append(expr.exprString).append("\n");
		}
		return builder.toString();
	}
}