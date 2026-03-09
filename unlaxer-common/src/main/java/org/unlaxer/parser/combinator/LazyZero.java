package org.unlaxer.parser.combinator;

import org.unlaxer.Name;

public abstract class LazyZero extends LazyOccurs{

	private static final long serialVersionUID = 4663586368647043291L;

	public LazyZero() {
		super();
	}

	public LazyZero(Name name) {
		super(name);
	}
	
	@Override
	public int min() {
		return 0;
	}

	@Override
	public int max() {
		return 0;
	}

}