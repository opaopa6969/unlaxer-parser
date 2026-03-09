package org.unlaxer;

import org.unlaxer.parser.SuggestableParser;

public class SqrtParser extends SuggestableParser {

	private static final long serialVersionUID = -6097760458963414195L;

	public SqrtParser() {
		super(true, "sqrt");
	}
		
	@Override
	public String getSuggestString(String matchedString) {
		return "(".concat(matchedString).concat(")");
	}

}