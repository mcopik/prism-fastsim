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

import static simulator.opencl.kernel.expression.ExpressionGenerator.convertPrismAction;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createAssignment;
import static simulator.opencl.kernel.expression.ExpressionGenerator.createBinaryExpression;
import static simulator.opencl.kernel.expression.ExpressionGenerator.fromString;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import prism.PrismLangException;
import simulator.opencl.automaton.AbstractAutomaton.AutomatonType;
import simulator.opencl.automaton.update.Action;
import simulator.opencl.kernel.KernelGenerator.LocalVar;
import simulator.opencl.kernel.expression.ComplexKernelComponent;
import simulator.opencl.kernel.expression.Expression;
import simulator.opencl.kernel.expression.IfElse;
import simulator.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.opencl.kernel.expression.KernelComponent;
import simulator.opencl.kernel.expression.Method;
import simulator.opencl.kernel.memory.CLVariable;
import simulator.opencl.kernel.memory.StdVariableType;
import simulator.opencl.kernel.memory.StdVariableType.StdType;
import simulator.opencl.kernel.memory.VariableTypeInterface;

/**
 * Generate code responsible for checking if there was only one update and
 * the state vector has not changed. Because PRISM allows only for deterministic updates,
 * we can draw a conclusion here that in next step again only the same update will be active
 * and we have a loop.
 * 
 * It is important to note that it is a deterministic loop only for model with discrete time space.
 * For models such as CTMC, the time distribution is not deterministic and it may be necessary for
 * some properties (for now only cumulative reward).
 * TODO: move this description to reward generator
 *  to continue path generation because we DON'T
 * know how many updates are going to be executed which means that we can't estimate the final
 * value of cumulative reward
 * 
 * Code changes include:
 * - modify update functions to check for a change
 * - modify update calls to return a flag
 * - stopping path generation when a loop has been detected
 * 
 * Update functions are split into three sets:
 * - non synchronized update known as "update"
 * - synchronized update performing update over all modules for a given label known as "synUpdate"
 * - synchronized update performing just update for a given a label known as "synLabelUpdate"
 *
 */
public class LoopDetector
{
	
	/**
	 * True iff loop detection can be applied.
	 */
	protected boolean canDetectLoop = false;
	
	/**
	 * True iff sampling can be stopped when a loop is detected.
	 * Some reward properties may require looping until bound is reached.
	 */
	protected boolean canExitOnLoop = false;
	
	/**
	 * Boolean flag marking a detected loop in main kernel function.
	 */
	protected CLVariable varLoopDetection = null;
	
	public enum FunctionVar
	{
		/**
		 * Have all values changed?
		 */
		CHANGE_FLAG,
		/**
		 * Previous value
		 */
		OLD_VALUE
	}

	protected final static EnumMap<FunctionVar, String> FUNCTION_VARIABLES_NAMES;
	static {
		EnumMap<FunctionVar, String> names = new EnumMap<>(FunctionVar.class);
		names.put(FunctionVar.CHANGE_FLAG, "changeFlag");
		names.put(FunctionVar.OLD_VALUE, "oldValue");
		FUNCTION_VARIABLES_NAMES = names;
	}
	
	/**
	 * Store local variables for synchronized update.
	 * Note: use the same variable in two functions: nonsyn and synLabel.
	 * They are literally the same.
	 */
	protected final static EnumMap<FunctionVar, CLVariable> UPDATE_FUNCTIONS_VAR;

	static {
		EnumMap<FunctionVar, CLVariable> updateFunctionsVar = new EnumMap<>(FunctionVar.class);
		
		CLVariable changeFlag = new CLVariable(new StdVariableType(StdType.BOOL),
				FUNCTION_VARIABLES_NAMES.get(FunctionVar.CHANGE_FLAG));
		changeFlag.setInitValue(StdVariableType.initialize(1));
		updateFunctionsVar.put(FunctionVar.CHANGE_FLAG, changeFlag);
		
		//oldValue - used for loop detection
		CLVariable oldValue = new CLVariable(new StdVariableType(StdType.INT32),
				FUNCTION_VARIABLES_NAMES.get(FunctionVar.OLD_VALUE));
		oldValue.setInitValue(StdVariableType.initialize(0));
		updateFunctionsVar.put(FunctionVar.OLD_VALUE, oldValue);

		UPDATE_FUNCTIONS_VAR = updateFunctionsVar;
	}
	

