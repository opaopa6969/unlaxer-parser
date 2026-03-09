package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.CollectingParser;
import org.unlaxer.parser.ConstructedAbstractParser;
import org.unlaxer.parser.Parsers;

public abstract class ConstructedOccurs extends ConstructedAbstractParser implements Occurs , CollectingParser{

	private static final long serialVersionUID = 7296599133826276749L;

	public ConstructedOccurs(Parsers children) {
		super(children);
	}

	public ConstructedOccurs(Name name, Parsers children) {
		super(name, children);
	}

	
	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		return Occurs.super.parse(parseContext, tokenKind, invertMatch);
	}
}