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

import java.util.HashMap;
import java.util.Map;

import prism.Pair;
import prism.Preconditions;
import simulator.opencl.automaton.Guard;
import simulator.opencl.automaton.PrismVariable;
import simulator.opencl.automaton.update.Action;
import simulator.opencl.automaton.update.Rate;
import simulator.opencl.kernel.SavedVariables;
import simulator.opencl.kernel.StateVector;
import simulator.opencl.kernel.memory.CLValue;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.ExpressionValue;

public class ExpressionGenerator
{
	/**
	 * @param object
	 * @return expression instance created directly from the string representation of an object
	 */
	static public <T> Expression fromString(T object)
	{
		Preconditions.checkNotNull(object, "ExpressionGenerator.fromString() called on null reference!");
		return new Expression(object.toString());
	}
	
	static public Expression standardFunctionCall(String functionName, Expression ... args)
	{
		StringBuilder builder = new StringBuilder(functionName);
		builder.append("(");
		for(Expression arg : args)
		{
			builder.append(arg).append(",");
		}
		builder.deleteCharAt( builder.length() - 1 );
		builder.append(")");
		return fromString( builder.toString() );
	}

	/**
	 * @param dest
	 * @param expr
	 * @return a = b, where a is a variable and b the expression
	 */
	static public Expression createAssignment(CLVariable dest, Expression expr)
	{
		Expression ret = createBinaryExpression(dest.getSource(), Operator.AS, expr);
		return ret;
	}

	/**
	 * @param dest
	 * @param source
	 * @return a = b, where a is a variable and b another variable
	 */
	static public Expression createAssignment(CLVariable dest, CLVariable source)
	{
		return createAssignment(dest, source.getName());
	}

	/**
	 * Operators for binary expressions.
	 * Includes typical arithmetical and logical operators, including augmented assignment operators.
	 */
	public enum Operator {
		GT, LT, GE, LE, EQ, NE, LAND, LOR, AS, ADD, SUB, MUL, DIV, ADD_AUGM, SUB_AUGM, MUL_AUGM, DIV_AUGM, LAND_AUGM
	};

	/**
	 * Representation of all operators in OpenCL.
	 */
	private static final Map<Operator, String> operatorsSource;
	static {
		operatorsSource = new HashMap<>();
		operatorsSource.put(Operator.GT, ">");
		operatorsSource.put(Operator.LT, "<");
		operatorsSource.put(Operator.GE, ">=");
		operatorsSource.put(Operator.LE, "<=");
		operatorsSource.put(Operator.EQ, "==");
		operatorsSource.put(Operator.LAND, "&&");
		operatorsSource.put(Operator.LOR, "||");
		operatorsSource.put(Operator.NE, "!=");
		operatorsSource.put(Operator.AS, "=");
		operatorsSource.put(Operator.ADD, "+");
		operatorsSource.put(Operator.SUB, "-");
		operatorsSource.put(Operator.MUL, "*");
		operatorsSource.put(Operator.DIV, "/");
		operatorsSource.put(Operator.ADD_AUGM, "+=");
		operatorsSource.put(Operator.SUB_AUGM, "-=");
		operatorsSource.put(Operator.MUL_AUGM, "*=");
		operatorsSource.put(Operator.DIV_AUGM, "/=");
		operatorsSource.put(Operator.LAND_AUGM, "&=");
	}

	/**
	 * @param expr1
	 * @param operator
	 * @param expr2
	 * @return expr1 operator expr2
	 */
	static public Expression createBinaryExpression(Expression expr1, Operator operator, Expression expr2)
	{
		return new Expression(String.format("%s %s %s", expr1, operatorsSource.get(operator), expr2));
	}

	/**
	 * @param expr
	 * @return !(expr)
	 */
	static public Expression createNegation(Expression expr)
	{
		expr.exprString = "!(" + expr.exprString + ")";
		return expr;
	}

	/**
	 * @param condition
	 * @param first
	 * @param second
	 * @return condition ? first : second
	 */
	static public Expression createConditionalAssignment(Expression condition, String first, String second)
	{
		return new Expression(String.format("%s ? %s : %s", condition.getSource(), first, second));
	}

	static public Expression createConditionalAssignment(Expression condition, Expression first, Expression second)
	{
		return new Expression(String.format("%s ? %s : %s", condition.getSource(), first.getSource(), second.getSource()));
	}

	/**
	 * @param expr
	 * @return (expr)
	 */
	static public Expression addParentheses(Expression expr)
	{
		expr.exprString = String.format("(%s)", expr.exprString);
		return expr;
	}

	/**
	 * @param expr
	 * @return expr;
	 */
	static public Expression addComma(Expression expr)
	{
		expr.exprString = expr.exprString + ";";
		return expr;
	}

	/**
	 * @param var
	 * @return var++
	 */
	static public Expression preIncrement(CLVariable var)
	{
		return new Expression("++" + var.varName);
	}
	
	/**
	 * @param var
	 * @return var++
	 */
	static public Expression postIncrement(CLVariable var)
	{
		return new Expression(var.varName + "++");
	}

