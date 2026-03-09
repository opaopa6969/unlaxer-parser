package org.unlaxer;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.unlaxer.parser.Parser;

public class ParserNameContext{
	
	Map<Name,Parser> parserByName;

	public ParserNameContext(Parser rootParser) {
		super();
		parserByName = rootParser.flatten().parallelStream()
			.collect(Collectors.toMap(Parser::getName, Function.identity()));
	}
	
	public Optional<Parser> get(Name name){
		return Optional.ofNullable(parserByName.get(name));
	}

}