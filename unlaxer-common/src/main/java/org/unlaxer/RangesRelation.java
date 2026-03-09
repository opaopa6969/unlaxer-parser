package org.unlaxer;
public enum RangesRelation{
	equal,//
	outer,//
	inner,//
	crossed,//
	notCrossed,//
	;
	
	public boolean related() {
		return this != notCrossed;
	}
}