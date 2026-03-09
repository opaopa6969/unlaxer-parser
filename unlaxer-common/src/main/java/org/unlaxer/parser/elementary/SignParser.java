package org.unlaxer.parser.elementary;

import org.unlaxer.parser.StaticParser;

public class SignParser extends SingleCharacterParser implements StaticParser{

	private static final long serialVersionUID = 8677618647419615180L;

	@Override
	public boolean isMatch(char target) {
		return '-' == target || '+'== target;
	}
}