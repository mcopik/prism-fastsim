//==============================================================================
//	
//	Copyright (c) 2013-
//	Authors:
//	* Dave Parker <david.parker@comlab.ox.ac.uk> (University of Oxford)
//	* Ernst Moritz Hahn <emhahn@cs.ox.ac.uk> (University of Oxford)
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

/*
 * TODO
 * - lumpers should start convert directly from ParamModel plus scheduler and rewards
 *   rather than from AlterablePMC as is done currently
 * - could print to log for which parameter values results will be valid
 * - could implement steady-state properties for models w.o.
 *   nondeterminism but not needed at least not for next paper
 * - could implement DAG-like functions + probabilistic equality
 *   - for each function num and den would each be pointers into DAG
 *   - then either exact equality (expensive)
 *   - or probabilistic equality (Schwartz-Zippel)
 * - also, DAG-like regexp representation possible
 * - for comparism with previous work, use 
 * - could implement other types of regions apart from boxes
 * - could later improve support for optimisation over parameters
 *   - using apache math commons
 *   - or ipopt (zip file with java support for linux, windows, mac os x exists)
 * - libraries used should be loaded by classloader to make easier to use in
 *   projects where we cannot use GPLed code (just delete library and that's it)
 * - could later add support for abstraction of functions
 * - could integrate in GUI (student project?)
 * - if time left, add JUnit tests at least for BigRational and maybe functions and regions
 *   basically for all classes where interface is more or less fixed
 * - could try to bind to Ginac for comparability, but probably not much difference
 * - should integrate binding to solvers (RAHD and the like) at some point
 */

package param;

import java.io.FileOutputStream;
import java.util.*;

import edu.jas.kern.ComputerThreads;
import explicit.Model;

import param.Lumper.BisimType;
import param.StateEliminator.EliminationOrder;
import parser.State;
import parser.Values;
import parser.ast.*;
import parser.ast.ExpressionFilter.FilterOperator;
import parser.type.*;
import prism.ModelType;
import prism.PrismException;
import prism.PrismLog;
import prism.PrismPrintStreamLog;
import prism.PrismSettings;
import prism.Result;

/**
 * Model checker for parametric Markov models.
 * 
 * @author Ernst Moritz Hahn <emhahn@cs.ox.ac.uk> (University of Oxford)
 */
final public class ParamModelChecker
{
	// Log for output (default to System.out)
	private PrismLog mainLog = new PrismPrintStreamLog(System.out);

	// Model file (for reward structures, etc.)
	private ModulesFile modulesFile = null;

	// Properties file (for labels, constants, etc.)
	private PropertiesFile propertiesFile = null;

	// Constants (extracted from model/properties)
	private Values constantValues;

	// The result of model checking will be stored here
	private Result result;
	
	// Flags/settings

	// Verbosity level
	private int verbosity = 0;
	
	private BigRational[] paramLower;
	private BigRational[] paramUpper;

	private FunctionFactory functionFactory;
	private RegionFactory regionFactory;
	private ConstraintChecker constraintChecker;
	private ValueComputer valueComputer;
	
	private BigRational precision;
	private int splitMethod;
	private EliminationOrder eliminationOrder;
	private int numRandomPoints;
	private Lumper.BisimType bisimType;
	private boolean simplifyRegions;

	private ModelBuilder modelBuilder;
	
	// Setters/getters

	/**
	 * Set log for output.
	 */
	public void setLog(PrismLog log)
	{
		this.mainLog = log;
	}

	/**
	 * Get log for output.
	 */
	public PrismLog getLog()
	{
		return mainLog;
	}

	/**
	 * Set the attached model file (for e.g. reward structures when model checking)
	 * and the attached properties file (for e.g. constants/labels when model checking)
	 */
	public void setModulesFileAndPropertiesFile(ModulesFile modulesFile, PropertiesFile propertiesFile)
	{
		this.modulesFile = modulesFile;
		this.propertiesFile = propertiesFile;
		// Get combined constant values from model/properties
		constantValues = new Values();
		constantValues.addValues(modulesFile.getConstantValues());
		if (propertiesFile != null)
			constantValues.addValues(propertiesFile.getConstantValues());
	}

	// Settings methods
	
	/**
	 * Set settings from a PRISMSettings object.
	 * @throws PrismException 
	 */
	public void setSettings(PrismSettings settings) throws PrismException
	{
		verbosity = settings.getBoolean(PrismSettings.PRISM_VERBOSE) ? 10 : 1;
		precision = new BigRational(settings.getString(PrismSettings.PRISM_PARAM_PRECISION));
		String splitMethodString = settings.getString(PrismSettings.PRISM_PARAM_SPLIT);
		if (splitMethodString.equals("longest")) {
			splitMethod = BoxRegion.SPLIT_LONGEST;
		} else if (splitMethodString.equals("all")) {
			splitMethod = BoxRegion.SPLIT_ALL;
		} else {
			throw new PrismException("unknown region splitting method " + splitMethodString);				
		}
		String eliminationOrderString = settings.getString(PrismSettings.PRISM_PARAM_ELIM_ORDER);
		if (eliminationOrderString.equals("arbitrary")) {
			eliminationOrder = EliminationOrder.ARBITRARY;
		} else if (eliminationOrderString.equals("forward")) {
			eliminationOrder = EliminationOrder.FORWARD;
		} else if (eliminationOrderString.equals("forward-reversed")) {
			eliminationOrder = EliminationOrder.FORWARD_REVERSED;
		} else if (eliminationOrderString.equals("backward")) {
			eliminationOrder = EliminationOrder.BACKWARD;
		} else if (eliminationOrderString.equals("backward-reversed")) {
			eliminationOrder = EliminationOrder.BACKWARD_REVERSED;
		} else if (eliminationOrderString.equals("random")) {
			eliminationOrder = EliminationOrder.RANDOM;
		} else {
			throw new PrismException("unknown state elimination order " + eliminationOrderString);				
		}
		numRandomPoints = settings.getInteger(PrismSettings.PRISM_PARAM_RANDOM_POINTS);
		String bisimTypeString = settings.getString(PrismSettings.PRISM_PARAM_BISIM);
		if (bisimTypeString.equals("weak")) {
			bisimType = BisimType.WEAK;
		} else if (bisimTypeString.equals("strong")) {
			bisimType = BisimType.STRONG;
		} else if (bisimTypeString.equals("none")) {
			bisimType = BisimType.NULL;
		} else {
			throw new PrismException("unknown bisimulation type " + bisimTypeString);							
		}
		simplifyRegions = settings.getBoolean(PrismSettings.PRISM_PARAM_SUBSUME_REGIONS);
	}

