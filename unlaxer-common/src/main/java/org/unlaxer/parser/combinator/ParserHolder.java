package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.MetaFunctionParser;
import org.unlaxer.parser.Parser;

public class ParserHolder extends ConstructedSingleChildParser implements MetaFunctionParser{

	private static final long serialVersionUID = -7769486063809426552L;

	public ParserHolder(Name name, Parser child) {
		super(name, child);
	}

	public ParserHolder(Parser child) {
		super(child);
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		return getChild().parse(parseContext);
	}
	
}