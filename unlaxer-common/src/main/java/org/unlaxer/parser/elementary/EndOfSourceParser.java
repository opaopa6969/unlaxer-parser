package org.unlaxer.parser.elementary;

import java.util.Optional;
import java.util.function.Supplier;

import org.unlaxer.Name;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.LazyZero;
import org.unlaxer.util.cache.SupplierBoundCache;

public class EndOfSourceParser extends LazyZero{

	private static final long serialVersionUID = 1020414075231270023L;

	public EndOfSourceParser() {
		super();
	}

	public EndOfSourceParser(Name name) {
		super(name);
	}

	@Override
	public Supplier<Parser> getLazyParser() {
		return new SupplierBoundCache<>(WildCardCharacterParser::new);
	}

	@Override
	public Optional<Parser> getLazyTerminatorParser() {
		return Optional.empty();
	}
	
}