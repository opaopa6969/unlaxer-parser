package org.unlaxer.parser;

import org.unlaxer.Name;

public abstract class ConstructedAbstractParser extends AbstractParser{

	private static final long serialVersionUID = 37196026907568384L;


	public ConstructedAbstractParser(Parsers children) {
		super(children);
	}

	public ConstructedAbstractParser(Name name, Parsers children) {
		super(name, children);
	}
	
	@SuppressWarnings("unused")
	private ConstructedAbstractParser() {
		super();
	}

	@SuppressWarnings("unused")
	private ConstructedAbstractParser(Name name) {
		super(name);
	}

	@Override
	public void prepareChildren(Parsers childrenContainer) {
	}

	@Override
	public Parser createParser() {
		return this;
	}
}