package org.unlaxer;

import org.unlaxer.parser.SuggestableParser;

/**
 * this parser is sample for suggest. 
 * if you want to see applicative implementation , calculator Project org.unlaxer.sample.calc.parser.function.SinParser
 */
public class SinParser extends SuggestableParser {

	private static final long serialVersionUID = 5911697205587011643L;

	public SinParser() {
		super(true, "sin");
	}
		
	@Override
	public String getSuggestString(String matchedString) {
		return "(".concat(matchedString).concat(")");
	}

}