/**
 * 
 */
package simulator.gpu.opencl.kernel.expression;

import java.util.ArrayList;
import java.util.List;

import prism.Preconditions;

/**
 * @author mcopik
 *
 */
public class IfElse extends ComplexKernelComponent
{
	private static class Condition implements KernelComponent
	{
		public enum Type {
			IF, ELIF, ELSE
		}

		public final Type type;
		public final Expression condition;
		public List<KernelComponent> commands = new ArrayList<>();

		public Condition(Type type, Expression expr)
		{
			this.type = type;
			this.condition = expr;
		}

		public Condition()
		{
			this.type = Type.ELSE;
			this.condition = null;
		}

		@Override
		public boolean hasIncludes()
		{
			return false;
		}

		@Override
		public boolean hasDeclaration()
		{
			return false;
		}

		@Override
		public List<Include> getIncludes()
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
			condition.accept(v);
			for (KernelComponent command : commands) {
				command.accept(v);
			}
		}

		@Override
		public String getSource()
		{
			StringBuilder builder = new StringBuilder();
			switch (type) {
			case IF:
				builder.append("if(").append(condition.getSource()).append("){\n");
				break;
			case ELSE:
				builder.append("else if(").append(condition.getSource()).append("){\n");
				break;
			default:
				builder.append("else {\n");
				break;
			}
			for (KernelComponent command : commands) {
				builder.append(command.getSource()).append("\n");
			}
			builder.append("}");
			return builder.toString();
		}
	}

	private boolean hasElse = false;

	public IfElse(Expression ifCondition)
	{
		body.add(new Condition(Condition.Type.IF, ifCondition));
	}

	public void addElif(Expression condition)
	{
		if (hasElse) {
			body.add(body.size() - 1, new Condition(Condition.Type.ELIF, condition));
		} else {
			body.add(new Condition(Condition.Type.ELIF, condition));
		}
	}

	public void addCommand(int conditionNumber, KernelComponent command)
	{
		Preconditions.checkIndex(conditionNumber, body.size(), "Non-valid index of condition in IfElse!");
		((Condition) body.get(conditionNumber)).commands.add(command);
	}

	public void addElse()
	{
		hasElse = true;
		body.add(new Condition());
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.ComplexKernelComponent#createHeader()
	 */
	@Override
	protected String createHeader()
	{
		return null;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.ComplexKernelComponent#hasDeclaration()
	 */
	@Override
	public boolean hasDeclaration()
	{
		return false;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.ComplexKernelComponent#getDeclaration()
	 */
	@Override
	public Expression getDeclaration()
	{
		return null;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.ComplexKernelComponent#accept(simulator.gpu.opencl.kernel.expression.VisitorInterface)
	 */
	@Override
	public void accept(VisitorInterface v)
	{
		for (KernelComponent component : body) {
			component.accept(v);
		}
	}

	@Override
	public String getSource()
	{
		StringBuilder source = new StringBuilder();
		for (KernelComponent e : body) {
			source.append(e.getSource()).append("\n");
		}
		return source.toString();
	}
}
