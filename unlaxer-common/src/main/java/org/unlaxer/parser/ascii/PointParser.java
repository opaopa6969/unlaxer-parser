package org.unlaxer.parser.ascii;

import org.unlaxer.parser.StaticParser;
import org.unlaxer.parser.elementary.SingleCharacterParser;

public class PointParser extends SingleCharacterParser implements StaticParser{

	private static final long serialVersionUID = 5906583777968911478L;

	@Override
	public boolean isMatch(char target) {
		return '.' == target; 
	}
	
}