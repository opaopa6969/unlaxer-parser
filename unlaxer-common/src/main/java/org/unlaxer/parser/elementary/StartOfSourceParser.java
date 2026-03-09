package org.unlaxer.parser.elementary;

import org.unlaxer.CodePointIndex;
import org.unlaxer.Parsed;
import org.unlaxer.Parsed.Status;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.AbstractParser;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

public class StartOfSourceParser extends AbstractParser{

	private static final long serialVersionUID = 4101094340196856988L;

	@Override
	public void prepareChildren(Parsers childrenContainer) {
	}

	@Override
	public ChildOccurs getChildOccurs() {
		return ChildOccurs.none;
	}

	@Override
	public Parser createParser() {
		return this;
	}
	
	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		
		CodePointIndex position = parseContext.getPosition(tokenKind);
		
		boolean match = position.isZero() ^ invertMatch;
		return new Parsed(match ? Status.succeeded : Status.failed);
	}
}