	/**
	 * Other generators.
	 */
	protected KernelGenerator generator = null;
	protected ProbPropertyGenerator propertyGenerator = null;
	protected RewardGenerator rewardGenerator = null;
	
	public LoopDetector(KernelGenerator generator, ProbPropertyGenerator propertyGenerator,
			RewardGenerator rewardGenerator)
	{
		this.generator = generator;
		this.propertyGenerator = propertyGenerator;
		this.rewardGenerator = rewardGenerator;

		canDetectLoop = generator.getRuntimeConfig().loopDetectionEnabled &&
				propertyGenerator.canDetectLoop() && rewardGenerator.canDetectLoop();
		canExitOnLoop = canDetectLoop &&
				propertyGenerator.canExitOnLoop() && rewardGenerator.canExitOnLoop();
		
		if(canDetectLoop) {
			/**
			 * For CTMC we need to manually define a counter for all transitions.
			 * Request from parent generator to use this counter.
			 */
			if(generator.getModel().getType() == AutomatonType.CTMC) {
				generator.kernelCreateTransitionCounter();
			}
		}
	}
	
	public Collection<CLVariable> getLocalVars()
	{
		if(canDetectLoop) {
			List<CLVariable> vars = new ArrayList<>();
			varLoopDetection = new CLVariable(new StdVariableType(StdType.BOOL), "loopDetection");
			varLoopDetection.setInitValue(StdVariableType.initialize(0));
			vars.add(varLoopDetection);
			
			if(generator.getModel().getType() == AutomatonType.CTMC) {
				vars.add( generator.kernelGetLocalVar(LocalVar.TRANSITIONS_COUNTER) );
			}
			
			return vars;
		}
		return Collections.emptyList();
	}
	
	/**
	 * Looping: varLoopDetection = update_call();
	 * Non-looping: update_call();
	 * @return
	 */
	public Expression kernelCallUpdate(Expression call)
	{
		return canDetectLoop ? createAssignment(varLoopDetection, call) : call;
	}

	/**
	 * @return boolean expression: loop detected?
	 */
	public Expression kernelLoopExpression()
	{
		if(canDetectLoop) {
			return varLoopDetection.getSource();
		} else {
			return fromString("false");
		}
	}
	
	/**
	 * Create conditional which stops computation when there was no change in values and there was only on update.
	 * This code should be injected as a last expression in the main loop.
	 * @param parent
	 * @throws KernelException 
	 * @throws PrismLangException 
	 */
	public void kernelLoopDetection(ComplexKernelComponent parent) throws PrismLangException, KernelException
	{
		if (canDetectLoop) {
			// no change?
			Expression updateFlag = createBinaryExpression(varLoopDetection.getSource(), Operator.EQ, fromString("true"));

			// update size == 1
			Expression updateSize = createBinaryExpression(generator.kernelActiveUpdates(), Operator.EQ, fromString("1"));
			IfElse loop = new IfElse(createBinaryExpression(updateFlag, Operator.LAND, updateSize));
			loop.setConditionNumber(0);
			
			/*loop.addExpression( rewardGenerator.kernelUpdateProperties(
					generator.kernelGetLocalVar(LocalVar.STATE_VECTOR),
					generator.kernelGetLocalVar(LocalVar.TIME))
					);*/
			
			loop.addExpression( propertyGenerator.kernelHandleLoop() );
			loop.addExpression( rewardGenerator.kernelHandleLoop() );
			
			loop.addExpression(new Expression("break;\n"));

			parent.addExpression(loop);
		}
	}
	
	/**
	 * NON-SYN UPDATE
	 */

	/**
	 * @return boolean for change flag, nothing(void) when loop detection disabled
	 */
	public VariableTypeInterface updateFunctionReturnType()
	{
		return new StdVariableType(canDetectLoop ? StdType.BOOL : StdType.VOID);
	}
	
	/**
	 * @return var to keep previous value and flag to check if update has happened
	 */
	public Collection<CLVariable> updateFunctionLocalVars()
	{
		if(canDetectLoop) {
			return UPDATE_FUNCTIONS_VAR.values();
		}
		return Collections.emptyList();
	}
	
