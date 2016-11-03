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

import static simulator.opencl.kernel.expression.ExpressionGenerator.convertPrismProperty;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createAssignment;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createBinaryExpression;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createNegation;
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import parser.ast.ExpressionLiteral;
import prism.PrismLangException;
import simulator.opencl.automaton.AbstractAutomaton.AutomatonType;
import simulator.opencl.kernel.expression.ComplexKernelComponent;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.ExpressionGenerator;
import simulator.opencl.kernel.expression.IfElse;
import simulator.opencl.kernel.expression.KernelComponent;
import simulator.opencl.kernel.expression.Method;
import simulator.opencl.kernel.memory.ArrayType;
import simulator.opencl.kernel.memory.CLValue;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.PointerType;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.opencl.kernel.memory.StructureType;
import simulator.opencl.kernel.memory.CLVariable.Location;
import simulator.opencl.kernel.memory.StdVariableType.StdType;
import simulator.sampler.SamplerBoolean;
import simulator.sampler.SamplerNext;
import simulator.sampler.SamplerUntil;

public abstract class ProbPropertyGenerator implements KernelComponentGenerator
{
	/**
	 * Structure with two booleans: property value and information
	 * if property has been evaluated.
	 */
	public final static StructureType PROPERTY_STATE_STRUCTURE;
	static {
		StructureType type = new StructureType("PropertyState");
		type.addVariable(new CLVariable(new StdVariableType(StdType.BOOL), "propertyState"));
		type.addVariable(new CLVariable(new StdVariableType(StdType.BOOL), "valueKnown"));
		PROPERTY_STATE_STRUCTURE = type;
	}

	/**
	 * Local variable storing results of properties.
	 */
	protected CLVariable varPropertiesArray = null;
	
	/**
	 * Property function argument: time
	 */
	protected CLVariable propertyMethodVarTime = null;
	
	/**
	 * OpenCL kernel arg corresponding to global buffer storing results.
	 */
	protected List<CLVariable> propertyOutputResults = null;
	
	/**
	 * Collection of PCTL/CSL properties.
	 */
	protected List<SamplerBoolean> properties = null;
	
	/**
	 * True iff there are properties.
	 */
	protected boolean activeGenerator = false;
	
	/**
	 * True iff property verification method requires passing time,
	 * i.e. at least one property is a bounded until.
	 */
	protected boolean timedProperty = false;
	
	/**
	 * Function updating and verifying property.
	 * Return value determines is we can stop simulation(we know all values).
	 * DTMC:
	 * bool updateProperties(StateVector * sv, PropertyState * prop);
	 * OR
	 * bool updateProperties(StateVector * sv, PropertyState * prop, int time);
	 * 
	 * CTMC:
	 * bool updateProperties(StateVector * sv, PropertyState * prop);
	 * OR
	 * bool updateProperties(StateVector * sv, PropertyState * prop, float time, float updated_time);
	 */
	Method propertyUpdateMethod = null;
	
	/**
	 * Main generator.
	 */
	protected KernelGenerator generator = null;
	
	public ProbPropertyGenerator(KernelGenerator generator) throws PrismLangException, KernelException
	{
		this.generator = generator;
		properties = generator.getProbProperties();
		activeGenerator = properties.size() > 0;
		if(!activeGenerator) {
			return;
		}
		
		for(SamplerBoolean property : properties) {
			if ( isTimedProperty(property) ) {
				timedProperty = true;
				break;
			}
		}
		
		propertiesMethodCreate();
	}
	
	/**
	 * @return true iff generator is active and produces any code
	 */
	public boolean isGeneratorActive()
	{
		return activeGenerator;
	}
	
	/**
	 * Depending on type of property, we know:
	 * a) X - false for loop
	 * b) U unbounded/bounded - if right expression has not been evaluated as true so far,
	 * it can't be true in future - state doesn't change.
	 * @return always true
	 */
	public boolean canDetectLoop()
	{
		return true;
	}

	/**
	 * In a case of loop, we always have enough knowledge to stop sample generation.
	 * @see canDetectLoops() for details
	 * @return always true
	 */
	public boolean canExitOnLoop()
	{
		return true;
	}
	
