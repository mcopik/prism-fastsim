/**
 * 
 */
package simulator.gpu.opencl.kernel.expression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import simulator.gpu.opencl.kernel.KernelException;
import simulator.gpu.opencl.kernel.memory.CLVariable;

public abstract class ComplexKernelComponent implements KernelComponent
{
	protected Map<String, CLVariable> localVars = new HashMap<>();
	protected List<KernelComponent> body = new ArrayList<>();
	protected List<Expression> variableDefinitions = new ArrayList<>();
	protected boolean sourceHasChanged = false;

	protected abstract String createHeader();

	/**
	 * Adds local variable in scope.
	 * @param var new variable
	 * @throws KernelException thrown when there exist variable with this same name
	 */
	public void addLocalVar(CLVariable var) throws KernelException
	{
		if (localVars.containsKey(var.varName)) {
			throw new KernelException("Variable " + var.varName + " already exists!");
		}
		localVars.put(var.varName, var);
		variableDefinitions.add(var.getDefinition());
	}

	/**
	 * Add expression to component's body
	 * @param expr
	 */
	public void addExpression(KernelComponent expr)
	{
		body.add(expr);
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.KernelComponent#hasInclude()
	 */
	@Override
	public abstract boolean hasInclude();

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.KernelComponent#hasDeclaration()
	 */
	@Override
	public abstract boolean hasDeclaration();

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.KernelComponent#getInclude()
	 */
	@Override
	public abstract List<Include> getInclude();

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.KernelComponent#getDeclaration()
	 */
	@Override
	public abstract Expression getDeclaration();

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.KernelComponent#accept(simulator.gpu.opencl.kernel.expression.VisitorInterface)
	 */
	@Override
	public abstract void accept(VisitorInterface v);

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.KernelComponent#getSource()
	 */
	@Override
	public String getSource()
	{
		StringBuilder source = new StringBuilder(createHeader());
		source.append("{\n");
		for (Expression e : variableDefinitions) {
			source.append(e.exprString).append("\n");
		}
		for (KernelComponent e : body) {
			source.append(e.getSource()).append("\n");
		}
		source.append("\n}");
		return source.toString();
	}
}