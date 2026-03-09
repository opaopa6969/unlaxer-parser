package org.unlaxer.parser.combinator;

import org.unlaxer.Name;

public abstract class LazyRepeat extends LazyOccurs{

	private static final long serialVersionUID = -6811228813551625985L;
	
	public final int minInclusive , maxInclusive;

	public LazyRepeat(int minInclusive, int maxInclusive) {
		this.minInclusive = minInclusive;
		this.maxInclusive = maxInclusive;
	}

	public LazyRepeat(Name name, int minInclusive, int maxInclusive) {
		super(name);
		this.minInclusive = minInclusive;
		this.maxInclusive = maxInclusive;
	}
	
	@Override
	public int min() {
		return minInclusive;
	}

	@Override
	public int max() {
		return maxInclusive;
	}

}