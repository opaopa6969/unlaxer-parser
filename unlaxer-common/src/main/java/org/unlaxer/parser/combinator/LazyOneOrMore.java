package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.parser.Parser;

public abstract class LazyOneOrMore extends LazyOccurs {

	private static final long serialVersionUID = -546174676814665330L;

	public LazyOneOrMore() {
		super();
	}

	public LazyOneOrMore(Name name) {
		super(name);
	}
	
	@Override
	public int min() {
		return 1;
	}

	@Override
	public int max() {
		return Integer.MAX_VALUE;
	}


	@Override
	public Parser createParser() {
		return this;
	}
	
	

}