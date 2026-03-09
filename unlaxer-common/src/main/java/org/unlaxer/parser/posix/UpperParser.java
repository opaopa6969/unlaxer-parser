package org.unlaxer.parser.posix;

import org.unlaxer.parser.elementary.MappedSingleCharacterParser;

public class UpperParser extends MappedSingleCharacterParser{
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 1159476721895377082L;

	public UpperParser() {
		super("ABCDEFGHIJKLMNOPQRSTUVWXYZ");
	}
	
	public final static UpperParser SINGLETON = new UpperParser();
}