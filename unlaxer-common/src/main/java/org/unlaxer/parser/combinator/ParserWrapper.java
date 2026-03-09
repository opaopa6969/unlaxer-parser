package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;


public class ParserWrapper extends ConstructedSingleChildParser{
	
	private static final long serialVersionUID = 5439333138608297140L;
	public final Parser parser;
	public final TokenKind tokenKind;
	public final boolean invertMatch;

	public ParserWrapper(Parser parser) {
		this(parser,parser.getTokenKind(),false);
	}
	
	public ParserWrapper(Name name , Parser parser) {
		this(name , parser ,parser.getTokenKind(),false);
	}


	public ParserWrapper(Parser parser, TokenKind tokenKind, boolean invertMatch) {
		this(null,parser,tokenKind , invertMatch);
	}
	
	public ParserWrapper(Name name, Parser parser, TokenKind tokenKind, boolean invertMatch) {
		super(name,parser);
		this.parser = parser;
		this.tokenKind = tokenKind;
		this.invertMatch = invertMatch;
	}

	/**
	 * ignore specified parameter tokenKind and invertMatch
	 * @see org.unlaxer.parser.Parser#parse(org.unlaxer.context.ParseContext, TokenKind, boolean)
	 */
	@Override
	@Deprecated 
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		parseContext.startParse(this, parseContext, tokenKind, invertMatch);
		Parsed parsed = parser.parse(parseContext,this.tokenKind,this.invertMatch);
		parseContext.endParse(this, parsed , parseContext, tokenKind, invertMatch);
		return parsed;
		
	}
	
	@Override
	public Parsed parse(ParseContext parseContext) {
		return parse(parseContext,this.tokenKind,this.invertMatch);
	}

	@Override
	public Parser createParser() {
		return this;
	}

	@Override
	public TokenKind getTokenKind() {
		return tokenKind;
	}
}