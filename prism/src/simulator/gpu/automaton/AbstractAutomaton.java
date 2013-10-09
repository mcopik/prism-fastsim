/**
 * 
 */
package simulator.gpu.automaton;

import parser.VarList;
import parser.ast.ModulesFile;
import prism.PrismException;

/**
 * @author mcopik
 *
 */
public abstract class AbstractAutomaton
{
	protected ModulesFile modulesFile = null;
	protected VarList variables = null;
	public AbstractAutomaton(ModulesFile modulesFile) throws PrismException
	{
		// get list of all variables
		variables = modulesFile.createVarList();
		// Evaluate constants and optimise (a copy of) modules file for simulation
		modulesFile = (ModulesFile) modulesFile.deepCopy().
				replaceConstants(modulesFile.getConstantValues()).simplify();
		this.modulesFile = modulesFile;
	}
}
