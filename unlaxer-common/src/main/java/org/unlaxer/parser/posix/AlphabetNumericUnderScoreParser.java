package org.unlaxer.parser.posix;

import org.unlaxer.parser.elementary.MappedSingleCharacterParser;

public class AlphabetNumericUnderScoreParser extends MappedSingleCharacterParser {


	private static final long serialVersionUID = 1649791994288064544L;

	public AlphabetNumericUnderScoreParser() {
		super("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_");
	}

	public final static AlphabetNumericUnderScoreParser SINGLETON = new AlphabetNumericUnderScoreParser();

}