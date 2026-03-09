package org.unlaxer.parser.ascii;

import org.unlaxer.parser.StaticParser;
import org.unlaxer.parser.elementary.SingleCharacterParser;

public class LeftParenthesisParser extends SingleCharacterParser implements StaticParser{

	private static final long serialVersionUID = 8740151170115179190L;

	@Override
	public boolean isMatch(char target) {
		return '(' == target; 
	}
	
}