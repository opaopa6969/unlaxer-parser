package org.unlaxer.parser.posix;

import org.unlaxer.parser.elementary.MappedSingleCharacterParser;

public class WordParser extends MappedSingleCharacterParser {

	private static final long serialVersionUID = -3800965382577159876L;

	public WordParser() {
		super("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_");
	}

	public final static WordParser SINGLETON = new WordParser();

}