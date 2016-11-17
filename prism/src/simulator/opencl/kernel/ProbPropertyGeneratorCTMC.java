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
package simulator.opencl.kernel;

import static simulator.opencl.kernel.expression.ExpressionGenerator.createBinaryExpression;
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;
import parser.ast.ExpressionLiteral;
import prism.PrismLangException;
import simulator.opencl.kernel.KernelGenerator.LocalVar;
import simulator.opencl.kernel.expression.ComplexKernelComponent;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.IfElse;
import simulator.opencl.kernel.expression.Method;
import simulator.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.opencl.kernel.memory.CLValue;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.opencl.kernel.memory.StdVariableType.StdType;
import simulator.sampler.SamplerBoolean;
import simulator.sampler.SamplerBoundedUntilCont;

public class ProbPropertyGeneratorCTMC extends ProbPropertyGenerator
{
	/**
	 * Variable used only for bounded until.
	 */
	private CLVariable propertyMethodVarUpdatedTime = null;

	public ProbPropertyGeneratorCTMC(KernelGenerator generator) throws PrismLangException, KernelException
	{
		super(generator);
	}

	@Override
	public boolean needsTimeDifference()
	{
		return timedProperty;
	}
	
	@Override
	protected boolean isTimedProperty(SamplerBoolean property)
	{
		return property instanceof SamplerBoundedUntilCont;
	}

	/**
	 * CODE GENERATION for main kernel function. 
	 */

	@Override
	public void kernelFirstUpdateProperties(ComplexKernelComponent parent, StateVector.Translations translations)
	{
		/**
		 * For the case of bounded until in CTMC, we have to check initial state at time 0.
		 * 
		 * It is important to remember that this code is injected directly to kernel, hence we
		 * can use translations for a pointer just like in a separate function.
		 */
		for (int i = 0; i < properties.size(); ++i) {

			SamplerBoolean property = properties.get(i);
			if (property instanceof SamplerBoundedUntilCont) {
				
				SamplerBoundedUntilCont prop = (SamplerBoundedUntilCont) property;
				CLVariable propertyVar = varPropertiesArray.accessElement(fromString(i));
				if (prop.getLowBound() == 0.0) {
					IfElse ifElse = createPropertyCondition(propertyVar, false, prop.getRightSide().toString(),
							true, translations);
					parent.addExpression(ifElse);
				}
				/**
				 * we do not have to check left side if it is constant 'true'
				 */
				else if (!(prop.getLeftSide() instanceof ExpressionLiteral)) {
					IfElse ifElse = createPropertyCondition(propertyVar, true, prop.getLeftSide().toString(),
							false, translations);
					parent.addExpression(ifElse);
				}
			}
		}
	}
	
	@Override
	protected Expression kernelUpdatePropertiesTimed(CLVariable sv, CLValue updatedTime)
	{
		return propertyUpdateMethod.callMethod(
						sv.convertToPointer(),
						varPropertiesArray,
						generator.kernelGetLocalVar(LocalVar.TIME),
						updatedTime
						);
	}

	/**
	 * PROPERTY METHOD: creation and definition.
	 */

	@Override
	protected void propertiesMethodTimeArg(Method currentMethod) throws KernelException
	{
		if (timedProperty) {
			propertyMethodVarTime = new CLVariable(new StdVariableType(StdType.FLOAT), "time");
			currentMethod.addArg(propertyMethodVarTime);
			propertyMethodVarUpdatedTime = new CLVariable(new StdVariableType(StdType.FLOAT), "updated_time");
			currentMethod.addArg(propertyMethodVarUpdatedTime);
		}
	}

	@Override
	protected void propertiesMethodAddBoundedUntil(Method currentMethod, ComplexKernelComponent parent,
			StateVector.Translations translations, SamplerBoolean property, CLVariable propertyVar) throws PrismLangException
	{
		SamplerBoundedUntilCont prop = (SamplerBoundedUntilCont) property;

		String propertyStringRight = visitPropertyExpression(prop.getRightSide()).toString();
		String propertyStringLeft = visitPropertyExpression(prop.getLeftSide()).toString();
		/**
		 * if(updated_time > upper_bound)
		 */
		IfElse ifElse = null;
		if (!Double.isInfinite(prop.getUpperBound())) {
			ifElse = new IfElse(createBinaryExpression(
					propertyMethodVarUpdatedTime.getSource(),
					Operator.GT, fromString(prop.getUpperBound())));
			/**
			 * if(right_side == true) -> true
			 * else -> false
			 */
			IfElse rhsCheck = createPropertyCondition(propertyVar, false, propertyStringRight,
					true, translations);
			createPropertyCondition(rhsCheck, propertyVar, false, null,
					false, translations);
			ifElse.addExpression(rhsCheck);
		}

		/**
		 * else if(updated_time < low_bound)
		 */
		if (prop.getLowBound() != 0.0) {
			int position = 0;
			Expression condition = createBinaryExpression(propertyMethodVarUpdatedTime.getSource(),
					Operator.LE,
					// updated_time < lb
					fromString(prop.getLowBound()));
			if (ifElse != null) {
				ifElse.addElif(condition);
				position = 1;
			} else {
				ifElse = new IfElse(condition);
				position = 0;
			}
			/**
			 * if(left_side == false) -> false
			 */
			if (!(prop.getLeftSide() instanceof ExpressionLiteral)) {
				IfElse lhsCheck = createPropertyCondition(propertyVar, true, propertyStringLeft,
						false, translations);
				ifElse.addExpression(position, lhsCheck);
			}
		}

		/**
		 * Else - inside the interval
		 */

		/**
		 * if(right_side == true) -> true
		 * else if(left_side == false) -> false
		 */
		IfElse betweenBounds = createPropertyCondition(propertyVar, false, propertyStringRight,
				true, translations);
		if (!(prop.getLeftSide() instanceof ExpressionLiteral)) {
			createPropertyCondition(betweenBounds, propertyVar, true, propertyStringLeft,
					false, translations);
		}

		/**
		 * No condition before, just add this check to method.
		 */
		if (ifElse == null) {
			parent.addExpression(betweenBounds);
		}
		/**
		 * Add 'else'
		 */
		else {
			ifElse.addElse();
			ifElse.addExpression(ifElse.size() - 1, betweenBounds);
			parent.addExpression(ifElse);
		}
	}
}
