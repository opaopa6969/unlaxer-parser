package org.unlaxer.parser.posix;

import org.unlaxer.parser.elementary.MappedSingleCharacterParser;

public class LowerParser extends MappedSingleCharacterParser{
	
	/**
	 * 
	 */
	private static final long serialVersionUID = -6892753972894812616L;

	public LowerParser() {
		super("abcdefghijklmnopqrstuvwxyz");
	}
	
	public final static LowerParser SINGLETON = new LowerParser();
}