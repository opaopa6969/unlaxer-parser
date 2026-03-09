package org.unlaxer.parser.posix;

import org.unlaxer.parser.elementary.MappedSingleCharacterParser;

public class AlphabetNumericParser extends MappedSingleCharacterParser {

	private static final long serialVersionUID = 6303389520321244429L;

	public AlphabetNumericParser() {
		super("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789");
	}

	public final static AlphabetNumericParser SINGLETON = new AlphabetNumericParser();

}