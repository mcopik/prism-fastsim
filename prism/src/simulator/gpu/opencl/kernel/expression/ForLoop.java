package simulator.gpu.opencl.kernel.expression;

import java.util.List;

public class ForLoop implements KernelComponent
{

	@Override
	public boolean hasInclude()
	{
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean hasDeclaration()
	{
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public List<Include> getInclude()
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Expression getDeclaration()
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void accept(VisitorInterface v)
	{
		// TODO Auto-generated method stub

	}

	@Override
	public String getSource()
	{
		// TODO Auto-generated method stub
		return null;
	}

}