	/**
	 * @return true for CTMC with bounded until. otherwise
	 * 	just current time is sufficient
	 */
	public abstract boolean needsTimeDifference();
	
	/**
	 * @param property
	 * @return true iff property value depends on time
	 */
	protected abstract boolean isTimedProperty(SamplerBoolean property);
	
	/**
	 * Factor method
	 * @param generator
	 * @param type
	 * @return
	 * @throws KernelException
	 * @throws PrismLangException
	 */
	public static ProbPropertyGenerator createGenerator(KernelGenerator generator, AutomatonType type)
			throws KernelException, PrismLangException
	{
		if (type == AutomatonType.DTMC) {
			return new ProbPropertyGeneratorDTMC(generator);
		} else {
			return new ProbPropertyGeneratorCTMC(generator);
		}
	}
	
	/**
	 * CODE GENERATION for main kernel function. 
	 */
	
	/**
	 * Additional definitions. Includes only structure type for property state.
	 * @return
	 */
	public Collection<KernelComponent> getDefinitions()
	{
		if(activeGenerator) {
			List<KernelComponent> definitions = new ArrayList<>();
			definitions.add(PROPERTY_STATE_STRUCTURE.getDefinition());
			return definitions;
		}
		return Collections.emptyList();
	}

	@Override
	public Collection<Method> getMethods()
	{
		if (activeGenerator) {
			return Collections.singletonList(propertyUpdateMethod);
		}
		return Collections.emptyList();
	}
	
	/**
	 * Arguments for OpenCL kernels.
	 * @return
	 */
	public Collection<CLVariable> getKernelArgs()
	{
		if(activeGenerator) {
			propertyOutputResults = new ArrayList<CLVariable>(properties.size());
			for (int i = 0; i < properties.size(); ++i) {
				CLVariable propertyResult = new CLVariable(new PointerType(new StdVariableType(StdType.UINT8)),
						//propertyNumber
						String.format("property%d", i));
				propertyResult.memLocation = Location.GLOBAL;
				propertyOutputResults.add(propertyResult);
			}
			return propertyOutputResults;
		}
		return Collections.emptyList();
	}

	/**
	 * Local variables in main kernel method.
	 * @return
	 */
	public Collection<CLVariable> getLocalVars()
	{
		if(activeGenerator) {
			ArrayType propertiesArrayType = null;
			propertiesArrayType = new ArrayType(PROPERTY_STATE_STRUCTURE, properties.size());
			varPropertiesArray = new CLVariable(propertiesArrayType, "properties");
			CLValue initValues[] = new CLValue[properties.size()];
			CLValue initValue = PROPERTY_STATE_STRUCTURE.initializeStdStructure(new Number[] { 0, 0 });
			for (int i = 0; i < initValues.length; ++i) {
				initValues[i] = initValue;
			}
			varPropertiesArray.setInitValue(propertiesArrayType.initializeArray(initValues));

			return Collections.singletonList(varPropertiesArray);
		}
		return Collections.emptyList();
	}
	
	/**
	 * First property check, before even entering the loop - necessary only for CTMC.
	 * For a CTMC, a bounded until require special attention if the lower bound is equal to zero.
	 * @param parent
	 */
	public abstract void kernelFirstUpdateProperties(ComplexKernelComponent parent);

	/**
	 * Create call to probabilistic property update method.
	 */
	public abstract Expression kernelUpdateProperties();
	
	/**
	 * Generate code for writing reward result into an OpenCL buffer.
	 * @param threadPosition
	 * @param mainMethod
	 * @param loopDetectionVariable
	 */
	public void kernelWriteOutput(Method mainMethod, Expression threadPosition)
	{
		if(activeGenerator) {
			// computation ended by a deadlock or loop detector
			Expression loopExpr = generator.kernelLoopExpression();
			Expression deadlockExpr = generator.kernelDeadlockExpression();
			Expression loopOrDeadlock = createBinaryExpression(
					loopExpr, ExpressionGenerator.Operator.LOR,
					deadlockExpr);
	
			for (int i = 0; i < properties.size(); ++i) {
				CLVariable result = propertyOutputResults.get(i).accessElement(threadPosition);
				CLVariable property = varPropertiesArray.accessElement(fromString(i)).accessField("propertyState");
				CLVariable valueKnown = varPropertiesArray.accessElement(fromString(i)).accessField("valueKnown");
				// if loop was detected, then by definition property is verified
				Expression assignment = ExpressionGenerator.createConditionalAssignment(
						createBinaryExpression(loopOrDeadlock, ExpressionGenerator.Operator.LOR, valueKnown.getSource()),
						property.getSource().toString(),
						"2");
	
				mainMethod.addExpression(createAssignment(result, assignment));
			}
		}
	}
	
