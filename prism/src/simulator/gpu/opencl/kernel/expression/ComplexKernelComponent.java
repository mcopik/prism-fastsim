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
import simulator.gpu.opencl.kernel.memory.UDType;

public abstract class ComplexKernelComponent implements KernelComponent
{
	protected Map<String, CLVariable> localVars = new HashMap<>();
	protected List<KernelComponent> body = new ArrayList<>();
	protected List<Expression> variableDefinitions = new ArrayList<>();
	protected List<Include> necessaryIncludes = new ArrayList<>();

	//protected boolean sourceHasChanged = false;

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
		updateIncludes(var);
	}

	public CLVariable getLocalVar(String name)
	{
		return localVars.get(name);
	}

	/**
	 * Add expression to component's body
	 * @param expr
	 */
	public void addExpression(KernelComponent expr)
	{
		body.add(expr);
	}

	protected final void updateIncludes(CLVariable var)
	{
		if (var.varType instanceof UDType) {
			List<Include> list = ((UDType) var.varType).getIncludes();
			if (list != null) {
				necessaryIncludes.addAll(list);
			}
		}
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.KernelComponent#hasInclude()
	 */
	@Override
	public final boolean hasIncludes()
	{
		return necessaryIncludes.size() != 0;
	}

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.KernelComponent#hasDeclaration()
	 */
	@Override
	public abstract boolean hasDeclaration();

	/* (non-Javadoc)
	 * @see simulator.gpu.opencl.kernel.expression.KernelComponent#getInclude()
	 */
	@Override
	public final List<Include> getIncludes()
	{
		return necessaryIncludes.size() != 0 ? necessaryIncludes : null;
	}

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