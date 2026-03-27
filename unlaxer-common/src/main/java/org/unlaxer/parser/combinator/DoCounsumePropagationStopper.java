package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;

/**
 * @deprecated Typo in class name. Use {@link DoConsumePropagationStopper} instead.
 */
@Deprecated(since = "2.7.0", forRemoval = true)
public class DoCounsumePropagationStopper extends DoConsumePropagationStopper {

	private static final long serialVersionUID = -8510339130971346858L;

	public DoCounsumePropagationStopper(Name name, Parser children) {
		super(name, children);
	}

	public DoCounsumePropagationStopper(Parser child) {
		super(child);
	}

}