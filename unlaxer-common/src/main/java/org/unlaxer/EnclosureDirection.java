package org.unlaxer;

public enum EnclosureDirection{
	Outer,Inner;
	public boolean isInner(){
		return this == Inner;
	}
	public boolean isOuter(){
		return this == Outer;
	}
}