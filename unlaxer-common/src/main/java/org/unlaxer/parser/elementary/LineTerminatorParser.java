package org.unlaxer.parser.elementary;

import org.unlaxer.Parsed;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.ChoiceInterface;
import org.unlaxer.parser.combinator.LazyChoice;

public class LineTerminatorParser extends LazyChoice{

	private static final long serialVersionUID = -325480488364751237L;
	static final String LF = new String(new byte[] {0x0a/*lf*/});
	static final String CR = new String(new byte[] {0x0d/*cr*/});
	static final String CRLF = new String(new byte[] {0x0d/*cr*/,0x0a/*lf*/});

	@Override
	public Parsers getLazyParsers() {
		return new Parsers(
			new WordParser(CRLF),
			new WordParser(CR),
			new WordParser(LF),
			Parser.get(EndOfSourceParser.class)
		);
	}
	
	
	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		Parsed parse = super.parse(parseContext, tokenKind, invertMatch);
//		TransactionElement current = parseContext.getCurrent();
//		ParserCursor parserCursor = current.getParserCursor();
//		parserCursor.getCursor(TokenKind.consumed)
//		    .resolveLineNumber(parseContext.source.rootPositionResolver());
//    parserCursor.getCursor(TokenKind.matchOnly)
//      .resolveLineNumber(parseContext.source.rootPositionResolver());
		return parse;
	}
	
	public boolean isEndOfSource(Token thisParserParsed) {
		Token choiced = ChoiceInterface.choiced(thisParserParsed);
		return choiced.parser.getClass() == EndOfSourceParser.class;
	}
}
