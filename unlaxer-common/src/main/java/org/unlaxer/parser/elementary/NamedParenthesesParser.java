package org.unlaxer.parser.elementary;

import org.unlaxer.Name;
import org.unlaxer.Token;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.ascii.LeftParenthesisParser;
import org.unlaxer.parser.ascii.RightParenthesisParser;
import org.unlaxer.parser.combinator.WhiteSpaceDelimitedLazyChain;
import org.unlaxer.util.annotation.TokenExtractor;

public abstract class NamedParenthesesParser extends WhiteSpaceDelimitedLazyChain{
	
	private static final long serialVersionUID = 5506328765442699565L;

	public NamedParenthesesParser(Name name) {
		super(name);
	}
	
	public NamedParenthesesParser() {
		super();
	}

	public abstract Parser nameParser();
	
	public abstract Parser innerParser();
	
	
	@TokenExtractor
	public static Token getInnerParserParsed(Token thisParserParsed) {
		
		// get next token of LeftParenthesisParser
		int childIndexWithParser = thisParserParsed.getChildIndexWithParser(LeftParenthesisParser.class);
		return thisParserParsed.getAstNodeChildren().get(childIndexWithParser+1);
	}
	
	@Override
	public Parsers getLazyParsers() {
		return
			new Parsers(
				nameParser(),
				new LeftParenthesisParser(),
				innerParser(),
				new RightParenthesisParser()
			);

	}
}