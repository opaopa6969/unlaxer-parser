package org.unlaxer.parser.posix;

import org.unlaxer.parser.elementary.SingleCharacterParser;

public class DotParser extends SingleCharacterParser{

	private static final long serialVersionUID = -1755095320796248636L;

	@Override
	public boolean isMatch(char target) {
		return '.' == target;
	}
}
