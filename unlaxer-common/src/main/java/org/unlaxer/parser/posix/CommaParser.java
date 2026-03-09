package org.unlaxer.parser.posix;

import org.unlaxer.parser.elementary.SingleCharacterParser;

public class CommaParser extends SingleCharacterParser{

	private static final long serialVersionUID = 3149819803112637590L;

	@Override
	public boolean isMatch(char target) {
		return ',' == target;
	}

}
