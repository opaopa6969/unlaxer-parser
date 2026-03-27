package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;

/**
 * Forces child parser to use {@link TokenKind#consumed} while preserving
 * the {@code invertMatch} parameter from the parent.
 * <p>
 * Stops TokenKind propagation only; invertMatch passes through.
 *
 * @see AllPropagationStopper
 * @see InvertMatchPropagationStopper
 */
public class DoConsumePropagationStopper extends ConstructedSingleChildParser implements PropagationStopper {

	private static final long serialVersionUID = -8510339130971346858L;

	public DoConsumePropagationStopper(Name name, Parser children) {
		super(name, children);
	}

	public DoConsumePropagationStopper(Parser child) {
		super(child);
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		parseContext.startParse(this, parseContext, tokenKind, invertMatch);
		Parsed parsed = getChild().parse(parseContext, TokenKind.consumed, invertMatch);
		parseContext.endParse(this, parsed, parseContext, tokenKind, invertMatch);
		return parsed;
	}
}