	/**
	 * @return OpenCL's value of global ID - for first dimension
	 */
	static public CLValue assignGlobalID()
	{
		return new ExpressionValue(new Expression("get_global_id(0)"));
	}

	/**
	 * Call one of embedded functions - e.g. floor from cmath
	 * @param functionName
	 * @param args
	 * @return functionName(args)
	 */
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

	/**
	 * Convert action from PRISM parsers to OpenCL. 
	 * @param stateVector
	 * @param action
	 * @param translations contains translations of model variables to proper references at state vector structure
	 * @param savedVariables if not null, then references to 'save' place - will be used instead of translation in previous map
	 * @return action converted from PRISM model to OpenCL
	 */
	static public Expression convertPrismAction(CLVariable stateVector, Action action, StateVector.Translations translations,
			SavedVariables.Translations savedVariables)
	{
		StringBuilder builder = new StringBuilder();
		for (Pair<PrismVariable, parser.ast.Expression> expr : action.expressions) {

			builder.append(translations.translate(expr.first.name)).append(" = ");
			builder.append(convertPrismUpdate(stateVector, expr.second, translations, savedVariables).getSource());
			builder.append(";\n");
		}
		return new Expression(builder.toString());
	}

	/**
	 * @param stateVector
	 * @param action
	 * @param translations contains translations of model variables to proper references at state vector structure
	 * @param savedVariables if not null, then references to 'save' place - will be used instead of translation in previous map
	 * @param changeFlag write checking: new value == old value? 
	 * @param oldValue old value variable to use
	 * @return action converted from PRISM model to OpenCL
	 */
	//TODO: move to loopdetector?
	static public KernelComponent convertPrismAction(CLVariable stateVector, Action action, StateVector.Translations translations,
			SavedVariables.Translations savedVariables, CLVariable changeFlag, CLVariable oldValue)
	{
		ExpressionList list = new ExpressionList();
		for (Pair<PrismVariable, parser.ast.Expression> expr : action.expressions) {

			list.addExpression(createAssignment(oldValue, new Expression(translations.translate(expr.first.name))));

			String destinationSVName = translations.translate(expr.first.name);
			Preconditions.checkCondition(destinationSVName != null);

			Expression mainAssignment = new Expression(String.format("%s = %s", translations.translate(expr.first.name),
					convertPrismUpdate(stateVector, expr.second, translations, savedVariables).getSource()));
			addParentheses(mainAssignment);
			list.addExpression(createBinaryExpression(changeFlag.getSource(), Operator.LAND_AUGM, createConditionalAssignment(
			//destination == new_value
					createBinaryExpression(mainAssignment, Operator.EQ, oldValue.getSource()), "true", "false")));
		}
		return list;
	}

	static public Expression convertPrismUpdate(CLVariable stateVector, parser.ast.Expression expr, StateVector.Translations translations)
	{
		StringBuilder assignment = new StringBuilder();
		assignment.append(convertActionWithSV(stateVector, translations, null, expr.toString()));
		convertEquality(assignment);
		builderReplace(assignment, "|", "||");
		builderReplace(assignment, "&", "&&");
		return new Expression(assignment.toString());
	}
	
	/**
	 * @param stateVector
	 * @param expr
	 * @param translations contains translations of model variables to proper references at state vector structure
	 * @param savedVariables if not null, then references to 'save' place - will be used instead of translation in previous map
	 * @return convert variable update from PRISM model to OpenCL
	 */
	static public Expression convertPrismUpdate(CLVariable stateVector, parser.ast.Expression expr, StateVector.Translations translations,
			SavedVariables.Translations savedVariables)
	{
		StringBuilder assignment = new StringBuilder();
		assignment.append(convertActionWithSV(stateVector, translations, savedVariables, expr.toString()));
		convertEquality(assignment);
		builderReplace(assignment, "|", "||");
		builderReplace(assignment, "&", "&&");
		return new Expression(assignment.toString());
	}

	/**
	 * Convert PRISM's equality '=' check to '==' in OpenCL C.
	 * @param builder
	 */
	static private void convertEquality(StringBuilder builder)
	{
		int index = 0;
		while ((index = builder.indexOf("=", index)) != -1) {
			if (index == 0 || (builder.charAt(index - 1) != '!' && builder.charAt(index - 1) != '>' && builder.charAt(index - 1) != '<')) {
				builder.replace(index, index + 1, "==");
				index += 2;
			} else {
				index += 1;
			}
		}
	}

	/**
	 * @param stateVector
	 * @param translations contains translations of model variables to proper references at state vector structure
	 * @param savedVariables if not null, then references to 'save' place - will be used instead of translation in previous map
	 * @param action
	 * @return action with replaced all references to PRISM model variables
	 */
	static private String convertActionWithSV(CLVariable stateVector, StateVector.Translations translations,
			SavedVariables.Translations savedVariables, String action)
	{
		StringBuilder builder = new StringBuilder(action);
		for (Map.Entry<String, String> entry : translations.entrySet()) {

			if (savedVariables != null && savedVariables.hasTranslation(entry.getKey())) {
				continue;
			}
			builderReplaceMostCommon(builder, entry.getKey(), entry.getValue());
		}

		if (savedVariables != null) {
			for (Map.Entry<String, CLVariable> entry : savedVariables.entrySet()) {
				builderReplaceMostCommon(builder, entry.getKey(), entry.getValue().varName);
			}
		}

		return builder.toString();
	}

