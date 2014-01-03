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
package simulator.gpu.opencl.kernel;

import simulator.gpu.automaton.AbstractAutomaton;
import simulator.gpu.automaton.command.Command;
import simulator.gpu.automaton.command.CommandInterface;
import simulator.gpu.automaton.command.SynchronizedCommand;
import simulator.gpu.automaton.update.Rate;
import simulator.gpu.automaton.update.Update;
import simulator.gpu.opencl.kernel.expression.Expression;
import simulator.gpu.opencl.kernel.expression.ExpressionGenerator;
import simulator.gpu.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.gpu.opencl.kernel.expression.IfElse;
import simulator.gpu.opencl.kernel.expression.Method;
import simulator.gpu.opencl.kernel.expression.Switch;
import simulator.gpu.opencl.kernel.memory.CLVariable;
import simulator.gpu.opencl.kernel.memory.PointerType;
import simulator.gpu.opencl.kernel.memory.StdVariableType;
import simulator.gpu.opencl.kernel.memory.StdVariableType.StdType;

public abstract class KernelGenerator
{
	protected Command commands[] = null;
	protected SynchronizedCommand synCommands[] = null;
	protected CLVariable stateVector = null;
	protected Method currentMethod = null;

	public KernelGenerator(AbstractAutomaton model, CLVariable stateVector)
	{
		this.stateVector = stateVector;
		int synSize = model.synchCmdsNumber();
		int size = model.commandsNumber();
		if (synSize != 0) {
			synCommands = new SynchronizedCommand[synSize];
		}
		commands = new Command[size - synSize];
		int normalCounter = 0, synCounter = 0;
		for (int i = 0; i < size; ++i) {
			CommandInterface cmd = model.getCommand(i);
			if (!cmd.isSynchronized()) {
				commands[normalCounter++] = (Command) cmd;
			} else {
				synCommands[synCounter++] = (SynchronizedCommand) cmd;
			}
		}
	}

	public Method createNonsynGuardsMethod() throws KernelException
	{
		if (commands == null) {
			return null;
		}
		guardsMethodCreateSignature();
		//StateVector * sv
		CLVariable sv = new CLVariable(stateVector.getPointer(), "sv");
		currentMethod.addArg(sv);
		currentMethod.registerStateVector(sv);
		//bool * guardsTab
		CLVariable guards = new CLVariable(new PointerType(new StdVariableType(0, commands.length)), "guardsTab");
		currentMethod.addArg(guards);
		//counter
		CLVariable counter = new CLVariable(new StdVariableType(0, commands.length), "counter");
		counter.setInitValue(StdVariableType.initialize(0));
		currentMethod.addLocalVar(counter);
		guardsMethodCreateLocalVars();
		for (int i = 0; i < commands.length; ++i) {
			guardsMethodCreateCondition(i, commands[i].getGuard().toString().replace("=", "=="));
		}
		//signature last guard
		CLVariable position = guards.varType.accessElement(guards.varName, new Expression(counter.varName));
		IfElse ifElse = new IfElse(ExpressionGenerator.createBasicExpression(counter, Operator.NE, Integer.toString(commands.length)));
		ifElse.addCommand(0, ExpressionGenerator.createAssignment(position, Integer.toString(commands.length)));
		currentMethod.addExpression(ifElse);
		guardsMethodReturnValue();
		return currentMethod;
	}

	protected abstract void guardsMethodCreateSignature();

	protected abstract void guardsMethodCreateLocalVars() throws KernelException;

	protected abstract void guardsMethodCreateCondition(int position, String guard);

	protected abstract void guardsMethodReturnValue();

	public Method createNonsynUpdate() throws KernelException
	{
		if (commands == null) {
			return null;
		}
		currentMethod = new Method("updateNonsynGuards", new StdVariableType(StdType.VOID));
		//StateVector * sv
		CLVariable sv = new CLVariable(stateVector.getPointer(), "sv");
		currentMethod.addArg(sv);
		currentMethod.registerStateVector(sv);
		//float sum
		CLVariable selectionSum = new CLVariable(new StdVariableType(StdType.FLOAT), "selectionSum");
		selectionSum.setInitValue(StdVariableType.initialize(0.0f));
		currentMethod.addArg(selectionSum);
		CLVariable selection = new CLVariable(new StdVariableType(0, commands.length), "selection");
		currentMethod.addLocalVar(selection);
		updateMethodAdditionalArgs();
		updateMethodLocalVars();
		updateMethodPerformSelection();
		Switch _switch = new Switch(new Expression(selection.varName));
		for (int i = 0; i < commands.length; ++i) {
			_switch.addCase(new Expression(Integer.toString(i)));
			Update update = commands[i].getUpdate();
			Rate rate = update.getRate(0);
			if (update.getActionsNumber() > 1) {
				IfElse ifElse = new IfElse(ExpressionGenerator.createBasicExpression(selectionSum, Operator.LT, rate.toString()));
				ifElse.addCommand(0, new Expression(String.format("%s;", update.getAction(0).toString())));
				for (int j = 1; j < update.getActionsNumber(); ++j) {
					rate.addRate(update.getRate(j));
					ifElse.addElif(ExpressionGenerator.createBasicExpression(selectionSum, Operator.LT, rate.toString()));
					ifElse.addCommand(j, new Expression(String.format("%s;", update.getAction(0).toString())));
				}
				_switch.addCommand(i, ifElse);
			} else {
				_switch.addCommand(i, new Expression(String.format("%s;", update.getAction(0).toString())));
			}
		}
		currentMethod.addExpression(_switch);
		return currentMethod;
	}

	protected abstract void updateMethodPerformSelection();

	protected abstract void updateMethodAdditionalArgs() throws KernelException;

	protected abstract void updateMethodLocalVars() throws KernelException;
}