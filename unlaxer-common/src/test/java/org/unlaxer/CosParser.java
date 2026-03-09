package org.unlaxer;

import org.unlaxer.parser.SuggestableParser;

public class CosParser extends SuggestableParser {

	private static final long serialVersionUID = 5850124685962927999L;

	public CosParser() {
		super(true, "cos");
	}
		
	@Override
	public String getSuggestString(String matchedString) {
		return "(".concat(matchedString).concat(")");
	}
}