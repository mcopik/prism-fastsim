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

import java.util.HashMap;
import java.util.Map;

import prism.Pair;
import prism.Preconditions;
import simulator.gpu.automaton.PrismVariable;
import simulator.gpu.automaton.update.Action;
import simulator.gpu.opencl.kernel.KernelException;
import simulator.gpu.opencl.kernel.memory.ArrayType;
import simulator.gpu.opencl.kernel.memory.CLValue;
import simulator.gpu.opencl.kernel.memory.CLVariable;
import simulator.gpu.opencl.kernel.memory.ExpressionValue;
import simulator.gpu.opencl.kernel.memory.PointerType;
import simulator.gpu.opencl.kernel.memory.StructureType;

/**
 * @author mcopik
 *
 */
public class ExpressionGenerator
{
	static public CLVariable accessStructureField(CLVariable structure, String fieldName)
	{
		Preconditions.checkCondition(structure.varType instanceof StructureType, "Can access field only in structure variable");
		StructureType type = (StructureType) structure.varType;
		CLVariable field = null;
		for (CLVariable var : type.getFields()) {
			if (var.varName.equals(fieldName)) {
				field = var;
				break;
			}
		}
		return field != null ? new CLVariable(field.varType, structure.varName + "." + field.varName) : null;
	}

	static public <T> Expression fromString(T object)
	{
		return new Expression(object.toString());
	}

	static public Expression createAssignment(CLVariable dest, String expr)
	{
		Expression ret = createBasicExpression(dest, Operator.AS, expr);
		ret.exprString += ";";
		return ret;
	}

	static public Expression createAssignment(CLVariable dest, Expression expr)
	{
		return createAssignment(dest, expr.getSource());
	}

	static public Expression createAssignment(CLVariable dest, CLVariable source)
	{
		return createAssignment(dest, source.getName());
	}

	public enum Operator {
		GT, LT, GE, LE, EQ, NE, AS, ADD, SUB, MUL, DIV, ADD_AUGM, SUB_AUGM, MUL_AUGM, DIV_AUGM
	};

	private static final Map<Operator, String> operatorsSource;
	static {
		operatorsSource = new HashMap<>();
		operatorsSource.put(Operator.GT, ">");
		operatorsSource.put(Operator.LT, "<");
		operatorsSource.put(Operator.GE, ">=");
		operatorsSource.put(Operator.LE, "<=");
		operatorsSource.put(Operator.EQ, "==");
		operatorsSource.put(Operator.NE, "!=");
		operatorsSource.put(Operator.AS, "=");
		operatorsSource.put(Operator.ADD, "+");
		operatorsSource.put(Operator.SUB, "-");
		operatorsSource.put(Operator.MUL, "*");
		operatorsSource.put(Operator.DIV, "/");
		operatorsSource.put(Operator.ADD_AUGM, "+=");
		operatorsSource.put(Operator.SUB_AUGM, "-=");
		operatorsSource.put(Operator.MUL_AUGM, "*=");
		operatorsSource.put(Operator.MUL_AUGM, "/=");
	}

	static public Expression createBasicExpression(CLVariable var, Operator operator, CLValue var2)
	{
		return ExpressionGenerator.createBasicExpression(var, operator, var2.getSource());
	}

	static public Expression createBasicExpression(Expression var, Operator operator, CLValue expr)
	{
		return new Expression(String.format("%s %s %s", var, operatorsSource.get(operator), expr.getSource()));
	}

	static public Expression createBasicExpression(CLVariable var, Operator operator, String expr)
	{
		return new Expression(String.format("%s %s %s", var.varName, operatorsSource.get(operator), expr));
	}

	static public Expression createBasicExpression(CLVariable var, Operator operator, Expression expr)
	{
		return ExpressionGenerator.createBasicExpression(var, operator, expr.getSource());
	}

	static public Expression createNegation(Expression var)
	{
		var.exprString = "!(" + var.exprString + ")";
		return var;
	}

	static public Expression createConditionalAssignment(String dest, String condition, String first, String second)
	{
		return new Expression(String.format("%s = %s ? %s : %s;", dest, condition, first, second));
	}

	static public String accessArrayElement(CLVariable var, int indice) throws KernelException
	{
		if (var.varType instanceof ArrayType || var.varType instanceof PointerType) {
			return String.format("%s[%d]", var.varName, indice);
		} else {
			throw new KernelException(String.format("Trying to access %d-ith position in variable %s which is not an array or a pointer!", indice, var.varName));
		}
	}

	static public void addParentheses(Expression expr)
	{
		expr.exprString = String.format("(%s)", expr.exprString);
	}

	static public Expression postIncrement(CLVariable var)
	{
		return new Expression(var.varName + "++");
	}

	static public CLValue assignGlobalID()
	{
		return new ExpressionValue(new Expression("get_global_id(0)"));
	}

	static public Expression functionCall(String functionName, Expression... args)
	{
		StringBuilder builder = new StringBuilder(functionName);
		builder.append("(");
		for (Expression arg : args) {
			builder.append(arg.getSource()).append(",");
		}
		builder.deleteCharAt(builder.length() - 1);
		builder.append(")");
		return new Expression(builder.toString());
	}

	static public Expression convertPrismAction(Action action)
	{
		StringBuilder builder = new StringBuilder();
		for (Pair<PrismVariable, parser.ast.Expression> expr : action.expressions) {
			builder.append(expr.first.name).append(" = ").append(expr.second.toString()).append(";");
			builder.append("\n");
		}
		return new Expression(builder.toString());
	}
}
