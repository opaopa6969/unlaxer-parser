package org.unlaxer.parser.elementary;

import org.unlaxer.parser.StaticParser;

public class EParser extends SingleCharacterParser implements StaticParser{
	
	private static final long serialVersionUID = -8184767306548745685L;
	
	@Override
	public boolean isMatch(char target) {
		return target =='e' || target== 'E';
	}
}