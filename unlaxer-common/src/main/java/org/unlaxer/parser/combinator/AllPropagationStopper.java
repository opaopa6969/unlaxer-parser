package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;

public class AllPropagationStopper extends ConstructedSingleChildParser implements PropagationStopper {

	private static final long serialVersionUID = -303251192158401009L;

	public AllPropagationStopper(Name name, Parser child) {
		super(name, child);
	}

	public AllPropagationStopper(Parser child) {
		super(child);
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		
		parseContext.startParse(this, parseContext, tokenKind, invertMatch);
		Parsed parsed = getChild().parse(parseContext, TokenKind.consumed, false);
		parseContext.endParse(this, parsed , parseContext, tokenKind, invertMatch);
		return parsed;
	}

}