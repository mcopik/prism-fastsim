/**
 * 
 */
package simulator.gpu.opencl.kernel.expression;

import java.util.HashMap;
import java.util.Map;

import simulator.gpu.opencl.kernel.memory.CLVariable;

/**
 * @author mcopik
 *
 */
public class MemoryTranslatorVisitor implements VisitorInterface
{
	private Map<String, CLVariable> translations = new HashMap<>();

	private String translateString(String str)
	{
		StringBuilder builder = new StringBuilder(str);
		for (Map.Entry<String, CLVariable> entry : translations.entrySet()) {
			int pos = builder.indexOf(entry.getKey());
			while (pos >= 0) {
				builder.replace(pos, pos + entry.getKey().length() - 1, entry.getValue().varName);
				pos = builder.indexOf(entry.getKey());
			}
		}
		return builder.toString();
	}

	public void addTranslation(String prismVariable, CLVariable clVariable)
	{
		translations.put(prismVariable, clVariable);
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
		// TODO Auto-generated method stub

	}

	@Override
	public void visit(ForLoop loop)
	{
		// TODO Auto-generated method stub

	}

}
