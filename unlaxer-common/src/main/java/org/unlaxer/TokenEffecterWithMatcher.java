package org.unlaxer;

import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public class TokenEffecterWithMatcher{
	public final Predicate<Token> target;
	public final UnaryOperator<Token> effector;
	public TokenEffecterWithMatcher(Predicate<Token> target, UnaryOperator<Token> effector) {
		super();
		this.target = target;
		this.effector = effector;
	}
}