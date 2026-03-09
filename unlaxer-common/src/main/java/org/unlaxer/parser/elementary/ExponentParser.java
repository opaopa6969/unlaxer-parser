package org.unlaxer.parser.elementary;

import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.StaticParser;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.combinator.Optional;
import org.unlaxer.parser.posix.DigitParser;



public class ExponentParser extends LazyChain implements StaticParser{
	
	private static final long serialVersionUID = 4717221978893045863L;
	
	@Override
	public Parsers getLazyParsers(){
		return Parsers.of(
			new EParser(),
			new Optional(
				SignParser.class
			),
			new OneOrMore(
				DigitParser.class
			)
		);
	}

}