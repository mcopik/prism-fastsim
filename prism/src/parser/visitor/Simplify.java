//==============================================================================
//	
//	Copyright (c) 2002-
//	Authors:
//	* Dave Parker <david.parker@comlab.ox.ac.uk> (University of Oxford, formerly University of Birmingham)
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

package parser.visitor;

import parser.ast.Expression;
import parser.ast.ExpressionBinaryOp;
import parser.ast.ExpressionFormula;
import parser.ast.ExpressionFunc;
import parser.ast.ExpressionLiteral;
import parser.ast.ExpressionUnaryOp;
import parser.type.TypeDouble;
import prism.PrismLangException;

/**
 * Simplify expressions (constant propagation, ...)
 */
public class Simplify extends ASTTraverseModify
{
	public Object visit(ExpressionBinaryOp e) throws PrismLangException
	{
		// Apply recursively
		e.setOperand1((Expression) (e.getOperand1().accept(this)));
		e.setOperand2((Expression) (e.getOperand2().accept(this)));
		// If all operands are literals, replace with literal
		if (e.getOperand1() instanceof ExpressionLiteral && e.getOperand2() instanceof ExpressionLiteral) {
			return new ExpressionLiteral(e.getType(), e.evaluate());
		}
		// Other special cases
		switch (e.getOperator()) {
		case ExpressionBinaryOp.IMPLIES:
			if (Expression.isFalse(e.getOperand1()) || Expression.isTrue(e.getOperand2()))
				return Expression.True();
			if (Expression.isFalse(e.getOperand2()))
				return Expression.Not(e.getOperand1());
			if (Expression.isTrue(e.getOperand1()))
				return e.getOperand2();
			break;
		case ExpressionBinaryOp.IFF:
			if (Expression.isFalse(e.getOperand1())) {
				if (Expression.isFalse(e.getOperand2())) {
					return Expression.True();
				} else if (Expression.isTrue(e.getOperand2())) {
					return Expression.False();
				}
			}
			if (Expression.isTrue(e.getOperand1())) {
				if (Expression.isFalse(e.getOperand2())) {
					return Expression.False();
				} else if (Expression.isTrue(e.getOperand2())) {
					return Expression.True();
				}
			}
			break;
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
			if (Expression.isTrue(e.getOperand1()) || Expression.isTrue(e.getOperand2()))
				return Expression.True();
			if (Expression.isFalse(e.getOperand2()))
				return e.getOperand1();
			if (Expression.isFalse(e.getOperand1()))
				return e.getOperand2();
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
			if (Expression.isFalse(e.getOperand1()) || Expression.isFalse(e.getOperand2()))
				return Expression.False();
			if (Expression.isTrue(e.getOperand2()))
				return e.getOperand1();
			if (Expression.isTrue(e.getOperand1()))
				return e.getOperand2();
			break;
		case ExpressionBinaryOp.PLUS:
			if (Expression.isInt(e.getOperand2()) && e.getOperand2().evaluateInt() == 0)
				return e.getOperand1();
			if (Expression.isInt(e.getOperand1()) && e.getOperand1().evaluateInt() == 0)
				return e.getOperand2();
			if (Expression.isDouble(e.getOperand2()) && e.getOperand2().evaluateDouble() == 0.0) {
				// Need to be careful that type is preserved
				e.getOperand1().setType(e.getType());
				return e.getOperand1();
			}
			if (Expression.isDouble(e.getOperand1()) && e.getOperand1().evaluateDouble() == 0.0) {
				// Need to be careful that type is preserved
				e.getOperand2().setType(e.getType());
				return e.getOperand2();
			}
			break;
		case ExpressionBinaryOp.MINUS:
			if (Expression.isInt(e.getOperand2()) && e.getOperand2().evaluateInt() == 0)
				return e.getOperand1();
			if (Expression.isInt(e.getOperand1()) && e.getOperand1().evaluateInt() == 0)
				return new ExpressionUnaryOp(ExpressionUnaryOp.MINUS, e.getOperand2());
			if (Expression.isDouble(e.getOperand2()) && e.getOperand2().evaluateDouble() == 0.0) {
				// Need to be careful that type is preserved
				e.getOperand1().setType(e.getType());
				return e.getOperand1();
			}
			if (Expression.isDouble(e.getOperand1()) && e.getOperand1().evaluateDouble() == 0.0)
				return new ExpressionUnaryOp(ExpressionUnaryOp.MINUS, e.getOperand2());
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
			if (Expression.isInt(e.getOperand2()) && e.getOperand2().evaluateInt() == 1)
				return e.getOperand1();
			if (Expression.isInt(e.getOperand1()) && e.getOperand1().evaluateInt() == 1)
				return e.getOperand2();
			if (Expression.isDouble(e.getOperand2()) && e.getOperand2().evaluateDouble() == 1.0) {
				// Need to be careful that type is preserved
				e.getOperand1().setType(e.getType());
				return e.getOperand1();
			}
			if (Expression.isDouble(e.getOperand1()) && e.getOperand1().evaluateDouble() == 1.0) {
				// Need to be careful that type is preserved
				e.getOperand2().setType(e.getType());
				return e.getOperand2();
			}
			if (Expression.isInt(e.getOperand2()) && e.getOperand2().evaluateInt() == 0) {
				// Need to be careful that type is preserved
				return (e.getType() instanceof TypeDouble) ? Expression.Double(0.0) : Expression.Int(0);
			}
			if (Expression.isInt(e.getOperand1()) && e.getOperand1().evaluateInt() == 0) {
				// Need to be careful that type is preserved
				return (e.getType() instanceof TypeDouble) ? Expression.Double(0.0) : Expression.Int(0);
			}
			if (Expression.isDouble(e.getOperand2()) && e.getOperand2().evaluateDouble() == 0.0) {
				// Need to be careful that type is preserved
				return (e.getType() instanceof TypeDouble) ? Expression.Double(0.0) : Expression.Int(0);
			}
			if (Expression.isDouble(e.getOperand1()) && e.getOperand1().evaluateDouble() == 0.0) {
				// Need to be careful that type is preserved
				return (e.getType() instanceof TypeDouble) ? Expression.Double(0.0) : Expression.Int(0);
			}
			break;
		case ExpressionBinaryOp.DIVIDE:
			// if the the division involves some other expression, then it
			// would be good to save information about necessary parentheses which we're removed
			// in recursive application of accept() at the beginning of this method
			if (e.getOperand2() instanceof ExpressionBinaryOp) {
				e.setOperand2(Expression.Parenth(e.getOperand2()));
			}
			break;
		}
		return e;
	}

