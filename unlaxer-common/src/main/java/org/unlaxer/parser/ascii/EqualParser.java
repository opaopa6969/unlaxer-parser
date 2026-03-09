package org.unlaxer.parser.ascii;

import org.unlaxer.parser.StaticParser;
import org.unlaxer.parser.elementary.SingleCharacterParser;

public class EqualParser extends SingleCharacterParser implements StaticParser{

	private static final long serialVersionUID = -1329782245393294896L;

	@Override
	public boolean isMatch(char target) {
		return '=' == target;
	}
}