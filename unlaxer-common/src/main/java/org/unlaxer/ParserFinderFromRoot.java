package org.unlaxer;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

public interface ParserFinderFromRoot extends ParserHierarchy{
	
	public default Optional<Parser> findFirstFromRoot(Predicate<Parser> predicate) {
		return findFromRoot(predicate).findFirst();
	}
	
	public default Stream<Parser> findFromRoot(Predicate<Parser> predicate) {
		//FIXME!
		Parsers flattenOriginal = getRoot().flatten();
		return flattenOriginal.stream()
				.peek(parser->System.out.println(parser.toString()))
				.filter(predicate);
	}
	
}