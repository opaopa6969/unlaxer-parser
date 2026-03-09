package org.unlaxer.parser.posix;

import org.unlaxer.parser.elementary.MappedSingleCharacterParser;

public class XDigitParser extends MappedSingleCharacterParser{
	
	/**
	 * 
	 */
	private static final long serialVersionUID = -7157990959355875128L;

	public XDigitParser(){
		super("0123456789ABCDEFabcdef");
	}
	
	public final static XDigitParser SINGLETON = new XDigitParser();

}