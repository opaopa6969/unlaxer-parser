package org.unlaxer.context;

import org.unlaxer.Name;
import org.unlaxer.listener.DebugParserListener;

public class ParserDebugSpecifier implements ParseContextEffector {

	DebugParserListener debugParserListener;
	
	public ParserDebugSpecifier(DebugParserListener debugParserListener) {
		super();
		this.debugParserListener = debugParserListener;
	}
	

	@Override
	public void effect(ParseContext parseContext) {
		parseContext.addParserListener(
				Name.of(ParserDebugSpecifier.class),
				debugParserListener);
	}
}