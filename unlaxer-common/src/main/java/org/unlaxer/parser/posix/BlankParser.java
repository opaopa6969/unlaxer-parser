package org.unlaxer.parser.posix;

import org.unlaxer.parser.elementary.MappedSingleCharacterParser;

public class BlankParser extends MappedSingleCharacterParser{

	/**
	 * 
	 */
	private static final long serialVersionUID = -4126092802214802542L;

	public BlankParser() {
		super(" \t");
	}
	
	public final static BlankParser SINGLETON = new BlankParser();

}