	/**
	 * Inherit settings (and other info) from another model checker object.
	 */
	public void inheritSettings(ParamModelChecker other)
	{
		setLog(other.getLog());
		setVerbosity(other.getVerbosity());
	}

	/**
	 * Print summary of current settings.
	 */
	public void printSettings()
	{
		mainLog.print("verbosity = " + verbosity + " ");
	}

	// Set methods for flags/settings

	/**
	 * Set verbosity level, i.e. amount of output produced.
	 */
	public void setVerbosity(int verbosity)
	{
		this.verbosity = verbosity;
	}

	// Get methods for flags/settings

	public int getVerbosity()
	{
		return verbosity;
	}

	// Model checking functions

	/**
	 * Model check an expression, process and return the result.
	 * Information about states and model constants should be attached to the model.
	 * For other required info (labels, reward structures, etc.), use the methods
	 * {@link #setModulesFile} and {@link #setPropertiesFile}
	 * to attach the original model/properties files.
	 */
	public Result check(Model model, Expression expr) throws PrismException
	{
		ParamModel paramModel = (ParamModel) model;
		functionFactory = paramModel.getFunctionFactory();
		constraintChecker = new ConstraintChecker(numRandomPoints);
		regionFactory = new BoxRegionFactory(functionFactory, constraintChecker, precision,
				model.getNumStates(), model.getFirstInitialState(), simplifyRegions, splitMethod);
		valueComputer = new ValueComputer(paramModel, regionFactory, precision, eliminationOrder, bisimType);
		
		ExpressionFilter exprFilter = null;
		long timer = 0;
		
		// Remove labels from property, using combined label list (on a copy of the expression)
		// This is done now so that we can handle labels nested below operators that are not
		// handled natively by the model checker yet (just evaluate()ed in a loop).
		expr = (Expression) expr.deepCopy().expandLabels(propertiesFile.getCombinedLabelList());

		// Also evaluate/replace any constants
		//expr = (Expression) expr.replaceConstants(constantValues);

		// The final result of model checking will be a single value. If the expression to be checked does not
		// already yield a single value (e.g. because a filter has not been explicitly included), we need to wrap
		// a new (invisible) filter around it. Note that some filters (e.g. print/argmin/argmax) also do not
		// return single values and have to be treated in this way.
		if (!expr.returnsSingleValue()) {
			// New filter depends on expression type and number of initial states.
			// Boolean expressions...
			if (expr.getType() instanceof TypeBool) {
				// Result is true iff true for all initial states
				exprFilter = new ExpressionFilter("forall", expr, new ExpressionLabel("init"));
			}
			// Non-Boolean (double or integer) expressions...
			else {
				// Result is for the initial state, if there is just one,
				// or the range over all initial states, if multiple
				if (model.getNumInitialStates() == 1) {
					exprFilter = new ExpressionFilter("state", expr, new ExpressionLabel("init"));
				} else {
					exprFilter = new ExpressionFilter("range", expr, new ExpressionLabel("init"));
				}
			}
		}
		// Even, when the expression does already return a single value, if the the outermost operator
		// of the expression is not a filter, we still need to wrap a new filter around it.
		// e.g. 2*filter(...) or 1-P=?[...{...}]
		// This because the final result of model checking is only stored when we process a filter.
		else if (!(expr instanceof ExpressionFilter)) {
			// We just pick the first value (they are all the same)
			exprFilter = new ExpressionFilter("first", expr, new ExpressionLabel("init"));
			// We stop any additional explanation being displayed to avoid confusion.
			exprFilter.setExplanationEnabled(false);
		}
		
		// For any case where a new filter was created above...
		if (exprFilter != null) {
			// Make it invisible (not that it will be displayed)
			exprFilter.setInvisible(true);
			// Compute type of new filter expression (will be same as child)
			exprFilter.typeCheck();
			// Store as expression to be model checked
			expr = exprFilter;
		}

		// Do model checking and store result vector
		timer = System.currentTimeMillis();
		BitSet needStates = new BitSet(model.getNumStates());
		needStates.set(0, model.getNumStates());
		RegionValues vals = checkExpression(paramModel, expr, needStates);
		timer = System.currentTimeMillis() - timer;
		mainLog.println("\nTime for model checking: " + timer / 1000.0 + " seconds.");
		result = new Result();
		vals.clearExceptInit();
		result.setResult(vals);
		mainLog.println(result);
		if (paramLower.length == 2) {
			try {
				FileOutputStream file = new FileOutputStream("out.tex");
				ResultExporter printer = new ResultExporter();
				printer.setOutputStream(file);
				printer.setRegionValues(vals);
				printer.setPointsPerDimension(19);
				printer.print();
				file.close();
			} catch (Exception e) {
				throw new PrismException("file could not be written");
			}
		}
		
		return result;
	}
	
	private int parserBinaryOpToRegionOp(int parserOp) throws PrismException
	{
		int regionOp;
		switch (parserOp) {
		case ExpressionBinaryOp.IMPLIES:
			regionOp = Region.IMPLIES;
			break;
		case ExpressionBinaryOp.IFF:
			regionOp = Region.IMPLIES;
			break;
		case ExpressionBinaryOp.OR:
			regionOp = Region.OR;
			break;
		case ExpressionBinaryOp.AND:
			regionOp = Region.AND;
			break;
		case ExpressionBinaryOp.EQ:
			regionOp = Region.EQ;
			break;
		case ExpressionBinaryOp.NE:
			regionOp = Region.NE;
			break;
		case ExpressionBinaryOp.GT:
			regionOp = Region.GT;
			break;
		case ExpressionBinaryOp.GE:
			regionOp = Region.GE;
			break;
		case ExpressionBinaryOp.LT:
			regionOp = Region.LT;
			break;
		case ExpressionBinaryOp.LE:
			regionOp = Region.LE;
			break;
		case ExpressionBinaryOp.PLUS:
			regionOp = Region.PLUS;
			break;
		case ExpressionBinaryOp.MINUS:
			regionOp = Region.MINUS;
			break;
		case ExpressionBinaryOp.TIMES:
			regionOp = Region.TIMES;
			break;
		case ExpressionBinaryOp.DIVIDE:
			regionOp = Region.DIVIDE;
			break;
		default:
			throw new PrismException("operator \"" + ExpressionBinaryOp.opSymbols[parserOp]
					+ "\" not currently supported for parametric analyses");				
		}
		return regionOp;
	}

