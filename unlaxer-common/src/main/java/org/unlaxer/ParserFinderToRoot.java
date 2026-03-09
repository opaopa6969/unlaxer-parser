package org.unlaxer;

import java.util.Optional;
import java.util.function.Predicate;

import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.RootParserIndicator;

public interface ParserFinderToRoot extends ParserHierarchy{
	
	//FIXME! move to parseContext ? 
	public static int infiniteBreakCount = 1000;
	
	/**
	 * @param predicate to find match Parser
	 * @return matched parser
	 * if grammar is circulative, implement RootParserIndicator to the parser that effectively root. 
	 */
	public default Optional<Parser> findFirstToParent(Predicate<Parser> predicate) {
		
		int count = infiniteBreakCount;
		Parser current = getThisParser();
		while(true){
			if(count-- ==0){
				throw new IllegalStateException("Infinite loop!!");
			}
			Optional<Parser> parent = current.getParent();
			
			if(parent.isPresent()){
				current = parent.get();
				if(predicate.test(current)){
					return parent;
				}
				if(current instanceof RootParserIndicator){
					return Optional.empty();
				}
				continue;
			}
			return Optional.empty();
		}
	}
	/**
	 * @param predicate to find match Parser
	 * @return matched parsers ordered first parser is near.
	 */
	public default Parsers findParents(Predicate<Parser> predicate) {
		
		return findParents(predicate,false);
	}

	
	/**
	 * @param predicate to find match Parser
	 * @param containCallerParser add first element to callerParser(this parser)
	 * @return matched parsers ordered first parser is near.
	 */
	public default Parsers findParents(
			Predicate<Parser> predicate,boolean containCallerParser) {
		
		Parsers parents = new Parsers();
		if(containCallerParser){
			parents.add(getThisParser());
		}
		
		int count = infiniteBreakCount;
		Parser current = getThisParser();
		while(true){
			//FIXME!
			if(count-- ==0){
				throw new IllegalStateException("Inifinite loop!!");
			}
			Optional<Parser> parent = current.getParent();
			
			if(parent.isPresent()){
				current = parent.get();
				parents.add(current);
				if(predicate.test(current) || current instanceof RootParserIndicator){
					return parents;
				}
				continue;
			}
			return parents;
		}
	}
}