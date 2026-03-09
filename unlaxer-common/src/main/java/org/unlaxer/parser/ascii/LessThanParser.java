package org.unlaxer.parser.ascii;

import org.unlaxer.parser.StaticParser;
import org.unlaxer.parser.elementary.SingleCharacterParser;

public class LessThanParser extends SingleCharacterParser implements StaticParser{

	private static final long serialVersionUID = 2503501374564076013L;

	@Override
	public boolean isMatch(char target) {
		return '<' == target;
	}
}