	private int parserUnaryOpToRegionOp(int parserOp) throws PrismException
	{
		int regionOp;
		switch (parserOp) {
		case ExpressionUnaryOp.MINUS:
			regionOp = Region.UMINUS;
			break;
		case ExpressionUnaryOp.NOT:
			regionOp = Region.NOT;
			break;
		case ExpressionUnaryOp.PARENTH:
			regionOp = Region.PARENTH;
			break;
		default:
			throw new PrismException("operator \"" + ExpressionBinaryOp.opSymbols[parserOp]
					+ "\" not currently supported for parametric analyses");				
		}
		return regionOp;
	}

	/**
	 * Model check an expression and return a vector result values over all states.
	 * Information about states and model constants should be attached to the model.
	 * For other required info (labels, reward structures, etc.), use the methods
	 * {@link #setModulesFile} and {@link #setPropertiesFile}
	 * to attach the original model/properties files.
	 */
	RegionValues checkExpression(ParamModel model, Expression expr, BitSet needStates) throws PrismException
	{
		RegionValues res;
		if (expr instanceof ExpressionUnaryOp) {
			res = checkExpressionUnaryOp(model, (ExpressionUnaryOp) expr, needStates);
		} else if (expr instanceof ExpressionBinaryOp) {
			res = checkExpressionBinaryOp(model, (ExpressionBinaryOp) expr, needStates);
		} else if (expr instanceof ExpressionLabel) {
			res = checkExpressionLabel(model, (ExpressionLabel) expr, needStates);
		} else if (expr instanceof ExpressionProp) {
			res = checkExpressionProp(model, (ExpressionProp) expr, needStates);
		} else if (expr instanceof ExpressionFilter) {
			if (((ExpressionFilter) expr).isParam()) {
				res = checkExpressionFilterParam(model, (ExpressionFilter) expr, needStates);
			} else {
				res = checkExpressionFilter(model, (ExpressionFilter) expr, needStates);
			}
		} else if (expr instanceof ExpressionProb) {
			res = checkExpressionProb(model, (ExpressionProb) expr, needStates);
		} else if (expr instanceof ExpressionReward) {
			res = checkExpressionReward(model, (ExpressionReward) expr, needStates);
		} else if (expr instanceof ExpressionSS) {
			res = checkExpressionSteadyState(model, (ExpressionSS) expr, needStates);
		} else {
			res = checkExpressionAtomic(model, expr, needStates);
		}
		return res;
	}

	private RegionValues checkExpressionAtomic(ParamModel model,
			Expression expr, BitSet needStates) throws PrismException {
		expr = (Expression) expr.replaceConstants(constantValues);
		
		int numStates = model.getNumStates();
		List<State> statesList = model.getStatesList();
		StateValues stateValues = new StateValues(numStates, model.getFirstInitialState());
		int[] varMap = new int[statesList.get(0).varValues.length];
		for (int var = 0; var < varMap.length; var++) {
			varMap[var] = var;
		}
		for (int state = 0; state < numStates; state++) {
			Expression exprVar = (Expression) expr.evaluatePartially(statesList.get(state), varMap);
			if (needStates.get(state)) {
				if (exprVar instanceof ExpressionLiteral) {
					ExpressionLiteral exprLit = (ExpressionLiteral) exprVar;
					if (exprLit.getType() instanceof TypeBool) {
						stateValues.setStateValue(state, exprLit.evaluateBoolean());
					} else if (exprLit.getType() instanceof TypeInt || exprLit.getType() instanceof TypeDouble) {
						String exprStr = exprLit.getString();
						BigRational exprRat = new BigRational(exprStr);
						stateValues.setStateValue(state, functionFactory.fromBigRational(exprRat));
					} else {
						throw new PrismException("model checking expresssion " + expr + " not supported for parametric models");
					}
				} else if (exprVar instanceof ExpressionConstant) {
					ExpressionConstant exprConst = (ExpressionConstant) exprVar;
					stateValues.setStateValue(state, functionFactory.getVar(exprConst.getName()));
				} else {
					throw new PrismException("cannot handle expression " + expr + " in parametric analysis");
				}
			} else {
				if (exprVar.getType() instanceof TypeBool) {
					stateValues.setStateValue(state, false);
				} else {
					stateValues.setStateValue(state, functionFactory.getZero());						
				}
			}
		}	
		return regionFactory.completeCover(stateValues);
	}

	protected RegionValues checkExpressionUnaryOp(ParamModel model, ExpressionUnaryOp expr, BitSet needStates) throws PrismException
	{
		RegionValues resInner = checkExpression(model, expr.getOperand(), needStates);
		resInner.clearNotNeeded(needStates);

		return resInner.unaryOp(parserUnaryOpToRegionOp(expr.getOperator()));
	}

	/**
	 * Model check a binary operator.
	 */
	protected RegionValues checkExpressionBinaryOp(ParamModel model, ExpressionBinaryOp expr, BitSet needStates) throws PrismException
	{
		RegionValues res1 = checkExpression(model, expr.getOperand1(), needStates);
		RegionValues res2 = checkExpression(model, expr.getOperand2(), needStates);
		res1.clearNotNeeded(needStates);
		res2.clearNotNeeded(needStates);

		return res1.binaryOp(parserBinaryOpToRegionOp(expr.getOperator()), res2);
	}

