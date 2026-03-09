package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;

public class DoCounsumePropagationStopper extends ConstructedSingleChildParser implements PropagationStopper {

	private static final long serialVersionUID = -8510339130971346858L;

	public DoCounsumePropagationStopper(Name name, Parser children) {
		super(name, children);
	}

	public DoCounsumePropagationStopper(Parser child) {
		super(child);
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		
		parseContext.startParse(this, parseContext, tokenKind, invertMatch);
		Parsed parsed = getChild().parse(parseContext, TokenKind.consumed, invertMatch);
		parseContext.endParse(this, parsed , parseContext, tokenKind, invertMatch);
		return parsed;
	}
}