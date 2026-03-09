package org.unlaxer.parser;

import java.util.Arrays;
import java.util.List;

import org.unlaxer.Token;

public class ParsersSpecifier{
	
	public final List<Class<? extends Parser>> parsers;
	
	public ParsersSpecifier(List<Class<? extends Parser>> parsers){
		this.parsers = parsers;
	}
	
	@SafeVarargs
	public ParsersSpecifier(Class<? extends Parser>... parsers){
		this(Arrays.asList(parsers));
	}
	
	public boolean contains(Parser parser){
		return parsers.contains(parser.getClass());
	}
	public boolean contains(Token token){
		return contains(token.parser);
	}
}