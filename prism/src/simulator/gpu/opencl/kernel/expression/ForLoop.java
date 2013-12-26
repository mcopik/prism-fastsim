package simulator.gpu.opencl.kernel.expression;

import java.util.List;

import simulator.gpu.opencl.kernel.memory.CLVariable;
import simulator.gpu.opencl.kernel.memory.StdVariableType;

public class ForLoop extends ComplexKernelComponent
{
	private CLVariable counter;
	private int endValue;
	boolean decreasing = false;

	public ForLoop(String counterName, int startValue, int endValue)
	{
		counter = new CLVariable(new StdVariableType(startValue, endValue), counterName);
		counter.setInitValue(StdVariableType.initialize(startValue));
		this.endValue = endValue;
	}

	public ForLoop(String counterName, int startValue, int endValue, boolean decreasing)
	{
		if (decreasing) {
			counter = new CLVariable(new StdVariableType(endValue, startValue), counterName);
			counter.setInitValue(StdVariableType.initialize(endValue));
			this.endValue = startValue;
			decreasing = true;
		} else {
			counter = new CLVariable(new StdVariableType(startValue, endValue), counterName);
			counter.setInitValue(StdVariableType.initialize(startValue));
			this.endValue = endValue;
		}
	}

	@Override
	public boolean hasInclude()
	{
		return false;
	}

	@Override
	public boolean hasDeclaration()
	{
		return false;
	}

	@Override
	public List<Include> getInclude()
	{
		return null;
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
		builder.append(counter.varName);
		if (decreasing) {
			builder.append(" > ");
		} else {
			builder.append(" < ");
		}
		builder.append(endValue).append(";");
		if (decreasing) {
			builder.append("--");
		} else {
			builder.append("++");
		}
		builder.append(counter.varName).append(")");
		return builder.toString();
	}
}