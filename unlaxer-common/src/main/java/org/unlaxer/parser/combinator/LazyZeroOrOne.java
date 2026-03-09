package org.unlaxer.parser.combinator;

import org.unlaxer.Name;

public abstract class LazyZeroOrOne extends LazyOptional{

	private static final long serialVersionUID = -3196891695328999199L;

	public LazyZeroOrOne() {
		super();
	}

	public LazyZeroOrOne(Name name) {
		super(name);
	}
}