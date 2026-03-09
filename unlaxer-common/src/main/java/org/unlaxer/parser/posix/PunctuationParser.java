package org.unlaxer.parser.posix;

import org.unlaxer.parser.elementary.MappedSingleCharacterParser;

public class PunctuationParser extends MappedSingleCharacterParser {

	private static final long serialVersionUID = -7842848328375562225L;

	// TODO separate puncture and symbols
	public PunctuationParser() {
		super("!\"#$%&'()*+,\\-./:;<=>?@[]^_`{|}~");
	}
	
	
}
