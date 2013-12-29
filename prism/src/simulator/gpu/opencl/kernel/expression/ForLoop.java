package simulator.gpu.opencl.kernel.expression;

import simulator.gpu.opencl.kernel.expression.ExpressionGenerator.Operator;
import simulator.gpu.opencl.kernel.memory.CLVariable;
import simulator.gpu.opencl.kernel.memory.StdVariableType;

public class ForLoop extends ComplexKernelComponent
{
	private CLVariable counter;
	private String endValue;
	boolean decreasing = false;

	public ForLoop(String counterName, int startValue, int endValue)
	{
		counter = new CLVariable(new StdVariableType(startValue, endValue), counterName);
		counter.setInitValue(StdVariableType.initialize(startValue));
		this.endValue = Integer.toString(endValue);
	}

	public ForLoop(String counterName, int startValue, int endValue, boolean decreasing)
	{
		if (decreasing) {
			counter = new CLVariable(new StdVariableType(endValue, startValue), counterName);
			counter.setInitValue(StdVariableType.initialize(endValue));
			this.endValue = Integer.toString(endValue);
			decreasing = true;
		} else {
			counter = new CLVariable(new StdVariableType(startValue, endValue), counterName);
			counter.setInitValue(StdVariableType.initialize(startValue));
			this.endValue = Integer.toString(endValue);
		}
	}

	@Override
	public boolean hasDeclaration()
	{
		return false;
	}

	@Override
	public Expression getDeclaration()
	{
		return null;
	}

	@Override
	public void accept(VisitorInterface v)
	{
		v.visit(this);
	}

	@Override
	protected String createHeader()
	{
		StringBuilder builder = new StringBuilder("for(");
		builder.append(counter.getDefinition());
		if (decreasing) {
			builder.append(ExpressionGenerator.createBasicExpression(counter, Operator.GT, endValue));
		} else {
			builder.append(ExpressionGenerator.createBasicExpression(counter, Operator.LT, endValue));
		}
		if (decreasing) {
			builder.append("--");
		} else {
			builder.append("++");
		}
		builder.append(counter.varName).append(")");
		return builder.toString();
	}
}