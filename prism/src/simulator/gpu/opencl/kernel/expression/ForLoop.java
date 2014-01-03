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
package simulator.gpu.opencl.kernel.expression;

import simulator.gpu.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.gpu.opencl.kernel.memory.CLVariable;
import simulator.gpu.opencl.kernel.memory.StdVariableType;

public class ForLoop extends ComplexKernelComponent
{
	private CLVariable counter;
	private String endValue = null;
	boolean decreasing = false;

	public ForLoop(CLVariable counter, boolean decreasing)
	{
		this.counter = counter;
		this.decreasing = decreasing;
	}

	public ForLoop(String counterName, int startValue, int endValue)
	{
		counter = new CLVariable(new StdVariableType(startValue, endValue), counterName);
		counter.setInitValue(StdVariableType.initialize(startValue));
		this.endValue = Integer.toString(endValue);
	}

	public ForLoop(String counterName, int startValue, int endValue, boolean decreasing)
	{
		if (decreasing) {
			counter = new CLVariable(new StdVariableType(endValue, startValue), counterName);
			counter.setInitValue(StdVariableType.initialize(endValue));
			this.endValue = Integer.toString(endValue);
			decreasing = true;
		} else {
			counter = new CLVariable(new StdVariableType(startValue, endValue), counterName);
			counter.setInitValue(StdVariableType.initialize(startValue));
			this.endValue = Integer.toString(endValue);
		}
	}

	@Override
	public boolean hasDeclaration()
	{
		return false;
	}

	@Override
	public Expression getDeclaration()
	{
		return null;
	}

	@Override
	public void accept(VisitorInterface v)
	{
		for (Expression expr : variableDefinitions) {
			expr.accept(v);
		}
		for (KernelComponent component : body) {
			component.accept(v);
		}
	}

	@Override
	protected String createHeader()
	{
		StringBuilder builder = new StringBuilder("for(");
		if (endValue != null) {
			builder.append(counter.getDefinition());
			if (decreasing) {
				builder.append(ExpressionGenerator.createBasicExpression(counter, Operator.GT, endValue));
			} else {
				builder.append(ExpressionGenerator.createBasicExpression(counter, Operator.LT, endValue));
			}
		} else {
			builder.append(";");
		}
		builder.append(";");
		if (decreasing) {
			builder.append("--");
		} else {
			builder.append("++");
		}
		builder.append(counter.varName).append(")");
		return builder.toString();
	}
}