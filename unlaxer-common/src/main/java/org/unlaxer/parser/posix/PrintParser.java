package org.unlaxer.parser.posix;

import org.unlaxer.Range;
import org.unlaxer.parser.elementary.MappedSingleCharacterParser;

public class PrintParser extends MappedSingleCharacterParser{
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 1278351823732612131L;

	public PrintParser() {
		super(new Range(32,126));
	}
	
	public final static PrintParser SINGLETON = new PrintParser();
}