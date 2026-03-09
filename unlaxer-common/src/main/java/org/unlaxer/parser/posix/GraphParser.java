package org.unlaxer.parser.posix;

import org.unlaxer.Range;
import org.unlaxer.parser.elementary.MappedSingleCharacterParser;

public class GraphParser extends MappedSingleCharacterParser{
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 7173456257650407336L;

	public GraphParser() {
		super(new Range(33,126));
	}
	
	public final static GraphParser SINGLETON = new GraphParser();
}