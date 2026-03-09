package org.unlaxer.parser.combinator;

import java.util.Optional;

import org.unlaxer.Name;
import org.unlaxer.RecursiveMode;

public abstract class NotASTChildrenOnlyLazyChoice extends LazyChoice{

	private static final long serialVersionUID = -778542274328850589L;

	public NotASTChildrenOnlyLazyChoice() {
		super();
	}

	public NotASTChildrenOnlyLazyChoice(Name name) {
		super(name);
	}
	
	
	@Override
	public Optional<RecursiveMode> getNotAstNodeSpecifier() {
		return Optional.of(RecursiveMode.childrenOnly);
	}
}