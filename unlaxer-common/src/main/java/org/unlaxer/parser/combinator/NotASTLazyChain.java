package org.unlaxer.parser.combinator;

import java.util.Optional;

import org.unlaxer.Name;
import org.unlaxer.RecursiveMode;

public abstract class NotASTLazyChain extends LazyChain{


	private static final long serialVersionUID = 2119255169367002360L;

	public NotASTLazyChain() {
		super();
	}

	public NotASTLazyChain(Name name) {
		super(name);
	}

	@Override
	public Optional<RecursiveMode> getNotAstNodeSpecifier() {
		return Optional.of(RecursiveMode.containsRoot);
	}
}