/**
 * 
 */
package simulator.gpu.automaton;

import parser.ast.ModulesFile;
import prism.ModelType;
import prism.PrismException;

/**
 * @author mcopik
 *
 */
public class DTMC extends AbstractAutomaton
{
	public DTMC(ModulesFile modulesFile) throws PrismException
	{
		super(modulesFile);
		if(modulesFile.getModelType() != ModelType.DTMC)
		{
			throw new IllegalArgumentException("Attempt to create DTMC from automaton which"
					+ " is " + modulesFile.getModelType().fullName());
		}
	}
}
