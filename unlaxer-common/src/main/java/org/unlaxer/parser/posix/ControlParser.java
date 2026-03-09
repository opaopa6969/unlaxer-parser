package org.unlaxer.parser.posix;

import org.unlaxer.parser.elementary.MappedSingleCharacterParser;

public class ControlParser extends MappedSingleCharacterParser {

	/**
	 * 
	 */
	private static final long serialVersionUID = -4754812096568092073L;

	public ControlParser() {
		super(new char[] {
			0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,127
		});
	}

	public final static ControlParser SINGLETON = new ControlParser();
}