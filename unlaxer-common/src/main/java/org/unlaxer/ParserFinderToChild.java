package org.unlaxer;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

public interface ParserFinderToChild extends ParserHierarchy{
	
	public default Optional<Parser> findFirstToChild(Predicate<Parser> predicate) {
		return findToChild(predicate).findFirst();
	}
	
	public default Stream<Parser> findToChild(Predicate<Parser> predicate) {
		Parsers flatten = flatten(RecursiveMode.childrenOnly);
		return flatten.stream().filter(predicate);
	}
	
	public default Parsers flatten(){
		return flatten(RecursiveMode.containsRoot);
	}
	
	public default Parsers flatten(RecursiveMode recursiveMode){
		Parsers list = new Parsers();
		if(recursiveMode.isContainsRoot()){
			list.add(getThisParser());
		}
		for(Parser child :getChildren()){
			list.addAll(child.flatten(recursiveMode));
		}
		return list;
	}
}