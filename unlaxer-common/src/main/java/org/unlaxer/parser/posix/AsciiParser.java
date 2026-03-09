package org.unlaxer.parser.posix;

import org.unlaxer.Range;
import org.unlaxer.parser.elementary.MappedSingleCharacterParser;

public class AsciiParser extends MappedSingleCharacterParser  {
	
	private static final long serialVersionUID = 2467932651767929962L;

	public AsciiParser() {
		super(new Range(0,127));
	}
	
	public final static AsciiParser SINGLETON = new AsciiParser();
}