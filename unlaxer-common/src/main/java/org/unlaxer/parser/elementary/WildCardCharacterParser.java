package org.unlaxer.parser.elementary;

import org.unlaxer.Name;

public class WildCardCharacterParser extends SingleCharacterParser{

	private static final long serialVersionUID = -234033222971607063L;
	
	public WildCardCharacterParser() {
		super();
	}

	public WildCardCharacterParser(Name name) {
		super(name);
	}

	@Override
	public boolean isMatch(char target) {
		return true;
	}
	
}