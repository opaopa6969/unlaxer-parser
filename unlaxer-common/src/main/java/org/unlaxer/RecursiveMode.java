package org.unlaxer;

public enum RecursiveMode{
	
	containsRoot,childrenOnly;
	
	public boolean isContainsRoot(){
		return this == RecursiveMode.containsRoot;
	}
	
	public boolean isChildrenOnly(){
		return this == RecursiveMode.childrenOnly;
	}

}