	public Object visit(ExpressionUnaryOp e) throws PrismLangException
	{
		// Apply recursively
		e.setOperand((Expression) (e.getOperand().accept(this)));
		// If operand is a literal, replace with literal
		if (e.getOperand() instanceof ExpressionLiteral) {
			return new ExpressionLiteral(e.getType(), e.evaluate());
		}
		// Even if not a literal, remove any parentheses
		if (e.getOperator() == ExpressionUnaryOp.PARENTH) {
			return e.getOperand();
		}
		return e;
	}

	public Object visit(ExpressionFunc e) throws PrismLangException
	{
		int i, n;
		boolean literal;
		// Apply recursively
		n = e.getNumOperands();
		for (i = 0; i < n; i++) {
			if (e.getOperand(i) != null)
				e.setOperand(i, (Expression) (e.getOperand(i).accept(this)));
		}
		// If all operands are literals, replace with literal
		literal = true;
		n = e.getNumOperands();
		for (i = 0; i < n; i++) {
			if (!(e.getOperand(i) instanceof ExpressionLiteral)) {
				literal = false;
				break;
			}
		}
		if (literal) {
			return new ExpressionLiteral(e.getType(), e.evaluate());
		}
		return e;
	}

	public Object visit(ExpressionFormula e) throws PrismLangException
	{
		// If formula has an attached definition, just replace it with that
		return e.getDefinition() != null ? e.getDefinition() : e;
	}
}
