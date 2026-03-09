package org.unlaxer.parser.combinator;

import org.unlaxer.Name;

public abstract class LazyZeroOrMore extends LazyOccurs{

	private static final long serialVersionUID = -4091762968070560259L;

	public LazyZeroOrMore() {
		super();
	}

	public LazyZeroOrMore(Name name) {
		super(name);
	}
	
	@Override
	public int min() {
		return 0;
	}

	@Override
	public int max() {
		return Integer.MAX_VALUE;
	}

}