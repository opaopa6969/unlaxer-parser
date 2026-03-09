package org.unlaxer.parser.combinator;

import java.util.Optional;

import org.unlaxer.Name;
import org.unlaxer.RecursiveMode;

public abstract class NotASTLazyChoice extends LazyChoice{

	private static final long serialVersionUID = 2350099853249851488L;

	public NotASTLazyChoice() {
		super();
	}

	public NotASTLazyChoice(Name name) {
		super(name);
	}
	
	
	@Override
	public Optional<RecursiveMode> getNotAstNodeSpecifier() {
		return Optional.of(RecursiveMode.containsRoot);
	}
}