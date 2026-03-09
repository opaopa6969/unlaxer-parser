package org.unlaxer.listener;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;

public interface ParserListenerContainer{
	
	public Map<Name, ParserListener> getParserListenerByName();
	
	public default void addParserListener(Name name , ParserListener parserListener){
		getParserListenerByName().put(name, parserListener);
	}
	
	public default Set<Entry<Name, ParserListener>> getParserListeners(){
		return getParserListenerByName().entrySet();
	}
	
	public default ParserListener removeParserListerner(Name name){
		return getParserListenerByName().remove(name);
	}
	
	public default void startParse(Parser parser , ParseContext parseContext , 
			TokenKind tokenKind , boolean invertMatch){
		
		for(Entry<Name , ParserListener> parserListener: parseContext.getParserListeners()){
			parserListener.getValue().onStart(parser, parseContext, tokenKind, invertMatch);
		}
	}
	
	public default void endParse(Parser parser , Parsed parsed , ParseContext parseContext , 
			TokenKind tokenKind , boolean invertMatch){
		
		for(Entry<Name , ParserListener> parserListener: parseContext.getParserListeners()){
			parserListener.getValue()
			.onEnd(parser , parsed ,  parseContext, tokenKind, invertMatch);
		}
	}
	
	public default void postParse(Parser parser , Parsed parsed , ParseContext parseContext) {
		
	}
}