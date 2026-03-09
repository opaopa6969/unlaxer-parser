package org.unlaxer;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

public interface ParserPath extends ParserFinder{
	
	public default String getParentPath() {
		return getPath(false);
	}
	
	public default String getPath() {
		return getPath(true);
	}
	
	public default String getPath(boolean containCallerParser) {
		
		return "/" + getPathStream(containCallerParser)
		.map(Parser::getName)
		.map(Name::getSimpleName)
		.collect(Collectors.joining("/"));
	}
	
	
	public default List<Name> getNamePath(){
		return getNamePath(NameKind.specifiedName);
	}
	
	public default List<Name> getParentNamePath(){
		return getParentNamePath(NameKind.specifiedName);
	}
	
	public default List<Name> getNamePath(NameKind nameKind) {
		
		return getNamePath(nameKind , true);
	}
	
	public default List<Name> getParentNamePath(NameKind nameKind) {
		
		return getNamePath(nameKind,false);
	}
	
	public default List<Name> getNamePath(NameKind nameKind , boolean containCallerParser) {
		return getPathStream(containCallerParser)
				.map(nameKind.isSpecifiedName() ? Parser::getName : Parser::getComputedName)
				.collect(Collectors.toList());
	}
	
	public default Stream<Parser> getPathStream(boolean containCallerParser){
		Parsers retrieveParents = findParents(Parser.isRoot,containCallerParser);
		Collections.reverse(retrieveParents);
		
		return retrieveParents.stream();
	}
}