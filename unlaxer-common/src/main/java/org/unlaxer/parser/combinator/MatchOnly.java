package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.MetaFunctionParser;
import org.unlaxer.parser.Parser;

public class MatchOnly extends ConstructedSingleChildParser implements MetaFunctionParser {

	private static final long serialVersionUID = 3691720461774778800L;

	public MatchOnly(Name name, Parser child) {
		super(name, child);
	}

	public MatchOnly(Parser child) {
		super(child);
	}

	@Override
	public Parsed parse(ParseContext parseContext) {
		return parse(parseContext, TokenKind.matchOnly, false);
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind,boolean invertMatch) {
		
		return parseContext.parseMatchOnly(this, getChild(), tokenKind, invertMatch);
	}

	@Override
	public TokenKind getTokenKind() {
		return TokenKind.matchOnly;
	}

}
