package org.unlaxer.listener;

import java.util.Arrays;
import java.util.List;

import org.unlaxer.Name;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseContextEffector;

public class ParserListenerSpecifier implements ParseContextEffector{
	
	List<ParserListener> parserListeners;		
	public ParserListenerSpecifier(List<ParserListener> parserListeners) {
		this.parserListeners = parserListeners;
	}
	
	public ParserListenerSpecifier(ParserListener... parserListeners) {
		this.parserListeners = Arrays.asList(parserListeners);
	}
	
	@Override
	public void effect(ParseContext parseContext) {
		for(ParserListener parserListener : parserListeners){
			parseContext.getParserListenerByName().put(
				Name.of(parserListener.toString()), //
				parserListener);
		}
	}
}