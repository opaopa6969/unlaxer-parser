package org.unlaxer.parser.elementary;

import org.unlaxer.Name;
import org.unlaxer.Token;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.ascii.LeftParenthesisParser;
import org.unlaxer.parser.ascii.RightParenthesisParser;
import org.unlaxer.parser.combinator.WhiteSpaceDelimitedLazyChain;
import org.unlaxer.util.annotation.TokenExtractor;

public class ParenthesesParser extends WhiteSpaceDelimitedLazyChain {

	private static final long serialVersionUID = 6964996290002171327L;
	
	Class<? extends Parser> inner;
	Parser innerParser;
	

	public ParenthesesParser(Name name , Class<? extends Parser> inner) {
		super(name);
		this.inner = inner;
	}


	public ParenthesesParser(Class<? extends Parser> inner) {
		super();
		this.inner = inner;
	}
	
	 public ParenthesesParser(Parser inner) {
	    super();
	    this.innerParser = inner;
  }


	
	@TokenExtractor
	public static Token getParenthesesed(Token parenthesesed ){
		if(false == parenthesesed.parser instanceof ParenthesesParser){
			throw new IllegalArgumentException("this token did not generate from " + 
				ParenthesesParser.class.getName());
		}
		Parser contentsParser = ParenthesesParser.class.cast(parenthesesed.parser).getParenthesesedParser();
		return parenthesesed.getChildWithParser(parser -> parser.equals(contentsParser));
	}
	
	public Parser getParenthesesedParser(){
	  synchronized (this) {
	    if(innerParser == null ) {
	      innerParser = Parser.get(inner);
	    }
    }

		return innerParser;
	}

	@Override
	public Parsers getLazyParsers() {
		return 
			Parsers.of(
				new LeftParenthesisParser(),
				getParenthesesedParser(),
				new RightParenthesisParser()
			);

	}

	@TokenExtractor
	public Token getInnerParserParsed(Token thisParserParsed) {
//		return thisParserParsed.filteredChildren.get(1);
		return thisParserParsed.getChildWithParser(parser->parser.equals(innerParser));
	}
}