	/**
	 * Model check a label.
	 */
	protected RegionValues checkExpressionLabel(ParamModel model, ExpressionLabel expr, BitSet needStates) throws PrismException
	{
		LabelList ll;
		int i;
		
		// treat special cases
		if (expr.getName().equals("deadlock")) {
			int numStates = model.getNumStates();
			StateValues stateValues = new StateValues(numStates, model.getFirstInitialState());
			for (i = 0; i < numStates; i++) {
				stateValues.setStateValue(i, model.isDeadlockState(i));
			}
			return regionFactory.completeCover(stateValues);
		} else if (expr.getName().equals("init")) {
			int numStates = model.getNumStates();
			StateValues stateValues = new StateValues(numStates, model.getFirstInitialState());
			for (i = 0; i < numStates; i++) {
				stateValues.setStateValue(i, model.isInitialState(i));
			}
			return regionFactory.completeCover(stateValues);
		} else {
			ll = propertiesFile.getCombinedLabelList();
			i = ll.getLabelIndex(expr.getName());
			if (i == -1)
				throw new PrismException("Unknown label \"" + expr.getName() + "\" in property");
			// check recursively
			return checkExpression(model, ll.getLabel(i), needStates);
		}
	}

	// Check property ref

	protected RegionValues checkExpressionProp(ParamModel model, ExpressionProp expr, BitSet needStates) throws PrismException
	{
		// Look up property and check recursively
		Property prop = propertiesFile.lookUpPropertyObjectByName(expr.getName());
		if (prop != null) {
			mainLog.println("\nModel checking : " + prop);
			return checkExpression(model, prop.getExpression(), needStates);
		} else {
			throw new PrismException("Unknown property reference " + expr);
		}
	}

	// Check filter

	protected RegionValues checkExpressionFilter(ParamModel model, ExpressionFilter expr, BitSet needStates) throws PrismException
	{
		RegionValues resVals = null;
		Expression filter = expr.getFilter();
		if (filter == null) {
			filter = Expression.True();
		}
		boolean filterTrue = Expression.isTrue(filter);
		
		BitSet needStatesInner = new BitSet(needStates.size());
		needStatesInner.set(0, needStates.size());
		RegionValues rvFilter = checkExpression(model, filter, needStatesInner);
		if (!rvFilter.parameterIndependent()) {
			throw new PrismException("currently, parameter-dependent filters are not supported");
		}
		BitSet bsFilter = rvFilter.getStateValues().toBitSet();
		RegionValues vals = checkExpression(model, expr.getOperand(), bsFilter);

		// Check if filter state set is empty; we treat this as an error
		if (bsFilter.isEmpty()) {
			throw new PrismException("Filter satisfies no states");
		}
		
		// Remember whether filter is for the initial state and, if so, whether there's just one
		boolean filterInit = (filter instanceof ExpressionLabel && ((ExpressionLabel) filter).getName().equals("init"));
		// Print out number of states satisfying filter
		if (!filterInit) {
			mainLog.println("\nStates satisfying filter " + filter + ": " + bsFilter.cardinality());
		}
			
		// Compute result according to filter type
		FilterOperator op = expr.getOperatorType();
		switch (op) {
		case PRINT:
			// Format of print-out depends on type
			if (expr.getType() instanceof TypeBool) {
				// NB: 'usual' case for filter(print,...) on Booleans is to use no filter
				mainLog.print("\nSatisfying states");
				mainLog.println(filterTrue ? ":" : " that are also in filter " + filter + ":");
				mainLog.print(vals.filteredString(bsFilter));
			} else {
				mainLog.println("\nResults (non-zero only) for filter " + filter + ":");
				mainLog.print(vals.filteredString(bsFilter));
			}
			resVals = vals;
			break;
		case MIN:
		case MAX:
		case ARGMIN:
		case ARGMAX:
			throw new PrismException("operation not implemented for parametric models");
		case COUNT:
			resVals = vals.op(Region.COUNT, bsFilter);
			break;
		case SUM:
			resVals = vals.op(Region.PLUS, bsFilter);
			break;
		case AVG:
			resVals = vals.op(Region.AVG, bsFilter);
			break;
		case FIRST:
			if (bsFilter.cardinality() < 1) {
				throw new PrismException("Filter should be satisfied in at least 1 state.");
			}
			resVals = vals.op(Region.FIRST, bsFilter);
			break;
		case RANGE:
			throw new PrismException("operation not implemented for parametric models");
		case FORALL:
			resVals = vals.op(Region.FORALL, bsFilter);
			break;
		case EXISTS:
			resVals = vals.op(Region.EXISTS, bsFilter);
			break;
		case STATE:
			// Check filter satisfied by exactly one state
			if (bsFilter.cardinality() != 1) {
				String s = "Filter should be satisfied in exactly 1 state";
				s += " (but \"" + filter + "\" is true in " + bsFilter.cardinality() + " states)";
				throw new PrismException(s);
			}
			resVals = vals.op(Region.FIRST, bsFilter);
			break;
		default:
			throw new PrismException("Unrecognised filter type \"" + expr.getOperatorName() + "\"");
		}

		return resVals;
	}

	// check filter over parameters
	
