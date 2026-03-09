package org.unlaxer.parser.ascii;

import org.unlaxer.parser.elementary.SingleCharacterParser;

public class PlusParser extends SingleCharacterParser {
	
	private static final long serialVersionUID = -2284625778872306935L;
	
	public static PlusParser SINGLETON = new PlusParser();

	@Override
	public boolean isMatch(char target) {
		return '+' == target; 
	}
}