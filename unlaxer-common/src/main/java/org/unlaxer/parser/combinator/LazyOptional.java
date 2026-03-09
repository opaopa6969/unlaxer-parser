package org.unlaxer.parser.combinator;

import org.unlaxer.Name;

public abstract class LazyOptional extends LazyOccurs {

	private static final long serialVersionUID = 1668458089750488800L;

	public LazyOptional() {
		super();
	}

	public LazyOptional(Name name) {
		super(name);
	}
	
	@Override
	public int min() {
		return 0;
	}

	@Override
	public int max() {
		return 1;
	}

}