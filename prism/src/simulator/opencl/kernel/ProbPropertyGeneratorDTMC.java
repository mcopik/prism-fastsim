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
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.sampler.SamplerBoolean;
import simulator.sampler.SamplerBoundedUntilCont;
import simulator.sampler.SamplerBoundedUntilDisc;

public class ProbPropertyGeneratorDTMC extends ProbPropertyGenerator
{

	public ProbPropertyGeneratorDTMC(KernelGenerator generator) throws PrismLangException, KernelException
	{
		super(generator);
	}

	@Override
	public boolean needsTimeDifference()
	{
		return false;
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
	public void kernelFirstUpdateProperties(ComplexKernelComponent parent)
	{
		//in case of DTMC, there is nothing to do
	}
	
	@Override
	public Expression kernelUpdateProperties()
	{
		if(activeGenerator) {
			CLVariable stateVector = generator.kernelGetLocalVar(LocalVar.STATE_VECTOR);
			if (timedProperty) {
				return propertyUpdateMethod.callMethod(
						stateVector.convertToPointer(),
						varPropertiesArray,
						generator.kernelGetLocalVar(LocalVar.TIME)
						);
			} else {
				return propertyUpdateMethod.callMethod(
						stateVector.convertToPointer(),
						varPropertiesArray
						);
			}
		}
		return new Expression();
	}

	/**
	 * PROPERTY METHOD: creation and definition.
	 */

	@Override
	protected void propertiesMethodTimeArg(Method currentMethod) throws KernelException
	{
		if (timedProperty) {
			// maximum time value is bounded by path length which we won't exceed
			propertyMethodVarTime = new CLVariable(new StdVariableType(0, generator.getRuntimeConfig().maxPathLength), "time");
			currentMethod.addArg(propertyMethodVarTime);
		}
	}

	@Override
	protected void propertiesMethodAddBoundedUntil(Method currentMethod, ComplexKernelComponent parent, SamplerBoolean property, CLVariable propertyVar)
			throws PrismLangException
	{
		//TODO: check if it will always work (e.g. CTMC case)
		SamplerBoundedUntilDisc prop = (SamplerBoundedUntilDisc) property;

		String propertyStringRight = visitPropertyExpression(prop.getRightSide()).toString();
		String propertyStringLeft = visitPropertyExpression(prop.getLeftSide()).toString();
		/**
		 * if(time > upper_bound)
		 */
		IfElse ifElse = new IfElse(createBinaryExpression(propertyMethodVarTime.getSource(),
				Operator.GE, fromString(prop.getUpperBound())));

		/**
		 * if(right_side == true) -> true
		 * else -> false
		 */
		IfElse rhsCheck = null;

		//TODO: always !prop?
		if (prop.getRightSide().toString().charAt(0) == '!') {
			rhsCheck = createPropertyCondition(propertyVar, true, propertyStringRight.substring(1),
					true, generator.getSVPtrTranslations());
		} else {
			rhsCheck = createPropertyCondition(propertyVar, false, propertyStringRight,
					true, generator.getSVPtrTranslations());
		}
		createPropertyCondition(rhsCheck, propertyVar, false, null,
				false, generator.getSVPtrTranslations());
		ifElse.addExpression(rhsCheck);
		/**
		 * Else -> check RHS and LHS
		 */
		ifElse.addElse();
		/**
		 * if(right_side == true) -> true
		 * else if(left_side == false) -> false
		 */
		IfElse betweenBounds = null;
		//TODO: same as above
		if (prop.getRightSide().toString().charAt(0) == '!') {
			betweenBounds = createPropertyCondition(propertyVar, true, propertyStringRight.substring(1),
					true, generator.getSVPtrTranslations());
		} else {
			betweenBounds = createPropertyCondition(propertyVar, false, propertyStringRight.toString(),
					true, generator.getSVPtrTranslations());
		}
		if (!(prop.getLeftSide() instanceof ExpressionLiteral)) {
			createPropertyCondition(betweenBounds, propertyVar, true, propertyStringLeft,
					false, generator.getSVPtrTranslations());
		}
		ifElse.addExpression(1, betweenBounds);
		parent.addExpression(ifElse);
	}
	
}
