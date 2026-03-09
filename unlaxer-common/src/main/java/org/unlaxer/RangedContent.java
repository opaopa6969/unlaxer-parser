package org.unlaxer;

public interface RangedContent<T>{
	public CursorRange getRange();
	public T getContent();
}