	protected RegionValues checkExpressionFilterParam(ParamModel model, ExpressionFilter expr, BitSet needStates) throws PrismException
	{
		// Filter info
		Expression filter = expr.getFilter();
		// Create default filter (true) if none given
		if (filter == null) {
			filter = Expression.True();
		}
		RegionValues rvFilter = checkExpression(model, filter, needStates);
		RegionValues vals = checkExpression(model, expr.getOperand(), needStates);

		Optimiser opt = new Optimiser(vals, rvFilter, expr.getOperatorType() == FilterOperator.MIN);
		System.out.println("\n" + opt.optimise());
		
		return null;

		/*
		// Remember whether filter is for the initial state and, if so, whether there's just one
		filterInit = (filter instanceof ExpressionLabel && ((ExpressionLabel) filter).getName().equals("init"));
		filterInitSingle = filterInit & model.getNumInitialStates() == 1;
		// Print out number of states satisfying filter
		if (!filterInit)
			mainLog.println("\nStates satisfying filter " + filter + ": " + bsFilter.cardinality());

		// Compute result according to filter type
		op = expr.getOperatorType();
		switch (op) {
		case PRINT:
			// Format of print-out depends on type
			if (expr.getType() instanceof TypeBool) {
				// NB: 'usual' case for filter(print,...) on Booleans is to use no filter
				mainLog.print("\nSatisfying states");
				mainLog.println(filterTrue ? ":" : " that are also in filter " + filter + ":");
				vals.printFiltered(mainLog, bsFilter);
			} else {
				mainLog.println("\nResults (non-zero only) for filter " + filter + ":");
				vals.printFiltered(mainLog, bsFilter);
			}
			// Result vector is unchanged; for ARGMIN, don't store a single value (in resObj)
			// Also, don't bother with explanation string
			resVals = vals;
			// Set vals to null to stop it being cleared below
			vals = null;
			break;
		case MIN:
			// Compute min
			// Store as object/vector
			resObj = vals.minOverBitSet(bsFilter);
			resVals = new RegionValues(expr.getType(), resObj, model); 
			// Create explanation of result and print some details to log
			resultExpl = "Minimum value over " + filterStatesString;
			mainLog.println("\n" + resultExpl + ": " + resObj);
			// Also find states that (are close to) selected value for display to log
			// TODO: un-hard-code precision once RegionValues knows hoe precise it is
			bsMatch = vals.getBitSetFromCloseValue(resObj, 1e-5, false);
			bsMatch.and(bsFilter);
			break;
		case MAX:
			// Compute max
			// Store as object/vector
			resObj = vals.maxOverBitSet(bsFilter);
			resVals = new RegionValues(expr.getType(), resObj, model); 
			// Create explanation of result and print some details to log
			resultExpl = "Maximum value over " + filterStatesString;
			mainLog.println("\n" + resultExpl + ": " + resObj);
			// Also find states that (are close to) selected value for display to log
			// TODO: un-hard-code precision once RegionValues knows hoe precise it is
			bsMatch = vals.getBitSetFromCloseValue(resObj, 1e-5, false);
			bsMatch.and(bsFilter);
			break;
		case ARGMIN:
			// Compute/display min
			resObj = vals.minOverBitSet(bsFilter);
			mainLog.print("\nMinimum value over " + filterStatesString + ": " + resObj);
			// Find states that (are close to) selected value
			// TODO: un-hard-code precision once RegionValues knows hoe precise it is
			bsMatch = vals.getBitSetFromCloseValue(resObj, 1e-5, false);
			bsMatch.and(bsFilter);
			// Store states in vector; for ARGMIN, don't store a single value (in resObj)
			// Also, don't bother with explanation string
			resVals = RegionValues.createFromBitSet(bsMatch, model);
			// Print out number of matching states, but not the actual states
			mainLog.println("\nNumber of states with minimum value: " + bsMatch.cardinality());
			bsMatch = null;
			break;
		case ARGMAX:
			// Compute/display max
			resObj = vals.maxOverBitSet(bsFilter);
			mainLog.print("\nMaximum value over " + filterStatesString + ": " + resObj);
			// Find states that (are close to) selected value
			bsMatch = vals.getBitSetFromCloseValue(resObj, precision, false);
			bsMatch.and(bsFilter);
			// Store states in vector; for ARGMAX, don't store a single value (in resObj)
			// Also, don't bother with explanation string
			resVals = RegionValues.createFromBitSet(bsMatch, model);
			// Print out number of matching states, but not the actual states
			mainLog.println("\nNumber of states with maximum value: " + bsMatch.cardinality());
			bsMatch = null;
			break;
		case COUNT:
			// Compute count
			int count = vals.countOverBitSet(bsFilter);
			// Store as object/vector
			resObj = new Integer(count);
			resVals = new RegionValues(expr.getType(), resObj, model); 
			// Create explanation of result and print some details to log
			resultExpl = filterTrue ? "Count of satisfying states" : "Count of satisfying states also in filter";
			mainLog.println("\n" + resultExpl + ": " + resObj);
			break;
		case SUM:
			// Compute sum
			// Store as object/vector
			resObj = vals.sumOverBitSet(bsFilter);
			resVals = new RegionValues(expr.getType(), resObj, model); 
			// Create explanation of result and print some details to log
			resultExpl = "Sum over " + filterStatesString;
			mainLog.println("\n" + resultExpl + ": " + resObj);
			break;
		case AVG:
			// Compute average
			// Store as object/vector
			resObj = vals.averageOverBitSet(bsFilter);
			resVals = new RegionValues(expr.getType(), resObj, model); 
			// Create explanation of result and print some details to log
			resultExpl = "Average over " + filterStatesString;
			mainLog.println("\n" + resultExpl + ": " + resObj);
			break;
		case FIRST:
			// Find first value
			resObj = vals.firstFromBitSet(bsFilter);
			resVals = new RegionValues(expr.getType(), resObj, model); 
			// Create explanation of result and print some details to log
			resultExpl = "Value in ";
			if (filterInit) {
				resultExpl += filterInitSingle ? "the initial state" : "first initial state";
			} else {
				resultExpl += filterTrue ? "the first state" : "first state satisfying filter";
			}
			mainLog.println("\n" + resultExpl + ": " + resObj);
			break;
		case RANGE:
			// Find range of values
			resObj = new prism.Interval(vals.minOverBitSet(bsFilter), vals.maxOverBitSet(bsFilter));
			// Leave result vector unchanged: for a range, result is only available from Result object
			resVals = vals;
			// Set vals to null to stop it being cleared below
			vals = null;
			// Create explanation of result and print some details to log
			resultExpl = "Range of values over ";
			resultExpl += filterInit ? "initial states" : filterStatesString;
			mainLog.println("\n" + resultExpl + ": " + resObj);
			break;
		case FORALL:
			// Get access to BitSet for this
			if(paras == null) {
				bs = vals.getBitSet();
				// Print some info to log
				mainLog.print("\nNumber of states satisfying " + expr.getOperand() + ": ");
				mainLog.print(bs.cardinality());
				mainLog.println(bs.cardinality() == model.getNumStates() ? " (all in model)" : "");
				// Check "for all" over filter
				b = vals.forallOverBitSet(bsFilter);
				// Store as object/vector
				resObj = new Boolean(b);
				resVals = new RegionValues(expr.getType(), resObj, model); 
				// Create explanation of result and print some details to log
				resultExpl = "Property " + (b ? "" : "not ") + "satisfied in ";
				mainLog.print("\nProperty satisfied in " + vals.countOverBitSet(bsFilter));
				if (filterInit) {
					if (filterInitSingle) {
						resultExpl += "the initial state";
					} else {
						resultExpl += "all initial states";
					}
					mainLog.println(" of " + model.getNumInitialStates() + " initial states.");
				} else {
					if (filterTrue) {
						resultExpl += "all states";
						mainLog.println(" of all " + model.getNumStates() + " states.");
					} else {
						resultExpl += "all filter states";
						mainLog.println(" of " + bsFilter.cardinality() + " filter states.");
					}
				}
			}
			break;
		case EXISTS:
			// Get access to BitSet for this
			bs = vals.getBitSet();
			// Check "there exists" over filter
			b = vals.existsOverBitSet(bsFilter);
			// Store as object/vector
			resObj = new Boolean(b);
			resVals = new RegionValues(expr.getType(), resObj, model); 
			// Create explanation of result and print some details to log
			resultExpl = "Property satisfied in ";
			if (filterTrue) {
				resultExpl += b ? "at least one state" : "no states";
			} else {
				resultExpl += b ? "at least one filter state" : "no filter states";
			}
			mainLog.println("\n" + resultExpl);
			break;
		case STATE:
			if(paras == null) {
				// Check filter satisfied by exactly one state
				if (bsFilter.cardinality() != 1) {
					String s = "Filter should be satisfied in exactly 1 state";
					s += " (but \"" + filter + "\" is true in " + bsFilter.cardinality() + " states)";
					throw new PrismException(s);
				}
				// Find first (only) value
				// Store as object/vector
				resObj = vals.firstFromBitSet(bsFilter);
				resVals = new RegionValues(expr.getType(), resObj, model); 
				// Create explanation of result and print some details to log
				resultExpl = "Value in ";
				if (filterInit) {
					resultExpl += "the initial state";
				} else {
					resultExpl += "the filter state";
				}
				mainLog.println("\n" + resultExpl + ": " + resObj);
			}
			break;
		default:
			throw new PrismException("Unrecognised filter type \"" + expr.getOperatorName() + "\"");
		}

		// For some operators, print out some matching states
		if (bsMatch != null) {
			states = RegionValues.createFromBitSet(bsMatch, model);
			mainLog.print("\nThere are " + bsMatch.cardinality() + " states with ");
			mainLog.print(expr.getType() instanceof TypeDouble ? "(approximately) " : "" + "this value");
			boolean verbose = verbosity > 0; // TODO
			if (!verbose && bsMatch.cardinality() > 10) {
				mainLog.print(".\nThe first 10 states are displayed below. To view them all, enable verbose mode or use a print filter.\n");
				states.print(mainLog, 10);
			} else {
				mainLog.print(":\n");
				states.print(mainLog);
			}
			
		}

		// Store result
		result.setResult(resObj);
		// Set result explanation (if none or disabled, clear)
		if (expr.getExplanationEnabled() && resultExpl != null) {
			result.setExplanation(resultExpl.toLowerCase());
		} else {
			result.setExplanation(null);
		}

		// Clear up
		if (vals != null)
			vals.clear();

		return resVals;
		*/
	}
	
