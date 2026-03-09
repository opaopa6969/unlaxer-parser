package org.unlaxer.parser.elementary;

import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.LazyChain;

public class WildCardLineParser extends LazyChain{

	@Override
	public Parsers getLazyParsers() {
	    return new Parsers(
	        Parser.get(StartOfLineParser.class),
	        new WildCardStringTerninatorParser(
	    		true ,
	    		Parser.get(LineTerminatorParser.class)
	        ),
	        Parser.get(LineTerminatorParser.class)
	    );
	}
}