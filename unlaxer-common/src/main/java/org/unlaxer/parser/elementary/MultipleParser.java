package org.unlaxer.parser.elementary;

import org.unlaxer.parser.StaticParser;

public class MultipleParser extends SingleCharacterParser implements StaticParser{

	private static final long serialVersionUID = -5558359079298083248L;
	
	@Override
	public boolean isMatch(char target) {
		return '*' == target; 
	}
	
}