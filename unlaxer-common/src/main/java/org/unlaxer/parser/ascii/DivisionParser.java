package org.unlaxer.parser.ascii;

import org.unlaxer.parser.StaticParser;
import org.unlaxer.parser.elementary.SingleCharacterParser;

public class DivisionParser extends SingleCharacterParser implements StaticParser{

	private static final long serialVersionUID = -1463434347426081506L;
	
	@Override
	public boolean isMatch(char target) {
		return '/' == target; 
	}
	
}