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
package simulator.opencl.kernel;

import static simulator.opencl.kernel.expression.ExpressionGenerator.addComma;
import static simulator.opencl.kernel.expression.ExpressionGenerator.addParentheses;
import static simulator.opencl.kernel.expression.ExpressionGenerator.convertPrismGuard;
import static simulator.opencl.kernel.expression.ExpressionGenerator.convertPrismRate;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createAssignment;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createBinaryExpression;
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;
import static simulator.opencl.kernel.expression.ExpressionGenerator.preIncrement;
import static simulator.opencl.kernel.expression.ExpressionGenerator.postIncrement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import prism.Preconditions;
import prism.PrismLangException;
import simulator.opencl.RuntimeConfig;
import simulator.opencl.automaton.AbstractAutomaton;
import simulator.opencl.automaton.command.Command;
import simulator.opencl.automaton.command.SynchronizedCommand;
import simulator.opencl.automaton.update.Rate;
import simulator.opencl.kernel.KernelGenerator.LocalVar;
import simulator.opencl.kernel.expression.ComplexKernelComponent;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.ExpressionGenerator;
import simulator.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.opencl.kernel.expression.ForLoop;
import simulator.opencl.kernel.expression.IfElse;
import simulator.opencl.kernel.expression.KernelComponent;
import simulator.opencl.kernel.expression.Method;
import simulator.opencl.kernel.expression.Switch;
import simulator.opencl.kernel.memory.ArrayType;
import simulator.opencl.kernel.memory.CLValue;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.PointerType;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.opencl.kernel.memory.StdVariableType.StdType;
import simulator.opencl.kernel.memory.StructureType;
import simulator.opencl.kernel.memory.VariableTypeInterface;
import simulator.sampler.SamplerBoolean;
import simulator.sampler.SamplerDouble;

public class KernelGeneratorCTMC extends KernelGenerator
{
	
	protected CLVariable varSum = null;
	protected CLVariable varSumPtr = null;
	
	/**
	 * Constructor for CTMC kernel generator.
	 * @param model
	 * @param properties
	 * @param rewardProperties
	 * @param config
	 * @throws PrismLangException 
	 */
	public KernelGeneratorCTMC(AbstractAutomaton model, List<SamplerBoolean> properties, List<SamplerDouble> rewardProperties, RuntimeConfig config)
			throws KernelException, PrismLangException
	{
		super(model, properties, rewardProperties, config);
	}
	
	@Override
	protected VariableTypeInterface timeVariableType()
	{
		return new StdVariableType(StdType.FLOAT);
	}

	/*********************************
	 * MAIN METHOD
	 ********************************/
	@Override
	public void mainMethodDefineLocalVars(Method currentMethod) throws KernelException
	{
		//time
		CLVariable varTime = new CLVariable(varTimeType, "time");
		varTime.setInitValue(StdVariableType.initialize(0.0f));
		currentMethod.addLocalVar(varTime);
		localVars.put(LocalVar.TIME, varTime);
		
		//updated time - it's needed for time bounded property and CTMC state reward
		if (propertyGenerator.needsTimeDifference() || rewardGenerator.needsTimeDifference()) {
			CLVariable varUpdatedTime = new CLVariable(new StdVariableType(StdType.FLOAT), "updatedTime");
			varUpdatedTime.setInitValue(StdVariableType.initialize(0.0f));
			currentMethod.addLocalVar(varUpdatedTime);
			localVars.put(LocalVar.UPDATED_TIME, varUpdatedTime);
		}
	}
	