	/**
	 * Model check a P operator expression and return the values for all states.
	 */
	protected RegionValues checkExpressionProb(ParamModel model, ExpressionProb expr, BitSet needStates) throws PrismException
	{
		Expression pb; // Probability bound (expression)
		BigRational p = null; // Probability bound (actual value)
		//String relOp; // Relational operator
		//boolean min = false; // For nondeterministic models, are we finding min (true) or max (false) probs
		ModelType modelType = model.getModelType();
		String relOp;
		boolean min = false;

		RegionValues probs = null;

		// Get info from prob operator
		relOp = expr.getRelOp();
		pb = expr.getProb();
		if (pb != null) {
			// TODO check whether actually evaluated as such
			p = modelBuilder.expr2function(functionFactory, pb).asBigRational();
			if (p.compareTo(0) == -1 || p.compareTo(1) == 1)
				throw new PrismException("Invalid probability bound " + p + " in P operator");
		}

		// For nondeterministic models, determine whether min or max probabilities needed
		if (modelType.nondeterministic()) {
			if (relOp.equals(">") || relOp.equals(">=") || relOp.equals("min=")) {
				// min
				min = true;
			} else if (relOp.equals("<") || relOp.equals("<=") || relOp.equals("max=")) {
				// max
				min = false;
			} else {
				throw new PrismException("Can't use \"P=?\" for nondeterministic models; use \"Pmin=?\" or \"Pmax=?\"");
			}
		}

		// Compute probabilities
		if (!expr.getExpression().isSimplePathFormula()) {
			throw new PrismException("Parametric engine does not yet handle LTL-style path formulas");
		}
		probs = checkProbPathFormulaSimple(model, expr.getExpression(), min, needStates);
		probs.clearNotNeeded(needStates);

		if (getVerbosity() > 5) {
			mainLog.print("\nProbabilities (non-zero only) for all states:\n");
			mainLog.print(probs);
		}
		// For =? properties, just return values
		if (pb == null) {
			return probs;
		}
		// Otherwise, compare against bound to get set of satisfying states
		else {
			return probs.binaryOp(Region.getOp(relOp), p);
		}
	}
	
	private RegionValues checkProbPathFormulaSimple(ParamModel model, Expression expr, boolean min, BitSet needStates) throws PrismException
	{
		RegionValues probs = null;
		if (expr instanceof ExpressionTemporal) {
			ExpressionTemporal exprTemp = (ExpressionTemporal) expr;
			if (exprTemp.getOperator() == ExpressionTemporal.P_U) {
				BitSet needStatesInner = new BitSet(model.getNumStates());
				needStatesInner.set(0, model.getNumStates());
				RegionValues b1 = checkExpression(model, exprTemp.getOperand1(), needStatesInner);
				RegionValues b2 = checkExpression(model, exprTemp.getOperand2(), needStatesInner);
				if (exprTemp.hasBounds()) {
					probs = checkProbBoundedUntil(model, b1, b2, min);
				} else {
					probs = checkProbUntil(model, b1, b2, min, needStates);
				}
			}
			// Anything else - convert to until and recurse
			else {
				probs = checkProbPathFormulaSimple(model, exprTemp.convertToUntilForm(), min, needStates);
			}
		}
		
		return probs;
	}

