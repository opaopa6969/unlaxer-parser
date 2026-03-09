package org.unlaxer.parser.ascii;

import org.unlaxer.parser.StaticParser;
import org.unlaxer.parser.elementary.SingleCharacterParser;

public class GreaterThanParser extends SingleCharacterParser implements StaticParser{

	private static final long serialVersionUID = -2193297929999058205L;

	@Override
	public boolean isMatch(char target) {
		return '>' == target;
	}
}