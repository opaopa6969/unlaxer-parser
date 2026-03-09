package org.unlaxer.parser.ascii;

import org.unlaxer.parser.StaticParser;
import org.unlaxer.parser.elementary.SingleCharacterParser;

public class SlashParser extends SingleCharacterParser implements StaticParser{

	private static final long serialVersionUID = -2574794542523548619L;

	@Override
	public boolean isMatch(char target) {
		return '/' == target;
	}
}