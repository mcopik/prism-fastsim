//==============================================================================
//	
//	Copyright (c) 2002-
//	Authors:
//	* Marcin Copik <mcopik@gmail.com> (RWTH Aachen, formerly Silesian University of Technology)
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

package simulator.opencl.automaton;

import parser.ast.Expression;
import parser.ast.ExpressionBinaryOp;
import parser.ast.ExpressionConstant;
import parser.ast.ExpressionFunc;
import parser.ast.ExpressionLiteral;
import parser.ast.ExpressionUnaryOp;
import parser.ast.ExpressionVar;
import parser.type.Type;
import parser.type.TypeDouble;
import parser.type.TypeInt;
import parser.visitor.ASTTraverseModify;
import prism.Preconditions;
import prism.PrismLangException;

/**
 * This class is used for:
 * - adding parentheses to binary expression
 * GPU simulator uses direct form (as a string) for kernel, so binary expressions like a/b-1
 * has to be transformed to a/(b-1) -> parentheses were deleted by Simplify visitor
 * - casting to float in division
 * 1/2 will be equal to 0 in C code 
 */
public class ParsTreeModifier extends ASTTraverseModify
{	
	
	public Object visit(ExpressionFunc e) throws PrismLangException
	{
		for(int i = 0;i < e.getNumOperands(); ++i) {
			e.setOperand(i, (Expression) (e.getOperand(i).accept(this)));
		}
		
		/**
		 * PRISM's logarithm is defined as: log(value, base).
		 * OpenCL offers several logarithms, based on Euler constant, 2, 10.
		 * The safest and cleanest way to do it is to use the well-known property of log function:
		 * log_b(x) = log_a(x)/log_a(b)
		 * 
		 * In our case, we use the natural logarithm log(x)
		 */
		if( e.getName().equals( ExpressionFunc.names[ExpressionFunc.LOG] )) {
			// Protect from changes in PRISM language
			Preconditions.checkCondition(e.getNumOperands() == 2);
			StringBuilder builder = new StringBuilder("log(");
			// the main argument of logarithm function
			builder.append( castExpression( e.getOperand(0) ) );
			//base
			builder.append( ") / log(");
			builder.append( castExpression( e.getOperand(1) ) );
			builder.append(")");
			return new ExpressionConstant( builder.toString(), e.getType());
		} 
		/**
		 * PRISM's mod functions has to be translated using C modulo operator:
		 * mod(a,b) -> a % b
		 * For safety, put every argument and whole expression in bracktets
		 */
		else if( e.getName().equals( ExpressionFunc.names[ExpressionFunc.MOD] )) {
			// Protect from changes in PRISM language
			Preconditions.checkCondition(e.getNumOperands() == 2);
			StringBuilder builder = new StringBuilder("( (");
			// no casting! it's an operation on integers
			builder.append( e.getOperand(0).toString() ).append(") % (");
			builder.append( e.getOperand(1).toString() ).append(") )");
			
			return new ExpressionConstant( builder.toString(), e.getType());
		} else {

			/**
			 * For every argument of function, cast it to float to avoid misunderstanding for overloaded functions
			 * Some functions require floating-point arguments and (float,double) is too confusing.
			 */
			for(int i = 0;i < e.getNumOperands(); ++i) {
				
				e.setOperand(i, castExpression( e.getOperand(i) ));
			}
		}
		
		return e;
	}
	
	public Object visit(ExpressionUnaryOp e) throws PrismLangException
	{
		e.setOperand((Expression) (e.getOperand().accept(this)));
		// avoid expressions of form !a op b
		// it's much better to have !(a op b)
		if (e.getOperator() == ExpressionUnaryOp.NOT) {
			e.setOperand(Expression.Parenth(e.getOperand()));
		}
		return e;
	}