	private RegionValues checkProbUntil(ParamModel model, RegionValues b1, RegionValues b2, boolean min, BitSet needStates) throws PrismException {
		return valueComputer.computeUnbounded(b1, b2, min, null);
	}
		
	private RegionValues checkProbBoundedUntil(ParamModel model, RegionValues b1, RegionValues b2, boolean min) throws PrismException {
		ModelType modelType = model.getModelType();
		RegionValues probs;
		switch (modelType) {
		case CTMC:
			throw new PrismException("bounded until not implemented for parametric CTMCs");
		case DTMC:
			probs = checkProbBoundedUntilDTMC(model, b1, b2);
			break;
		case MDP:
			probs = checkProbBoundedUntilMDP(model, b1, b2, min);
			break;
		default:
			throw new PrismException("Cannot model check for a " + modelType);
		}

		return probs;
	}

	private RegionValues checkProbBoundedUntilMDP(ParamModel model, RegionValues b1, RegionValues b2, boolean min) {
		throw new UnsupportedOperationException("Bounded until is not supported at the moment");
	}

	private RegionValues checkProbBoundedUntilDTMC(ParamModel model, RegionValues b1, RegionValues b2) {
		throw new UnsupportedOperationException("Bounded until is not supported at the moment");
	}

	/**
	 * Model check an R operator expression and return the values for all states.
	 */
	protected RegionValues checkExpressionReward(ParamModel model, ExpressionReward expr, BitSet needStates) throws PrismException
	{
		Object rs; // Reward struct index
		RewardStruct rewStruct = null; // Reward struct object
		Expression rb; // Reward bound (expression)
		BigRational r = null; // Reward bound (actual value)
		//String relOp; // Relational operator
		ModelType modelType = model.getModelType();
		RegionValues rews = null;
		int i;
		boolean min = false;

		// Get info from reward operator
		rs = expr.getRewardStructIndex();
		String relOp = expr.getRelOp();
		rb = expr.getReward();
		if (rb != null) {
			// TODO check whether actually evaluated as such, take constantValues into account
			r = modelBuilder.expr2function(functionFactory, rb).asBigRational();
			if (r.compareTo(0) == -1)
				throw new PrismException("Invalid reward bound " + r + " in R[] formula");
		}

		// For nondeterministic models, determine whether min or max rewards needed
		//if (modelType.nondeterministic()) {
			if (relOp.equals(">") || relOp.equals(">=") || relOp.equals("min=")) {
				// min
				min = true;
			} else if (relOp.equals("<") || relOp.equals("<=") || relOp.equals("max=")) {
				// max
				min = false;
			} else if(modelType.nondeterministic()) {
				throw new PrismException("Can't use \"R=?\" for nondeterministic models; use \"Rmin=?\" or \"Rmax=?\"");
			} 
		//}

		// Get reward info
		if (modulesFile == null)
			throw new PrismException("No model file to obtain reward structures");
		if (modulesFile.getNumRewardStructs() == 0)
			throw new PrismException("Model has no rewards specified");
		if (rs == null) {
			rewStruct = modulesFile.getRewardStruct(0);
		} else if (rs instanceof Expression) {
			i = ((Expression) rs).evaluateInt(constantValues);
			rs = new Integer(i); // for better error reporting below
			rewStruct = modulesFile.getRewardStruct(i - 1);
		} else if (rs instanceof String) {
			rewStruct = modulesFile.getRewardStructByName((String) rs);
		}
		if (rewStruct == null)
			throw new PrismException("Invalid reward structure index \"" + rs + "\"");

		
		ParamRewardStruct rew = constructRewards(model, rewStruct, constantValues);
		mainLog.println("Building reward structure...");
		rews = checkRewardFormula(model, rew, expr.getExpression(), min, needStates);
		rews.clearNotNeeded(needStates);

		// Print out probabilities
		if (getVerbosity() > 5) {
			mainLog.print("\nProbabilities (non-zero only) for all states:\n");
			mainLog.print(rews);
		}

		// For =? properties, just return values
		if (rb == null) {
			return rews;
		}
		// Otherwise, compare against bound to get set of satisfying states
		else {
			return rews.binaryOp(Region.getOp(relOp), r);
		}
	}
	
	private RegionValues checkRewardFormula(ParamModel model,
			ParamRewardStruct rew, Expression expr, boolean min, BitSet needStates) throws PrismException {
		RegionValues rewards = null;
		
		if (expr instanceof ExpressionTemporal) {
			ExpressionTemporal exprTemp = (ExpressionTemporal) expr;
			switch (exprTemp.getOperator()) {
			case ExpressionTemporal.R_F:
				rewards = checkRewardReach(model, rew, exprTemp, min, needStates);
				break;
			case ExpressionTemporal.R_S:
				rewards = checkRewardSteady(model, rew, exprTemp, min, needStates);				
				break;
			default:
				throw new PrismException("Parametric engine does not yet handle the " + exprTemp.getOperatorSymbol() + " operator in the R operator");
			}
		}
		
		if (rewards == null) {
			throw new PrismException("Unrecognised operator in R operator");
		}
		
		return rewards;
	}

	private RegionValues checkRewardReach(ParamModel model,
			ParamRewardStruct rew, ExpressionTemporal expr, boolean min, BitSet needStates) throws PrismException {
		RegionValues allTrue = regionFactory.completeCover(true);
		BitSet needStatesInner = new BitSet(needStates.size());
		needStatesInner.set(0, needStates.size());
		RegionValues reachSet = checkExpression(model, expr.getOperand2(), needStatesInner);
		return valueComputer.computeUnbounded(allTrue, reachSet, min, rew);
	}
	
