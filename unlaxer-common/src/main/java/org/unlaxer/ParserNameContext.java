package org.unlaxer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.unlaxer.parser.Parser;

public class ParserNameContext{
	
	Map<Name,Parser> parserByName;

	public ParserNameContext(Parser rootParser) {
		super();
		parserByName = new HashMap<>();
		// One parser reached through several parents is one entry; distinct parsers sharing a
		// name are rejected as before (#845).
		for (Parser parser : rootParser.flatten()) {
			Parser registered = parserByName.putIfAbsent(parser.getName(), parser);
			if (registered != null && registered != parser) {
				throw new IllegalStateException(String.format(
					"Duplicate key %s (attempted merging values %s and %s)",
					parser.getName(), registered, parser));
			}
		}
	}
	
	public Optional<Parser> get(Name name){
		return Optional.ofNullable(parserByName.get(name));
	}

}