package org.unlaxer.parser.posix;

import org.unlaxer.parser.elementary.SingleCharacterParser;

public class ColonParser extends SingleCharacterParser{

	@Override
	public boolean isMatch(char target) {
		return ':' == target;
	}
}