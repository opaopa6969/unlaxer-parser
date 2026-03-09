package org.unlaxer.parser.clang;

import org.unlaxer.Name;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.posix.AlphabetNumericUnderScoreParser;
import org.unlaxer.parser.posix.AlphabetUnderScoreParser;

public class IdentifierParser extends LazyChain {

	private static final long serialVersionUID = 1456799104515530397L;

	public IdentifierParser() {
		super();
	}

	public IdentifierParser(Name name) {
		super(name);
	}
	
	@Override
	public Parsers getLazyParsers() {
	  return
	      new Parsers(
	        Parser.get(AlphabetUnderScoreParser.class),
	        new ZeroOrMore(
	          AlphabetNumericUnderScoreParser.class
	        )
      );
	}

}