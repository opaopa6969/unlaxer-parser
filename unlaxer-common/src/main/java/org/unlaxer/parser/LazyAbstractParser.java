package org.unlaxer.parser;

import org.unlaxer.Name;

public abstract class LazyAbstractParser extends AbstractParser{

	private static final long serialVersionUID = 8299776236082228019L;

	public LazyAbstractParser() {
		super();
	}

	public LazyAbstractParser(Name name) {
		super(name);
	}
	
	@SuppressWarnings("unused")
	private LazyAbstractParser(Parsers children) {
		super(children);
	}

	@SuppressWarnings("unused")
	private LazyAbstractParser(Name name, Parsers children) {
		super(name, children);
	}
	
	@Override
	public Parser createParser() {
		return this;
	}
}