    @Override
	protected void mainMethodUpdateTimeBefore(Method currentMethod, ComplexKernelComponent parent)
	{
    	CLVariable varUpdatedTime = localVars.get(LocalVar.UPDATED_TIME);
		// time = updated_time;
		if (varUpdatedTime != null) {
	    	CLVariable varTime = localVars.get(LocalVar.TIME);
			CLValue random = config.prngType.getRandomUnifFloat(fromString(1));
			Expression substrRng = createBinaryExpression(fromString(1),
			//1 - random()
					Operator.SUB, random.getSource());
			substrRng = new Expression(String.format("log(%s)", substrRng.getSource()));
			// it may be a complex expression
			Expression sum = addParentheses(kernelActiveUpdates());
			substrRng = createBinaryExpression(substrRng, Operator.DIV, sum);
			// updated = time - new value
			// OR time -= new value
			substrRng = createBinaryExpression(varTime.getSource(), Operator.SUB, substrRng);
			parent.addExpression(createAssignment(varUpdatedTime, substrRng));
		}
	}

	@Override
	protected void mainMethodUpdateTimeAfter(Method currentMethod, ComplexKernelComponent parent)
	{
    	CLVariable varUpdatedTime = localVars.get(LocalVar.UPDATED_TIME);
    	CLVariable varTime = localVars.get(LocalVar.TIME);
		if (varUpdatedTime == null) {
			CLValue random = config.prngType.getRandomUnifFloat(fromString(1));
			Expression substrRng = createBinaryExpression(fromString(1),
			//1 - random()
					Operator.SUB, random.getSource());
			substrRng = new Expression(String.format("log(%s)", substrRng.getSource()));
			Expression sum = addParentheses(kernelActiveUpdates());
			substrRng = createBinaryExpression(substrRng, Operator.DIV, sum);
			// time -= new value
			parent.addExpression(addComma(createBinaryExpression(varTime.getSource(), Operator.SUB_AUGM, substrRng)));
		} else {
            // OR updated = time - new value
			parent.addExpression(createAssignment(varTime, varUpdatedTime));
		}
	}

	@Override
	protected int mainMethodRandomsPerIteration()
	{
		//1 for update selection, one for time generation
		return 2;
	}

	@Override
	protected void mainMethodCallNonsynUpdateImpl(ComplexKernelComponent parent, CLValue... args) throws KernelException
	{
		CLVariable varSelectionSize = kernelGetLocalVar(LocalVar.UNSYNCHRONIZED_SIZE);
		parent.addExpression(rewardGenerator.kernelBeforeUpdate(localVars.get(LocalVar.STATE_VECTOR)));
		CLValue random = null;
		if (args.length == 0) {
			random = config.prngType.getRandomFloat(fromString(0), varSelectionSize.getSource());
		} else {
			random = args[0];
		}
		parent.addExpression( cmdGenerator.kernelCallUpdate(random, null) );
	}

	@Override
	protected CLVariable mainMethodBothUpdatesSumVar()
	{
		return new CLVariable( synCmdGenerator.kernelUpdateSizeType(), "synSum");
	}

	@Override
	protected CLVariable mainMethodSelectionVar(Expression selectionSize)
	{
		CLVariable selection = new CLVariable(new StdVariableType(StdType.FLOAT), "selection");
		Expression rndNumber = null;
		if (config.prngType.numbersPerRandomize() == 2) {
			rndNumber = fromString(0);
		}
		//we assume that this is an even number!
		else {
			rndNumber = new Expression(String.format("%s%%%d",
			//pathLength*2
					addParentheses(createBinaryExpression(varPathLength.getSource(), Operator.MUL,
					// % numbersPerRandom
							fromString(2))).toString(), config.prngType.numbersPerRandomize()));
		}
		selection.setInitValue(config.prngType.getRandomFloat(fromString(rndNumber), selectionSize));
		return selection;
	}

	@Override
	protected IfElse mainMethodBothUpdatesCondition(CLVariable selection) throws KernelException
	{
		CLVariable varSelectionSize = kernelGetLocalVar(LocalVar.UNSYNCHRONIZED_SIZE);
		Expression condition = createBinaryExpression(selection.getSource(), Operator.LT,
		//random < selectionSize
				varSelectionSize.getSource());
		IfElse ifElse = new IfElse(condition);
		/**
		 * if(selection < selectionSize/sum)
		 * callNonsynUpdate(..)
		 */
		mainMethodCallNonsynUpdate(ifElse, selection);
		return ifElse;
	}

}
