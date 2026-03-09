package org.unlaxer.parser;

public interface PropagatableSource extends Parser{
	
	public boolean computeInvertMatch(boolean fromParentValue, boolean thisSourceValue);
	
	public boolean getInvertMatchToChild();
	
	public void setComputedInvertMatch(boolean computedInvertMatch);
	
	public boolean getThisInvertedSourceValue();
}