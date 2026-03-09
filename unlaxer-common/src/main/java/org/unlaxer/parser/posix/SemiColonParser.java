package org.unlaxer.parser.posix;

import org.unlaxer.parser.elementary.SingleCharacterParser;

public class SemiColonParser extends SingleCharacterParser{

	@Override
	public boolean isMatch(char target) {
		return ';' == target;
	}
}