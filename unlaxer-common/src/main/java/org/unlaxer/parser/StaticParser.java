package org.unlaxer.parser;

public interface StaticParser extends Parser{
	
//	public static Parser get(Class<? extends StaticParser> staticParserClass){
//		StaticParser staticParser = Singletons.get(staticParserClass);
//		return new ParserHolder(staticParser);
//	}
//	
//	public static Parser get(Name name , Class<? extends StaticParser> staticParserClass){
//		StaticParser staticParser = Singletons.get(staticParserClass);
//		return new ParserHolder(name , staticParser);
//	}
//	
//	public static MappedSingleCharacterParserHolder getMappedSingleCharacterParser(Class<? extends MappedSingleCharacterParser> staticParserClass){
//		MappedSingleCharacterParser staticParser = Singletons.get(staticParserClass);
//		return new MappedSingleCharacterParserHolder(staticParser);
//	}
//	
//	public static MappedSingleCharacterParserHolder getMappedSingleCharacterParser(Name name , Class<? extends MappedSingleCharacterParser> staticParserClass){
//		MappedSingleCharacterParser staticParser = Singletons.get(staticParserClass);
//		return new MappedSingleCharacterParserHolder(name , staticParser);
//	}
}