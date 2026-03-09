package org.unlaxer.parser.ascii;

import org.unlaxer.parser.StaticParser;
import org.unlaxer.parser.elementary.SingleCharacterParser;

public class RightParenthesisParser extends SingleCharacterParser implements StaticParser{

	private static final long serialVersionUID = 5790819271761672337L;

	@Override
	public boolean isMatch(char target) {
		return ')' == target; 
	}
	
}