	/**
	 * Either call ExpressionGenerator to convert PRISM action directly or generate
	 * more complex expression with comparing new value to older value and saving result.
	 * 
	 * However, perform this only if there is one action. Looping is not possible when more
	 * than one action can be taken. For such cases, looping is explicitly disabled
	 * by writing 'changeFlag = false' before any action is taken. Hence we don't need to save values.
	 * @param sv
	 * @param action
	 * @param svPtrTranslations
	 * @param savedVariables
	 * @return
	 */
	public KernelComponent updateFunctionConvertAction(CLVariable sv, Action action,
			int actionsCount, StateVector.Translations svPtrTranslations,
			SavedVariables.Translations savedVariables)
	{
		if (canDetectLoop && actionsCount == 1) {
			return convertPrismAction(sv, action, svPtrTranslations, savedVariables,
					UPDATE_FUNCTIONS_VAR.get(FunctionVar.CHANGE_FLAG),
					UPDATE_FUNCTIONS_VAR.get(FunctionVar.OLD_VALUE));
		} else {
			return convertPrismAction(sv, action, svPtrTranslations, savedVariables);
		}
	}
	
	/**
	 * If loop detection active, add to given function a return statement with flag for
	 * loop detection.
	 * @param function
	 */
	public void updateFunctionReturn(Method function)
	{
		// return change flag, indicating if the performed update changed the state vector
		if (canDetectLoop) {
			function.addReturn( UPDATE_FUNCTIONS_VAR.get(FunctionVar.CHANGE_FLAG) );
		}
	}

	/**
	 * @return changeFlag = false, because there are multiple actions
	 */
	public KernelComponent updateFunctionMultipleActions()
	{
		if(canDetectLoop) {
			return createAssignment(UPDATE_FUNCTIONS_VAR.get(FunctionVar.CHANGE_FLAG), fromString(false));
		}
		return new Expression();
	}

	/**
	 * SYN UPDATE
	 */
	
	/**
	 * @return same as @see updateFunctionReturnType
	 */
	public VariableTypeInterface synUpdateFunctionReturnType()
	{
		return updateFunctionReturnType();
	}
	
	/**
	 * @return only a flag to check if update in one of label functions has happened
	 */
	public Collection<CLVariable> synUpdateFunctionLocalVars()
	{
		if(canDetectLoop) {
			return Collections.singletonList(
					UPDATE_FUNCTIONS_VAR.get(FunctionVar.CHANGE_FLAG)
					);
		}
		return Collections.emptyList();
	}
	
	/**
	 * Looping: varLoopDetection &= update_call();
	 * Non-looping: update_call();
	 * @return
	 */
	public Expression synUpdateCallUpdate(Expression call)
	{
		return canDetectLoop
				? createBinaryExpression(UPDATE_FUNCTIONS_VAR.get(FunctionVar.CHANGE_FLAG).getSource(),
						Operator.LAND_AUGM,
						call)
				: call;
	}
	
	/**
	 * Same as @see updateFunctionReturn
	 * @param function
	 */
	public void synUpdateFunctionReturn(Method function)
	{
		updateFunctionReturn(function);
	}
	
	/**
	 * SYN UPDATE: LABEL METHODS
	 */

	/**
	 * @return same as @see updateFunctionReturnType
	 */
	public VariableTypeInterface synLabelUpdateFunctionReturnType()
	{
		return updateFunctionReturnType();
	}
	
	/**
	 * @return flag to check if all updates have not changed anything
	 * and an integer to keep previous value
	 */
	public Collection<CLVariable> synLabelUpdateFunctionLocalVars()
	{
		if(canDetectLoop) {
			return UPDATE_FUNCTIONS_VAR.values();
		}
		return Collections.emptyList();
	}
	
	/**
	 * Same as @see updateFunctionConvertAction
	 * @param sv
	 * @param action
	 * @param svPtrTranslations
	 * @param savedVariables
	 * @return
	 */
	public KernelComponent synLabelUpdateFunctionConvertAction(CLVariable sv, Action action,
			int actionsCount, StateVector.Translations svPtrTranslations,
			SavedVariables.Translations savedVariables)
	{
		return updateFunctionConvertAction(sv, action, actionsCount, svPtrTranslations, savedVariables);
	}	
	
	/**
	 * Same as @see updateFunctionReturn
	 * @param function
	 */
	public void synLabelUpdateFunctionReturn(Method function)
	{
		updateFunctionReturn(function);
	}
	
	/**
	 * same as @see updateFunctionMultipleActions
	 * @return
	 */
	public KernelComponent synLabelUpdateFunctionMultipleActions()
	{
		return updateFunctionMultipleActions();
	}
}
