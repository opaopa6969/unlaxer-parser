package org.unlaxer.parser.elementary;

import java.util.Optional;
import java.util.function.Supplier;

import org.unlaxer.parser.Parser;
import org.unlaxer.parser.StaticParser;
import org.unlaxer.parser.combinator.LazyZeroOrMore;
import org.unlaxer.parser.posix.SpaceParser;
import org.unlaxer.util.cache.SupplierBoundCache;

public class SpaceDelimitor extends LazyZeroOrMore implements StaticParser{
	
	private static final long serialVersionUID = 2334485577013129855L;

	public SpaceDelimitor() {
		super();
	}

	@Override
	public Supplier<Parser> getLazyParser() {
		return new SupplierBoundCache<>(SpaceParser::new);
	}

	@Override
	public Optional<Parser> getLazyTerminatorParser() {
		return Optional.empty();
	}
}