	private RegionValues checkRewardSteady(ParamModel model,
			ParamRewardStruct rew, ExpressionTemporal expr, boolean min, BitSet needStates) throws PrismException {
		if (model.getModelType() != ModelType.DTMC && model.getModelType() != ModelType.CTMC) {
			throw new PrismException("Parametric long-run average rewards are only supported for DTMCs and CTMCs");
		}
		RegionValues allTrue = regionFactory.completeCover(true);
		BitSet needStatesInner = new BitSet(needStates.size());
		needStatesInner.set(0, needStates.size());
		return valueComputer.computeSteadyState(allTrue, min, rew);
	}

	private ParamRewardStruct constructRewards(ParamModel model, RewardStruct rewStruct, Values constantValues2)
			throws PrismException {
		int numStates = model.getNumStates();
		List<State> statesList = model.getStatesList();
		ParamRewardStruct rewSimple = new ParamRewardStruct(functionFactory, model.getNumTotalChoices());
		int numRewItems = rewStruct.getNumItems();
		for (int rewItem = 0; rewItem < numRewItems; rewItem++) {
			Expression expr = rewStruct.getReward(rewItem);
			expr = (Expression) expr.replaceConstants(constantValues);
			Expression guard = rewStruct.getStates(rewItem);
			String action = rewStruct.getSynch(rewItem);
			boolean isTransitionReward = rewStruct.getRewardStructItem(rewItem).isTransitionReward();
			for (int state = 0; state < numStates; state++) {
				if (guard.evaluateBoolean(constantValues, statesList.get(state))) {
					int[] varMap = new int[statesList.get(0).varValues.length];
					for (int i = 0; i < varMap.length; i++) {
						varMap[i] = i;
					}
					Expression exprState = (Expression) expr.evaluatePartially(statesList.get(state), varMap);
					Function newReward = modelBuilder.expr2function(functionFactory, exprState);
					for (int choice = model.stateBegin(state); choice < model.stateEnd(state); choice++) {
						Function sumOut = model.sumLeaving(choice);
						Function choiceReward;
						if (!isTransitionReward) {
							choiceReward = newReward.divide(sumOut);
						} else {
							choiceReward = functionFactory.getZero();
							for (int succ = model.choiceBegin(choice); succ < model.choiceEnd(choice); succ++) {
								String mdpAction = model.getLabel(succ);
								if ((isTransitionReward && (mdpAction == null ? (action.isEmpty()) : mdpAction.equals(action)))) {
									choiceReward = choiceReward.add(newReward.multiply(model.succProb(succ)));
								}
							}
							choiceReward = choiceReward.divide(sumOut);
						}
						rewSimple.addReward(choice, choiceReward);
					}
				}
			}
		}
		return rewSimple;
	}
	
	/**
	 * Model check an S operator expression and return the values for all states.
	 */
	protected RegionValues checkExpressionSteadyState(ParamModel model, ExpressionSS expr, BitSet needStates) throws PrismException
	{
		Expression pb; // Probability bound (expression)
		BigRational p = null; // Probability bound (actual value)
		//String relOp; // Relational operator
		//boolean min = false; // For nondeterministic models, are we finding min (true) or max (false) probs
		ModelType modelType = model.getModelType();
		String relOp;
		boolean min = false;

		RegionValues probs = null;

		// Get info from prob operator
		relOp = expr.getRelOp();
		pb = expr.getProb();
		if (pb != null) {
			// TODO check whether actually evaluated as such
			p = modelBuilder.expr2function(functionFactory, pb).asBigRational();
			if (p.compareTo(0) == -1 || p.compareTo(1) == 1)
				throw new PrismException("Invalid probability bound " + p + " in P operator");
		}

		// For nondeterministic models, determine whether min or max probabilities needed
		if (modelType.nondeterministic()) {
			if (relOp.equals(">") || relOp.equals(">=") || relOp.equals("min=")) {
				// min
				min = true;
			} else if (relOp.equals("<") || relOp.equals("<=") || relOp.equals("max=")) {
				// max
				min = false;
			} else {
				throw new PrismException("Can't use \"S=?\" for nondeterministic models; use \"Smin=?\" or \"Smax=?\"");
			}
		}

		// Compute probabilities
		probs = checkProbSteadyState(model, expr.getExpression(), min, needStates);
		probs.clearNotNeeded(needStates);

		if (getVerbosity() > 5) {
			mainLog.print("\nProbabilities (non-zero only) for all states:\n");
			mainLog.print(probs);
		}
		// For =? properties, just return values
		if (pb == null) {
			return probs;
		}
		// Otherwise, compare against bound to get set of satisfying states
		else {
			return probs.binaryOp(Region.getOp(relOp), p);
		}
	}

	private RegionValues checkProbSteadyState(ParamModel model, Expression expr, boolean min, BitSet needStates)
	throws PrismException
	{
		BitSet needStatesInner = new BitSet(model.getNumStates());
		needStatesInner.set(0, model.getNumStates());
		RegionValues b = checkExpression(model,expr, needStatesInner);
		if (model.getModelType() != ModelType.DTMC
				&& model.getModelType() != ModelType.CTMC) {
			throw new PrismException("Parametric engine currently only implements steady state for DTMCs and CTMCs.");
		}
		return valueComputer.computeSteadyState(b, min, null);
	}

	/**
	 * Set parameters for parametric analysis.
	 * 
	 * @param paramNames names of parameters
	 * @param lower lower bounds of parameters
	 * @param upper upper bounds of parameters
	 */
	public void setParameters(String[] paramNames, String[] lower, String[] upper) {
		if (paramNames == null || lower == null || upper == null) {
			throw new IllegalArgumentException("all arguments of this functions must be non-null");
		}
		if (paramNames.length != lower.length || lower.length != upper.length) {
			throw new IllegalArgumentException("all arguments of this function must have the same length");
		}
		
		paramLower = new BigRational[lower.length];
		paramUpper = new BigRational[upper.length];
		
		for (int i = 0; i < paramNames.length; i++) {
			if (paramNames[i] == null || lower[i] == null || upper[i] == null)  {
				throw new IllegalArgumentException("all entries in arguments of this function must be non-null");
			}
			paramLower[i] = new BigRational(lower[i]);
			paramUpper[i] = new BigRational(upper[i]);
		}
	}	
			
	public static void closeDown() {
		ComputerThreads.terminate();
	}

	public void setModelBuilder(ModelBuilder builder)
	{
		this.modelBuilder = builder;
	}	
}
