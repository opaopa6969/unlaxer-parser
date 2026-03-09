package org.unlaxer.parser.posix;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.elementary.MappedSingleCharacterParser;

public class DigitParser extends MappedSingleCharacterParser {
	
	private static final long serialVersionUID = -463617540001438700L;
	
	public DigitParser() {
		this(null);
	}

	public DigitParser(Name name) {
		super(name , "0123456789");
	}

	@Override
	public boolean isMatch(char target) {
		return super.isMatch(target);
	}

	@Override
	public Token getToken(ParseContext parseContext, TokenKind tokenKind,boolean invertMatch) {
		return super.getToken(parseContext, tokenKind,invertMatch);
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		return super.parse(parseContext, tokenKind, invertMatch);
	}
}