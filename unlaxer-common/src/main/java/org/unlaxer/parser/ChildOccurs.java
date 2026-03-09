package org.unlaxer.parser;

public enum ChildOccurs{
	none,
	single,
	multi,
	;
	public boolean isNone(){
		return this == none;
	}
	public boolean isSingle(){
		return this == single;
	}
	public boolean isMulti(){
		return this == multi;
	}
}