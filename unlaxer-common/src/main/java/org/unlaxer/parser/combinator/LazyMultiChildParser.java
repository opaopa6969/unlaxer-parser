package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.HasChildrenParser;
import org.unlaxer.parser.LazyAbstractParser;

public abstract class LazyMultiChildParser extends LazyAbstractParser implements HasChildrenParser{

	
	private static final long serialVersionUID = 4805554348169130621L;

	public LazyMultiChildParser() {
		super();
	}

	public LazyMultiChildParser(Name name) {
		super(name);
	}

	@Override
	public ChildOccurs getChildOccurs() {
		return ChildOccurs.multi;
	}
}