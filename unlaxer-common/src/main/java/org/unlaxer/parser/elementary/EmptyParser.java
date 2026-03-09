package org.unlaxer.parser.elementary;

import java.util.Optional;
import java.util.function.Supplier;

import org.unlaxer.Name;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.LazyZeroOrOne;
import org.unlaxer.parser.combinator.MatchOnly;
import org.unlaxer.util.cache.SupplierBoundCache;

public class EmptyParser extends LazyZeroOrOne{

	private static final long serialVersionUID = 2737636685837145192L;
	
	public EmptyParser() {
		super();
	}

	public EmptyParser(Name name) {
		super(name);
	}

	@Override
	public Supplier<Parser> getLazyParser() {
		return new SupplierBoundCache<>(
			()-> new MatchOnly(new WildCardCharacterParser())
		);
	}

	@Override
	public Optional<Parser> getLazyTerminatorParser() {
		return Optional.empty();
	}
	
}