	/**
	 * @param translations contains translations of model variables to proper references at state vector structure
	 * @param rate
	 * @return rate of update with replaced all references to model variable
	 */
	static public Expression convertPrismRate(StateVector.Translations translations,
			SavedVariables.Translations savedVariables, Rate rate)
	{
		StringBuilder builder = new StringBuilder(rate.toString());
		for (Map.Entry<String, String> entry : translations.entrySet()) {

			if ( savedVariables != null && savedVariables.hasTranslation(entry.getKey()) )
				continue;
			builderReplaceMostCommon(builder, entry.getKey(), entry.getValue());
		}

		if(savedVariables != null) {
			for (Map.Entry<String, CLVariable> entry : savedVariables.entrySet()) {
				builderReplaceMostCommon(builder, entry.getKey(), entry.getValue().varName);
			}
		}

		return new Expression(builder.toString());
	}
	
	static public Expression convertPrismRate(StateVector.Translations translations, Rate rate)
	{
		return convertPrismRate(translations, null, rate);
	}

	/**
	 * @param translations contains translations of model variables to proper references at state vector structure
	 * @param expr
	 * @return PRISM property with replaced all references to model variable and fixed logical operators
	 */
	static public Expression convertPrismProperty(StateVector.Translations translations, String expr)
	{
		StringBuilder builder = new StringBuilder(expr);
		for (Map.Entry<String, String> entry : translations.entrySet()) {
			builderReplaceMostCommon(builder, entry.getKey(), entry.getValue());
		}
		convertEquality(builder);
		builderReplace(builder, "|", "||");
		builderReplace(builder, "&", "&&");
		return new Expression(builder.toString());
	}

	/**
	 * @param translations contains translations of model variables to proper references at state vector structure
	 * @param exprd guarding expression
	 * @return PRISM guard with replaced all references to model variable and fixed logical operators. It has to be a single expression,
	 * otherwise it couldn't be a logical condition with single value
	 */
	static public Expression convertPrismGuard(StateVector.Translations translations, parser.ast.Expression expr)
	{
		return convertPrismGuard(translations, expr.toString());
	}

	/**
	 * @param translations contains translations of model variables to proper references at state vector structure
	 * @param exprd possibly preprocessed guard
	 * @return PRISM guard with replaced all references to model variable and fixed logical operators. It has to be a single expression,
	 * otherwise it couldn't be a logical condition with single value
	 */
	static public Expression convertPrismGuard(StateVector.Translations translations, Guard expr)
	{
		return convertPrismGuard(translations, expr.toString());
	}

	/**
	 * Common implementation for method taking a preprocessed guard or a PRISM expression.
	 * Documentation - look for overloaded methods.
	 */
	static private Expression convertPrismGuard(StateVector.Translations translations, String expr)
	{
		StringBuilder builder = new StringBuilder(expr);
		for (Map.Entry<String, String> entry : translations.entrySet()) {
			builderReplaceMostCommon(builder, entry.getKey(), entry.getValue());
		}
		convertEquality(builder);
		builderReplace(builder, "|", "||");
		builderReplace(builder, "&", "&&");
		return new Expression(builder.toString());
	}

	/**
	 * Replace all references of 'first' with 'second' in builder.
	 * @param builder
	 * @param first
	 * @param second
	 */
	static private void builderReplace(StringBuilder builder, String first, String second)
	{
		int index = 0;
		while ((index = builder.indexOf(first, index)) != -1) {
			builder.replace(index, index + first.length(), second);
			index += second.length();
		}
	}

	/**
	 * Replace the longest instance of 'first' with 'second', i.e. only when it's surrounded with
	 * non-identifier characters, so variable xy won't be replaced with a reference to variable x.
	 * @param builder
	 * @param first
	 * @param second
	 */
	static private void builderReplaceMostCommon(StringBuilder builder, String first, String second)
	{
		int index = 0;
		while ((index = builder.indexOf(first, index)) != -1) {
			//check whether it is a prefix
			if (builder.length() > index + first.length() && isIdentifierCharacter(builder.charAt(index + first.length()))) {
				index += first.length();
			}
			//check if it is a suffix
			else if (index != 0 && isIdentifierCharacter(builder.charAt(index - 1))) {
				index += first.length();
			} else {
				builder.replace(index, index + first.length(), second);
				index += second.length();
			}
			//for safety
			index = Math.min(index, builder.length());
		}
	}

	/**
	 * Used for checking if the identifier word is not a prefix/suffix of longer identifier.
	 * @param c
	 * @return true if the character is a valid character of an identifier
	 */
	static private boolean isIdentifierCharacter(char c)
	{
		return c == '_' || Character.isAlphabetic(c) || Character.isDigit(c);
	}
}