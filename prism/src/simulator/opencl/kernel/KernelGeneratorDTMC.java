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
import static simulator.opencl.kernel.expression.ExpressionGenerator.createAssignment;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createBinaryExpression;
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;
import static simulator.opencl.kernel.expression.ExpressionGenerator.functionCall;
import static simulator.opencl.kernel.expression.ExpressionGenerator.postIncrement;

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
import simulator.opencl.kernel.expression.ComplexKernelComponent;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.ExpressionGenerator;
import simulator.opencl.kernel.expression.KernelComponent;
import simulator.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.opencl.kernel.expression.ForLoop;
import simulator.opencl.kernel.expression.IfElse;
import simulator.opencl.kernel.expression.Method;
import simulator.opencl.kernel.memory.ArrayType;
import simulator.opencl.kernel.memory.CLValue;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.RValue;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.opencl.kernel.memory.StdVariableType.StdType;
import simulator.opencl.kernel.memory.StructureType;
import simulator.opencl.kernel.memory.VariableTypeInterface;
import simulator.sampler.SamplerBoolean;
import simulator.sampler.SamplerDouble;

public class KernelGeneratorDTMC extends KernelGenerator
{
	/**
	 * Constructor for DTMC kernel generator.
	 * @param model
	 * @param properties
	 * @param rewardProperties
	 * @param config
	 * @throws PrismLangException 
	 */
	public KernelGeneratorDTMC(AbstractAutomaton model, List<SamplerBoolean> properties, List<SamplerDouble> rewardProperties, RuntimeConfig config)
			throws KernelException, PrismLangException
	{
		super(model, properties, rewardProperties, config);
	}

	@Override
	protected VariableTypeInterface timeVariableType()
	{
		return new StdVariableType(0, config.maxPathLength);
	}

	/*********************************
	 * MAIN METHOD
	 ********************************/

	@Override
	public void mainMethodDefineLocalVars(Method currentMethod) throws KernelException
	{
		//time
		CLVariable varTime = new CLVariable(timeVariableType(), "time");
		varTime.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(varTime);
		localVars.put(LocalVar.TIME, varTime);
	}
	
	@Override
	protected void mainMethodUpdateTimeBefore(Method currentMethod, ComplexKernelComponent parent)
	{
		//don't need to do anything!
	}

	@Override
	protected void mainMethodUpdateTimeAfter(Method currentMethod, ComplexKernelComponent parent)
	{
		parent.addExpression(addComma(postIncrement(currentMethod.getLocalVar("time"))));
	}

	@Override
	protected int mainMethodRandomsPerIteration()
	{
		return 1;
	}

	@Override
	protected void mainMethodCallNonsynUpdateImpl(ComplexKernelComponent parent, CLValue... args) throws KernelException
	{
		if (args.length == 0) {
			Expression rndNumber = new Expression(String.format("%s%%%d",
					kernelGetLocalVar(LocalVar.PATH_LENGTH).getSource().toString(),
					config.prngType.numbersPerRandomize()
					));
			CLValue random = config.prngType.getRandomUnifFloat(rndNumber);
			parent.addExpression(
					cmdGenerator.kernelCallUpdate(random, kernelGetLocalVar(LocalVar.UNSYNCHRONIZED_SIZE))
					);
		} else if (args.length == 2) {
			parent.addExpression(
					cmdGenerator.kernelCallUpdate(args[0], args[1])
					);
		} else {
			throw new RuntimeException("Illegal number of parameters for mainMethodCallNonsynUpdateImpl @ KernelGeneratorDTMC, required 0 or 2!");
		}
	}

	@Override
	protected CLVariable mainMethodBothUpdatesSumVar()
	{
		return new CLVariable( synCmdGenerator.kernelUpdateSizeType(), "synSum");
	}

	@Override
	protected IfElse mainMethodBothUpdatesCondition(CLVariable selection) throws KernelException
	{
		CLVariable varSelectionSize = kernelGetLocalVar(LocalVar.UNSYNCHRONIZED_SIZE);
		CLVariable varSynSelectionSize = kernelGetLocalVar(LocalVar.SYNCHRONIZED_SIZE);
		
		Expression sum = createBinaryExpression(varSelectionSize.getSource(), Operator.ADD, varSynSelectionSize.getSource());
		addParentheses(sum);
		Expression condition = createBinaryExpression(selection.getSource(), Operator.LT,
		//nonSyn/(syn+nonSyn)
				createBinaryExpression(varSelectionSize.cast("float"), Operator.DIV, sum));
		IfElse ifElse = new IfElse(condition);
		/**
		 * if(selection < selectionSize/sum)
		 * callNonsynUpdate(..)
		 */
		mainMethodCallNonsynUpdate(ifElse, selection, new RValue(sum));
		return ifElse;
	}

	@Override
	protected CLVariable mainMethodSelectionVar(Expression selectionSize)
	{
		CLVariable selection = new CLVariable(new StdVariableType(StdType.FLOAT), "selection");
		Expression rndNumber = new Expression(String.format("%s%%%d",
				kernelGetLocalVar(LocalVar.PATH_LENGTH).getSource().toString(),
				//pathLength%2 for Random123
				config.prngType.numbersPerRandomize()
				));
		selection.setInitValue(config.prngType.getRandomUnifFloat(fromString(rndNumber)));
		return selection;
	}

}
