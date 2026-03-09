package org.unlaxer;

import java.util.function.Predicate;

import org.unlaxer.parser.Parser;

public class TokenPredicators{
	
	public final static Predicate<Token> parserImplements(Class<?>... interfaceClasses){
		return token->{
			for (Class<?> interfaceClass : interfaceClasses) {
				
				if(interfaceClass.isAssignableFrom(token.parser.getClass())) {
					return true;
				}
			}
			return false;
		};
	}
	
	@SafeVarargs
	public final static Predicate<Token> parsers(Class<? extends Parser>... parserClasses){
		return token->{
			for (Class<? extends Parser> parserClass : parserClasses) {
				
				if(token.parser.getClass() == parserClass) {
					return true;
				}
			}
			return false;
		};
	}
	
	public final static Predicate<Token> parsersMatchWithClass(Parser... parsers){
		return token->{
			for (Parser parser: parsers) {
				
				if(token.parser.getClass() == parser.getClass()) {
					return true;
				}
			}
			return false;
		};
	}
	
	public final static Predicate<Token> noMatch(){
		return token-> false;
	}

	public final static Predicate<Token> allMatch(){
		return token-> true;
	}
	
	public final static Predicate<Token> afterToken(Token targetToken){
		return token-> targetToken.getSource().cursorRange()
		    .lessThan(token.getSource().cursorRange());
	}
	
	public final static Predicate<Token> beforeToken(Token targetToken){
		return token-> targetToken.getSource().cursorRange()
		    .graterThan(token.getSource().cursorRange());
	}
	
	public final static Predicate<Token> relation(Token targetToken , RangesRelation rangesRelation){
		return token-> targetToken.getSource().cursorRange()
		    .relation(token.getSource().cursorRange()) == rangesRelation;
	}
	
	public final static Predicate<Token> hasTag(Tag tag){
		return token-> token.parser.hasTag(tag);
	}
	
	public final static Predicate<Token> hasTagInParent(Tag tag){
		return token-> token.parser.getParent()
				.map(_parent->_parent.hasTag(tag)).orElse(false);
	}
	
	public final static Predicate<Token> pathEndsWith(String path){
		return token-> token.parser.getPath().endsWith(path);
	}
	
	public final static Predicate<Token> parentPathEndsWith(String path){
		return token-> token.parser.getParentPath().endsWith(path);
	}
}