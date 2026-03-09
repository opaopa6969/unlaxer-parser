package org.unlaxer.parser.posix;

import org.unlaxer.parser.elementary.SingleCharacterParser;

public class HashParser extends SingleCharacterParser{

	private static final long serialVersionUID = -7528108429026690672L;

	@Override
	public boolean isMatch(char target) {
		return '#' == target;
	}

}