	/**
	 * PROPERTY METHOD: creation and definition.
	 */

	/**
	 * @return helper method checking all properties and returning true when all of them are verified
	 * @throws KernelException
	 * @throws PrismLangException 
	 */
	protected void propertiesMethodCreate() throws KernelException, PrismLangException
	{
		propertyUpdateMethod = new Method("checkProperties", new StdVariableType(StdType.BOOL));

		/**
		 * Local variables and args.
		 */
		//StateVector * sv
		CLVariable sv = new CLVariable(new PointerType(generator.getSVType()), "sv");
		propertyUpdateMethod.addArg(sv);
		//PropertyState * property
		CLVariable propertyState = new CLVariable(new PointerType(PROPERTY_STATE_STRUCTURE), "propertyState");
		propertyUpdateMethod.addArg(propertyState);
		//Time variable - uint/float
		propertiesMethodTimeArg(propertyUpdateMethod);
		//uint counter
		CLVariable counter = new CLVariable(new StdVariableType(0, properties.size()), "counter");
		counter.setInitValue(StdVariableType.initialize(0));
		propertyUpdateMethod.addLocalVar(counter);
		//bool allKnown - will be returned 
		CLVariable allKnown = new CLVariable(new StdVariableType(StdType.BOOL), "allKnown");
		allKnown.setInitValue(StdVariableType.initialize(1));
		propertyUpdateMethod.addLocalVar(allKnown);

		/**
		 * For each property, add checking
		 */
		for (int i = 0; i < properties.size(); ++i) {
			SamplerBoolean property = properties.get(i);
			
			CLVariable currentProperty = propertyState.accessElement(counter.getSource());
			CLVariable valueKnown = currentProperty.accessField("valueKnown");

			IfElse ifElse = new IfElse(createNegation(valueKnown.getSource()));
			/**
			 * X state_formulae
			 * I don't think that this will be used in future.
			 */
			if (property instanceof SamplerNext) {
				propertiesMethodAddNext(ifElse, (SamplerNext) property, currentProperty);
			}
			/**
			 * state_formulae U state_formulae
			 */
			else if (property instanceof SamplerUntil) {
				propertiesMethodAddUntil(ifElse, (SamplerUntil) property, currentProperty);
			}
			/**
			 * state_formulae U[k1,k2] state_formulae
			 * Requires additional timing args.
			 */
			else {
				propertiesMethodAddBoundedUntil(propertyUpdateMethod, ifElse, property, currentProperty);
			}
			// allKnown &= property[i].valueKnown
			ifElse.addExpression(0, createBinaryExpression( allKnown.getSource(), 
					ExpressionGenerator.Operator.LAND_AUGM, valueKnown.getSource()));
			propertyUpdateMethod.addExpression(ifElse);
		}
		
		propertyUpdateMethod.addReturn(allKnown);
	}

	/**
	 * Time argument, used when one have to verify timed property.
	 * DTMC: only current time (integer)
	 * CTMC: two floats - time and updated time
	 * @param currentMethod
	 * @throws KernelException
	 */
	protected abstract void propertiesMethodTimeArg(Method currentMethod) throws KernelException;

	/**
	 * Handle the timed 'until' operator - different implementations for automata.
	 * CTMC requires an additional check for the situation, when current time is between lower
	 * and upper bound.
	 * @param currentMethod
	 * @param parent
	 * @param property
	 * @param propertyVar
	 * @throws PrismLangException
	 */
	protected abstract void propertiesMethodAddBoundedUntil(Method currentMethod, ComplexKernelComponent parent,
			SamplerBoolean property, CLVariable propertyVar) throws PrismLangException;

