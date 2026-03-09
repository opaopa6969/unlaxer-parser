package org.unlaxer.parser.posix;

import org.unlaxer.Name;
import org.unlaxer.parser.elementary.MappedSingleCharacterParser;

public class AlphabetParser extends MappedSingleCharacterParser {

	private static final long serialVersionUID = -4498138530298998607L;
	
	public AlphabetParser() {
		this(null);
	}
	
	public AlphabetParser(Name name) {
		super("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz");
	}


}