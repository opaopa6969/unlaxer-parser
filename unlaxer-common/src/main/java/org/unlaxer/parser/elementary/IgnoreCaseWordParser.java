package org.unlaxer.parser.elementary;

public class IgnoreCaseWordParser extends WordParser{

	private static final long serialVersionUID = 8429015551046858469L;

	public IgnoreCaseWordParser(String word) {
		super(word, true);
	}
}