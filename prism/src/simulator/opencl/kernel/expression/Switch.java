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
import java.util.List;

import prism.Preconditions;
import simulator.opencl.kernel.memory.CLVariable;

public class Switch extends ComplexKernelComponent
{
	/**
	 * Internal component.
	 */
	private static class Case implements KernelComponent
	{
		/**
		 * Case value.
		 */
		public final Expression value;
		/**
		 * Case body.
		 */
		public List<KernelComponent> commands = new ArrayList<>();

		/**
		 * Default constructor.
		 * @param value
		 */
		public Case(Expression value)
		{
			this.value = value;
		}

		@Override
		public boolean hasIncludes()
		{
			return false;
		}

		@Override
		public boolean hasDeclaration()
		{
			return false;
		}

		@Override
		public List<Include> getIncludes()
		{
			return null;
		}

		@Override
		public KernelComponent getDeclaration()
		{
			return null;
		}

		@Override
		public void accept(VisitorInterface v)
		{
			value.accept(v);
			for (KernelComponent command : commands) {
				command.accept(v);
			}
		}

		@Override
		public String getSource()
		{
			StringBuilder builder = new StringBuilder();
			if (value != null) {
				builder.append("case ").append(value.getSource()).append(":\n");
			} else {
				builder.append("default:\n ");
			}
			// Add brackets {}, so variable declarations will be allowed
			builder.append("{\n");
			for (KernelComponent command : commands) {
				builder.append(command.getSource()).append("\n");
			}
			builder.append("}\n");
			builder.append("break;");
			return builder.toString();
		}
	}

	/**
	 * True when the switch contains a 'default' case at the end.
	 */
	private boolean hasDefault = false;
	/**
	 * Switch condition to evaluate.
	 */
	private Expression switchCondition = null;
	/**
	 * Currently selected condition.
	 */
	private int conditionNumber = 0;

	/**
	 * Create switch from an expression.
	 * @param switchCondition
	 */
	public Switch(Expression switchCondition)
	{
		this.switchCondition = switchCondition;
	}

	/**
	 * Create switch from a variable, used as switch expression.
	 * @param var
	 */
	public Switch(CLVariable var)
	{
		this.switchCondition = new Expression(var.varName);
	}

	/**
	 * Add another case to switch.
	 * @param condition
	 */
	public void addCase(Expression condition)
	{
		if (hasDefault) {
			body.add(body.size() - 1, new Case(condition));
		} else {
			body.add(new Case(condition));
		}
	}

	/**
	 * Add the 'default' case.
	 */
	public void addDefault()
	{
		hasDefault = true;
	}

	/**
	 * Add another expression to case specified by first argument.
	 * @param conditionNumber
	 * @param command
	 */
	public void addExpression(int conditionNumber, KernelComponent command)
	{
		Preconditions.checkIndex(conditionNumber, body.size(), "Non-valid index of condition in Switch!");
		correctExpression(command);
		((Case) body.get(conditionNumber)).commands.add(command);
	}

	@Override
	public void addExpression(KernelComponent expr)
	{
		Preconditions.checkNotNull(expr, "Trying to add null reference to expression!");
		correctExpression(expr);
		addExpression(conditionNumber, expr);
		if (expr.hasIncludes()) {
			necessaryIncludes.addAll(expr.getIncludes());
		}
	}

	/**
	 * Specify current condition, used by addExpression method
	 * which doesn't use the condition number argument. 
	 * @param conditionNumber
	 */
	public void setConditionNumber(int conditionNumber)
	{
		Preconditions.checkIndex(conditionNumber, body.size(), "Non-valid index of condition in IfElse!");
		this.conditionNumber = conditionNumber;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.ComplexKernelComponent#createHeader()
	 */
	@Override
	protected String createHeader()
	{
		return null;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.ComplexKernelComponent#hasDeclaration()
	 */
	@Override
	public boolean hasDeclaration()
	{
		return false;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.ComplexKernelComponent#getDeclaration()
	 */
	@Override
	public KernelComponent getDeclaration()
	{
		return null;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.ComplexKernelComponent#accept(simulator.gpu.opencl.kernel.expression.VisitorInterface)
	 */
	@Override
	public void accept(VisitorInterface v)
	{
		for (KernelComponent component : body) {
			component.accept(v);
		}
	}

	@Override
	public String getSource()
	{
		StringBuilder source = new StringBuilder("switch(");
		source.append(switchCondition).append("){\n");
		for (KernelComponent e : body) {
			source.append(e.getSource()).append("\n");
		}
		source.append("}\n");
		return source.toString();
	}
}