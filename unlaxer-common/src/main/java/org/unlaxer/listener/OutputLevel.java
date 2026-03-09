package org.unlaxer.listener;

public enum OutputLevel{
	none,
	simple,
	detail,
	mostDetail,
	withTag,
	
	;
	public boolean isNone(){
		return this == none;
	}
	public boolean isSimple(){
		return this == simple;
	}
	public boolean isDetail(){
		return this == detail;
	}
	public boolean isMostDetail() {
		return this == mostDetail;
	}
	public boolean isWithTag() {
		return this == withTag;
	}

}