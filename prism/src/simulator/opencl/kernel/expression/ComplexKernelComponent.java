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
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import prism.Preconditions;
import simulator.opencl.kernel.KernelException;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.UDType;

public abstract class ComplexKernelComponent implements KernelComponent
{
	/**
	 * Local variables defined in this component.
	 */
	protected Map<String, CLVariable> localVars = new HashMap<>();
	/**
	 * Definitions of local variables.
	 */
	protected List<Expression> variableDefinitions = new ArrayList<>();
	/**
	 * Code included in this component.
	 */
	protected List<KernelComponent> body = new ArrayList<>();
	/**
	 * Includes required by this component.
	 */
	protected List<Include> necessaryIncludes = new ArrayList<>();
	
	/**
	 * Internal function - create the header of this component,
	 * which precedes the body.
	 * @return string with the code
	 */
	protected abstract String createHeader();

	/**
	 * Adds local variable in scope.
	 * @param var new variable
	 * @throws KernelException thrown when there exist variable with this same name
	 */
	public void addLocalVar(CLVariable var) throws KernelException
	{
		Preconditions.checkNotNull(var);
		if (localVars.containsKey(var.varName)) {
			throw new KernelException("Variable " + var.varName + " already exists!");
		}
		localVars.put(var.varName, var);
		updateIncludes(var);
	}

	/**
	 * @param name variable name
	 * @return null if local variable with this name doesn't exist
	 */
	public CLVariable getLocalVar(String name)
	{
		return localVars.get(name);
	}
	
	/**
	 * @return number of defined local variables
	 */
	public int getVarsNum()
	{
		return localVars.size();
	}
	
	/**
	 * Add expression to component's body
	 * @param expr
	 */
	public void addExpression(KernelComponent expr)
	{
		Preconditions.checkNotNull(expr, "Trying to add null reference to expression!");
		correctExpression(expr);
		body.add(expr);
		if (expr.hasIncludes()) {
			necessaryIncludes.addAll(expr.getIncludes());
		}
	}

	protected void correctExpression(KernelComponent expr)
	{
		if (expr instanceof ExpressionList) {
			Iterator<Expression> listIt = ((ExpressionList) expr).iterator();
			while(listIt.hasNext()) {
				correctExpression(listIt.next());
			}
		} else if (expr instanceof Expression) {
			correctExpression((Expression) expr);
		}
	}
	
	/**
	 * Remove whitespace and add commas
	 * (they may be sometimes unnecessary, but they are completely
	 * correct; it's easier to avoid compilation errors this way)
	 * @param expression
	 */
	protected void correctExpression(Expression expression)
	{
		Preconditions.checkNotNull(expression);
		int len = expression.exprString.length() - 1;
		while (len >= 0 && expression.exprString.charAt(len) != ';' &&
		//remove all whitespaces
				Character.isWhitespace(expression.exprString.charAt(len))) {
			--len;
		}
		if (len >= 0 && expression.exprString.charAt(len) != ';') {
			ExpressionGenerator.addComma(expression);
		}
	}
	
	/**
	 * Add expression from string.
	 * @param expr
	 */
	@Deprecated
	public void addExpression(String expr)
	{
		Preconditions.checkNotNull(expr, "Trying to add null reference to expression!");
		body.add(new Expression(expr));
	}

	/**
	 * Add includes for user-defined types.
	 * @param var
	 */
	protected final void updateIncludes(CLVariable var)
	{
		Preconditions.checkNotNull(var);
		if (var.varType instanceof UDType) {
			List<Include> list = ((UDType) var.varType).getIncludes();
			if (list != null) {
				necessaryIncludes.addAll(list);
			}
		}
	}
	
	/**
	 * Add include to component.
	 * @param include
	 */
	public void addInclude(Include include)
	{
		Preconditions.checkNotNull(include);
		necessaryIncludes.add(include);
	}
	
	/**
	 * Add includes to component.
	 * @param include
	 */
	public void addInclude(Collection<Include> includes)
	{
		Preconditions.checkNotNull(includes);
		necessaryIncludes.addAll(includes);
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.KernelComponent#hasInclude()
	 */
	@Override
	public final boolean hasIncludes()
	{
		return necessaryIncludes.size() != 0;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.KernelComponent#hasDeclaration()
	 */
	@Override
	public abstract boolean hasDeclaration();

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.KernelComponent#getInclude()
	 */
	@Override
	public final List<Include> getIncludes()
	{
		return necessaryIncludes.size() != 0 ? necessaryIncludes : null;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.KernelComponent#getDeclaration()
	 */
	@Override
	public abstract KernelComponent getDeclaration();

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.KernelComponent#accept(simulator.gpu.opencl.kernel.expression.VisitorInterface)
	 */
	@Override
	public abstract void accept(VisitorInterface v);

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.KernelComponent#getSource()
	 */
	@Override
	public String getSource()
	{
		StringBuilder source = new StringBuilder(createHeader());
		source.append("{\n");
		for (Map.Entry<String, CLVariable> var : localVars.entrySet()) {
			source.append(var.getValue().getDefinition()).append("\n");
		}
		for (KernelComponent e : body) {
			source.append(e.getSource()).append("\n");
		}
		source.append("\n}");
		return source.toString();
	}
}