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

package simulator.gpu.automaton;

import parser.ast.Expression;
import parser.ast.ExpressionBinaryOp;
import parser.ast.ExpressionConstant;
import parser.ast.ExpressionFormula;
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
 * Simplify expressions (constant propagation, ...)
 */
public class ParsTreeModifier extends ASTTraverseModify
{
	public Object visit(ExpressionBinaryOp e) throws PrismLangException
	{
		// Apply recursively
		e.setOperand1((Expression) (e.getOperand1().accept(this)));
		e.setOperand2((Expression) (e.getOperand2().accept(this)));
		
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
			/*
			if( operand instanceof ExpressionVar || operand instanceof ExpressionUnaryOp) {
				e.setOperand1( new ExpressionConstant( String.format("((float)%s)", e.getOperand1()) , operand.getType()) );
			} else {
				Preconditions.checkCondition( operand instanceof ExpressionLiteral);
				Object value = ( (ExpressionLiteral) operand).getValue();
				Type type = ( (ExpressionLiteral) operand).getType();
				//assume: only Double and Integer
				Preconditions.checkCondition( value instanceof TypeInt || value instanceof TypeDouble);
				if( type instanceof TypeInt ) {
					e.setOperand2( new ExpressionLiteral(TypeDouble.getInstance(), Double.valueOf( (Integer) value) ) );
				}
			}*/
			break;
		}
		return e;
	}
}
