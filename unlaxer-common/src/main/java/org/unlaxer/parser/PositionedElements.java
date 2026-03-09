package org.unlaxer.parser;

import java.util.List;

import org.unlaxer.RangedContent;

public class PositionedElements<T>{
	
	public final List<RangedContent<T>> elements;

	public PositionedElements(List<RangedContent<T>> elements) {
		super();
		this.elements = elements;
	}
	
}