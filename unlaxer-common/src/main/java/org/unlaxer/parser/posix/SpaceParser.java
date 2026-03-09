package org.unlaxer.parser.posix;

import org.unlaxer.parser.elementary.MappedSingleCharacterParser;

public class SpaceParser extends MappedSingleCharacterParser {

	private static final long serialVersionUID = 95516864251035105L;

	//http://www.unicode.org/Public/5.2.0/ucd/PropList.txt
	public SpaceParser() {
		super(new char[]{32,9,10,11,12,13});
		//" \t\n\r\f\v"
	}
	
	public final static SpaceParser SINGLETON = new SpaceParser();
	
}