	/**
	 * @param prop
	 * @return translate property: add parentheses, cast to float in division etc
	 * @throws PrismLangException
	 */
	protected parser.ast.Expression visitPropertyExpression(parser.ast.Expression prop) throws PrismLangException
	{
		return (parser.ast.Expression) prop.accept( generator.getTreeVisitor() );
	}

	/**
	 * Handle the 'next' operator - same for both CTMC/DTMC
	 * @param parent
	 * @param property
	 * @param propertyVar
	 * @throws PrismLangException
	 */
	protected void propertiesMethodAddNext(ComplexKernelComponent parent, SamplerNext property, CLVariable propertyVar) throws PrismLangException
	{
		String propertyString = visitPropertyExpression(property.getExpression()).toString();
		IfElse ifElse = createPropertyCondition(propertyVar, false, propertyString, true, generator.getSVPtrTranslations());
		createPropertyCondition(ifElse, propertyVar, false, null, false, generator.getSVPtrTranslations());
		parent.addExpression(ifElse);
	}

	/**
	 * Handle the 'until' non-timed operator - same for both DTMC and CTMC. 
	 * @param parent
	 * @param property
	 * @param propertyVar
	 * @throws PrismLangException
	 */
	protected void propertiesMethodAddUntil(ComplexKernelComponent parent, SamplerUntil property, CLVariable propertyVar) throws PrismLangException
	{
		String propertyStringRight = visitPropertyExpression(property.getRightSide()).toString();
		String propertyStringLeft = visitPropertyExpression(property.getLeftSide()).toString();
		IfElse ifElse = createPropertyCondition(propertyVar, false, propertyStringRight, true, generator.getSVPtrTranslations());

		/**
		 * in F/G it is true, no need to check
		 */
		if (!(property.getLeftSide() instanceof ExpressionLiteral)) {
			createPropertyCondition(ifElse, propertyVar, true, propertyStringLeft, false, generator.getSVPtrTranslations());
		}
		parent.addExpression(ifElse);
	}

	/**
	 * Creates IfElse for property.
	 * @param propertyVar
	 * @param negation
	 * @param condition
	 * @param propertyValue
	 * @return property verification in conditional - write results to property structure
	 */
	protected IfElse createPropertyCondition(CLVariable propertyVar, boolean negation, String condition,
			boolean propertyValue, Map<String, String> svTranslations)
	{
		IfElse ifElse = null;
		if (!negation) {
			ifElse = new IfElse(convertPrismProperty(svTranslations, condition));
		} else {
			ifElse = new IfElse(createNegation(convertPrismProperty(svTranslations, condition)));
		}
		CLVariable valueKnown = propertyVar.accessField("valueKnown");
		CLVariable propertyState = propertyVar.accessField("propertyState");
		if (propertyValue) {
			ifElse.addExpression(0, createAssignment(propertyState, fromString("true")));
		} else {
			ifElse.addExpression(0, createAssignment(propertyState, fromString("false")));
		}
		ifElse.addExpression(0, createAssignment(valueKnown, fromString("true")));
		return ifElse;
	}

	/**
	 * Private helper method - update ifElse
	 * @param ifElse
	 * @param propertyVar
	 * @param negation
	 * @param condition
	 * @param propertyValue
	 */
	protected void createPropertyCondition(IfElse ifElse, CLVariable propertyVar, boolean negation, String condition,
			boolean propertyValue, Map<String, String> svTranslations)
	{
		if (condition != null) {
			if (!negation) {
				ifElse.addElif(convertPrismProperty(svTranslations, condition));
			} else {
				ifElse.addElif(createNegation(convertPrismProperty(svTranslations, condition)));
			}
		} else {
			ifElse.addElse();
		}
		CLVariable valueKnown = propertyVar.accessField("valueKnown");
		CLVariable propertyState = propertyVar.accessField("propertyState");
		if (propertyValue) {
			ifElse.addExpression(ifElse.size() - 1, createAssignment(propertyState, fromString("true")));
		} else {
			ifElse.addExpression(ifElse.size() - 1, createAssignment(propertyState, fromString("false")));
		}
		ifElse.addExpression(ifElse.size() - 1, createAssignment(valueKnown, fromString("true")));
	}
	
}
