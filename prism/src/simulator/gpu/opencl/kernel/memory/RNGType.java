/**
 * 
 */
package simulator.gpu.opencl.kernel.memory;

import simulator.gpu.opencl.kernel.expression.Expression;
import simulator.gpu.opencl.kernel.expression.ExpressionGenerator;

/**
 * @author mcopik
 *
 */
public class RNGType implements VariableInterface
{
	/**
	 * Because Java doesn't have unsigned int.
	 * Or unsigned long.
	 * Or just unsigned arithmetic.
	 * Why?
	 */
	public static final String RNG_MAX = "0xFFFFFFFF";

	static public Expression initializeGenerator(CLVariable rng, CLVariable offset, String baseOffset)
	{
		return new Expression(String.format("MWC64X_SeedStreams(&%s,%s,%s);", rng.varName, offset.varName, baseOffset));
	}

	static public Expression assignRandomInt(CLVariable rng, CLVariable dest, int max)
	{
		return ExpressionGenerator.assignValue(dest, String.format("floor(((float)MWC64X_NextUint(&%s))*%d/%s)", rng.varName, max, RNG_MAX));
	}

	static public Expression assignRandomFloat(CLVariable rng, CLVariable dest)
	{
		return ExpressionGenerator.assignValue(dest, String.format("((float)MWC64X_NextUint(&%s))/%s", rng.varName, RNG_MAX));
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.memory.VariableInterface#getDeclaration()
	 */
	@Override
	public Expression getDeclaration()
	{
		return null;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.memory.VariableInterface#getDefinition()
	 */
	@Override
	public Expression getDefinition()
	{
		return null;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.memory.VariableInterface#getType()
	 */
	@Override
	public String getType()
	{
		return "mwc64x_state_t";
	}
}