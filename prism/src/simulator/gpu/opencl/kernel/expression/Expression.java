/**
 * 
 */
package simulator.gpu.opencl.kernel.expression;

/**
 * @author mcopik
 *
 */
public class Expression
{
	private final String expr;

	public Expression(String expr)
	{
		this.expr = expr;
	}

	public String getSource()
	{
		return expr;
	}
}
