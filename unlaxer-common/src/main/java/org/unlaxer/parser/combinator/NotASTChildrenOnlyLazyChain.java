package org.unlaxer.parser.combinator;

import java.util.Optional;

import org.unlaxer.Name;
import org.unlaxer.RecursiveMode;

public abstract class NotASTChildrenOnlyLazyChain extends LazyChain{

	private static final long serialVersionUID = 5394104170334904569L;

	public NotASTChildrenOnlyLazyChain() {
		super();
	}

	public NotASTChildrenOnlyLazyChain(Name name) {
		super(name);
	}

	@Override
	public Optional<RecursiveMode> getNotAstNodeSpecifier() {
		return Optional.of(RecursiveMode.childrenOnly);
	}
}