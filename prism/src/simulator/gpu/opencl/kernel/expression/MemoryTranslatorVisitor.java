/**
 * 
 */
package simulator.gpu.opencl.kernel.expression;

import simulator.gpu.opencl.kernel.memory.CLVariable;
import simulator.gpu.opencl.kernel.memory.StructureType;

/**
 * @author mcopik
 *
 */
public class MemoryTranslatorVisitor implements VisitorInterface
{
	private CLVariable stateVector = null;

	public MemoryTranslatorVisitor(CLVariable sv)
	{
		stateVector = sv;
	}

	private String translateString(String str)
	{
		StringBuilder builder = new StringBuilder(str);
		StructureType structure = (StructureType) stateVector.varType;
		for (CLVariable var : structure.getFields()) {
			int pos = builder.indexOf(var.varName);
			CLVariable newVar = ExpressionGenerator.accessStructureField(stateVector, var.varName);
			while (pos >= 0) {
				builder.replace(pos, pos + var.varName.length() - 1, newVar.varName);
				pos = builder.indexOf(var.varName);
			}
		}
		return builder.toString();
	}

	public void setStateVector(CLVariable var)
	{
		stateVector = var;
	}

	@Override
	public void visit(Include include)
	{
		// TODO Auto-generated method stub

	}

	@Override
	public void visit(Expression expr)
	{
		expr.exprString = translateString(expr.exprString);
	}

	@Override
	public void visit(Method method)
	{

	}

	@Override
	public void visit(ForLoop loop)
	{
		// TODO Auto-generated method stub

	}

}