	public Object visit(ExpressionBinaryOp e) throws PrismLangException
	{
		// Apply recursively
		Expression leftOperand = e.getOperand1();
		Expression rightOperand = e.getOperand2();
		e.setOperand1((Expression) (leftOperand.accept(this)));
		e.setOperand2((Expression) (rightOperand.accept(this)));

		switch (e.getOperator()) {
		case ExpressionBinaryOp.OR:

			// if the the logical operation involves some other expression, then it
			// would be good to save information about necessary parentheses which we're removed
			// in recursive application of accept() at the beginning of this method
			// so a | (b & c) won't change into a | b & c
			// tree structure is preserved, but if we wan't to use string description then it will
			// useless
			if (e.getOperand2() instanceof ExpressionBinaryOp) {
				e.setOperand2(Expression.Parenth(e.getOperand2()));
			}
			if (e.getOperand1() instanceof ExpressionBinaryOp) {
				e.setOperand1(Expression.Parenth(e.getOperand1()));
			}
			break;
		case ExpressionBinaryOp.AND:
			// if the the logical operation involves some other expression, then it
			// would be good to save information about necessary parentheses which we're removed
			// in recursive application of accept() at the beginning of this method
			// so a | (b & c) won't change into a | b & c
			// tree structure is preserved, but if we wan't to use string description then it will
			// useless
			if (e.getOperand2() instanceof ExpressionBinaryOp) {
				e.setOperand2(Expression.Parenth(e.getOperand2()));
			}
			if (e.getOperand1() instanceof ExpressionBinaryOp) {
				e.setOperand1(Expression.Parenth(e.getOperand1()));
			}
			break;
		case ExpressionBinaryOp.TIMES:
			// if the the multiplication involves some other expression, then it
			// would be good to save information about necessary parentheses which we're removed
			// in recursive application of accept() at the beginning of this method
			// e.g. i = (a+1)*(a+2) will become in toString:
			// i = a+1*a+2
			if (e.getOperand2() instanceof ExpressionBinaryOp) {
				e.setOperand2(Expression.Parenth(e.getOperand2()));
			}
			if (e.getOperand1() instanceof ExpressionBinaryOp) {
				e.setOperand1(Expression.Parenth(e.getOperand1()));
			}
			break;
		case ExpressionBinaryOp.DIVIDE:

			// if the the division involves some other expression, then it
			// would be good to save information about necessary parentheses which we're removed
			// in recursive application of accept() at the beginning of this method
			if (e.getOperand2() instanceof ExpressionBinaryOp) {
				e.setOperand2(Expression.Parenth(e.getOperand2()));
			}
			if (e.getOperand1() instanceof ExpressionBinaryOp) {
				e.setOperand1(Expression.Parenth(e.getOperand1()));
			}

			// add casting to float
			// it should be only an identifier or a literal
			Expression operand = e.getOperand1();
			e.setOperand1( castExpression(operand) );
			break;
		case ExpressionBinaryOp.IFF:
			// use the logical evaluation:
			// a <=> b TO (a & b) | (!a & !b)
			Expression newLeftOperand = Expression.And(leftOperand, rightOperand);
			Expression newRightOperand = Expression.And( 
					Expression.Not( Expression.Parenth(leftOperand) ), 
					Expression.Not( Expression.Parenth(rightOperand) ));
			return Expression.Or( Expression.Parenth(newLeftOperand), 
					Expression.Parenth(newRightOperand) );
		case ExpressionBinaryOp.IMPLIES:
			// use logical equivalence:
			// p => q TO !(p & !q)
			Expression middleOperand = Expression.And(leftOperand, 
					Expression.Not( Expression.Parenth(rightOperand)) );
			return Expression.Not(Expression.Parenth(middleOperand));
		}
		return e;
	}
	
	private Expression castExpression(Expression operand)
	{
		if (operand instanceof ExpressionLiteral) {
			Preconditions.checkCondition(operand instanceof ExpressionLiteral);
			Object value = ((ExpressionLiteral) operand).getValue();
			Type type = ((ExpressionLiteral) operand).getType();
			//assume: only Double and Integer
			Preconditions.checkCondition(type instanceof TypeInt || type instanceof TypeDouble, "Unknown type of expression " + type.getTypeString());
			/**
			 * Instead of using ExpressionLiteral, use ExpressionConstant and print in kernel as "2.0f" - 2.0 will be interpreted as double.
			 */
			if (type instanceof TypeInt) {
				return new ExpressionLiteral(operand.getType(), String.format("%ff",Double.valueOf((Integer) value)));
			} else {
				return new ExpressionLiteral(operand.getType(), String.format("%ff",(Double) value));
			}
		} else {
			String newVariable = String.format("(float)(%s)", operand);
			// will break parser rules!
			return new ExpressionVar(newVariable, operand.getType());
		}
	}
}
