package org.unlaxer.parser.combinator;

import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.Tag;
import org.unlaxer.Token;
import org.unlaxer.TokenPredicators;
import org.unlaxer.TokenPrinter;
import org.unlaxer.context.ParseContext;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.WordParser;

public class ConditionalParserTest {
	
	@Test
	public void test() {
		
		WordParser aParser = new WordParser("a");
		WordParser bParser = new WordParser("b");
		WordParser cParser = new WordParser("c");
		
		Tag abChoice = Tag.of("ab-Coice");
		
		
		Chain chain = new Chain(
			new Choice(
				aParser,
				bParser
			).addTag(abChoice),
			new Chain(
				cParser,
				new Choice(
						aParser,
						bParser
				).addTag(abChoice)
			)
		);
		
		ParseContext parseContext = new ParseContext(StringSource.createRootSource("aca"));
		
		Parsed parse = chain.parse(parseContext);
		Token rootToken = parse.getRootToken();
		String string = TokenPrinter.get(rootToken , OutputLevel.detail);
		System.out.println(parse.status);
		System.out.println(string);
		
		Stream<Token> children = rootToken.flatten().stream().filter(TokenPredicators.hasTag(abChoice));
		List<Parser> collect = children
			.map(ChoiceInterface::choiced)
			.map(Token::getParser)
			.collect(Collectors.toList());
		
		for (Parser parser : collect) {
			System.err.println(parser);
		}
	}
	
	@Test
	public void testPredicateAnyMatchForParsedParser() {

		Tag aTag = new Tag("a");
		Tag bTag = new Tag("b");
		
		Parser aParser = new WordParser("a").addTag(aTag);
		Parser bParser = new WordParser("b").addTag(bTag);
		Parser cParser = new WordParser("c");
		
		Tag abChoice = Tag.of("ab-Coice");
		
		
		
		Chain chain = new Chain(
			new Choice(
				aParser,
				bParser
			).addTag(abChoice),
			new Chain(
				cParser,
				new Choice(
						aParser,
						bParser
				).addTag(abChoice)
			)
		);
		
		var predicateAnyMatchForParsedParser = new PredicateAnyMatchForParsedParser(
			chain , 
			TokenPredicators.hasTag(aTag)
		);
		
		{
			ParseContext parseContext = new ParseContext(StringSource.createRootSource("bcb"));
			
			Parsed parse = predicateAnyMatchForParsedParser.parse(parseContext);
			Token rootToken = parse.getRootToken();
			String string = TokenPrinter.get(rootToken , OutputLevel.detail);
			System.out.println(parse.status);
			System.out.println(string);
			assertTrue(parse.isFailed());
		
		}

		{
			ParseContext parseContext = new ParseContext(StringSource.createRootSource("aca"));
			
			Parsed parse = predicateAnyMatchForParsedParser.parse(parseContext);
			Token rootToken = parse.getRootToken();
			String string = TokenPrinter.get(rootToken , OutputLevel.detail);
			System.out.println(parse.status);
			System.out.println(string);
			assertTrue(parse.isSucceeded());
		
		}
		
		{
			ParseContext parseContext = new ParseContext(StringSource.createRootSource("bcb"));
			
			Parsed parse = chain.parse(parseContext);
			Token rootToken = parse.getRootToken();
			String string = TokenPrinter.get(rootToken , OutputLevel.detail);
			System.out.println(parse.status);
			System.out.println(string);
			assertTrue(parse.isSucceeded());
		
		}
	}
	
	@Test
	public void testPredicateAnyMatchForParsedParserWithApplyOnlyChoiced() {

		Tag aTag = new Tag("a");
		Tag bTag = new Tag("b");
		
		Supplier<Parser> aParser = ()-> new WordParser("a").addTag(aTag);
		Parser bParser = new WordParser("b").addTag(bTag);
		Parser cParser = new WordParser("c");
		
		Tag abChoice = Tag.of("ab-Choice");
		
		
		
//		Chain chain = new Chain(
//			new Choice(
//				aParser,
//				bParser
//			).addTag(abChoice),
//			new Chain(
//				cParser,
//				new Choice(
//						aParser,
//						bParser
//				).addTag(abChoice),
//				aParser
//			)
//		);
		// ↑　この実装だとaParserのparentがChainになる。（上書きされて最後のChainが親になるという事
		
		Chain chain = new Chain(
				new Choice(
					aParser.get(),
					bParser
				).addTag(abChoice),
				new Chain(
					cParser,
					new Choice(
							aParser.get(),
							bParser
					).addTag(abChoice),
					aParser.get()
				)
			);

		
		var predicateAnyMatchForParsedParser = new PredicateAnyMatchForParsedParser(
			chain , 
			TokenPredicators.hasTagInParent(abChoice)
				.and(TokenPredicators.hasTag(aTag))
		);
		
		{
			ParseContext parseContext = new ParseContext(StringSource.createRootSource("acaa"));
			
			Parsed parse = predicateAnyMatchForParsedParser.parse(parseContext);
			Token rootToken = parse.getRootToken();
			String string = TokenPrinter.get(rootToken , OutputLevel.detail);
			System.out.println(parse.status);
			System.out.println(string);
			assertTrue(parse.isSucceeded());
		
		}
		
		{
			ParseContext parseContext = new ParseContext(StringSource.createRootSource("bcba"));
			
			Parsed parse = predicateAnyMatchForParsedParser.parse(parseContext);
			Token rootToken = parse.getRootToken();
			String string = TokenPrinter.get(rootToken , OutputLevel.detail);
			System.out.println(parse.status);
			System.out.println(string);
			assertTrue(parse.isFailed());
		
		}

		{
			ParseContext parseContext = new ParseContext(StringSource.createRootSource("bcaa"));
			
			Parsed parse = chain.parse(parseContext);
			Token rootToken = parse.getRootToken();
			String string = TokenPrinter.get(rootToken , OutputLevel.detail);
			System.out.println(parse.status);
			System.out.println(string);
			assertTrue(parse.isSucceeded());
		
		}
	}
	
	@Test
	public void testPredicateAnyMatchForParsedParserWithParserPath() {

		Tag aTag = new Tag("a");
		Tag bTag = new Tag("b");
		
		Supplier<Parser> aParser = ()-> new WordParser("a").addTag(aTag);
		Parser bParser = new WordParser("b").addTag(bTag);
		Parser cParser = new WordParser("c");
		
		Tag abChoice = Tag.of("ab-Choice");
		
		System.out.println(cParser.getPath());
		
//		Chain chain = new Chain(
//			new Choice(
//				aParser,
//				bParser
//			).addTag(abChoice),
//			new Chain(
//				cParser,
//				new Choice(
//						aParser,
//						bParser
//				).addTag(abChoice),
//				aParser
//			)
//		);
		// ↑　この実装だとaParserのparentがChainになる。（上書きされて最後のChainが親になるという事
		
		Chain chain = new Chain(
				new Choice(
					aParser.get(),
					bParser
				).addTag(abChoice),
				new Chain(
					cParser,
					new Choice(
							aParser.get(),
							bParser
					).addTag(abChoice),
					aParser.get()
				)
			);

		System.out.println(cParser.getPath());
		System.out.println(cParser.getParentPath());
		System.out.println(cParser.getParentPath());
		
		var predicateAnyMatchForParsedParser = new PredicateAnyMatchForParsedParser(
			chain , 
			 TokenPredicators.parentPathEndsWith("Choice")
				.and(TokenPredicators.hasTag(aTag))
		);
		
		{
			ParseContext parseContext = new ParseContext(StringSource.createRootSource("acaa"));
			
			Parsed parse = predicateAnyMatchForParsedParser.parse(parseContext);
			Token rootToken = parse.getRootToken();
			String string = TokenPrinter.get(rootToken , OutputLevel.detail);
			System.out.println(parse.status);
			System.out.println(string);
			assertTrue(parse.isSucceeded());
		
		}
		
		{
			ParseContext parseContext = new ParseContext(StringSource.createRootSource("bcba"));
			
			Parsed parse = predicateAnyMatchForParsedParser.parse(parseContext);
			Token rootToken = parse.getRootToken();
			String string = TokenPrinter.get(rootToken , OutputLevel.detail);
			System.out.println(parse.status);
			System.out.println(string);
			assertTrue(parse.isFailed());
		
		}

		{
			ParseContext parseContext = new ParseContext(StringSource.createRootSource("bcaa"));
			
			Parsed parse = chain.parse(parseContext);
			Token rootToken = parse.getRootToken();
			String string = TokenPrinter.get(rootToken , OutputLevel.detail);
			System.out.println(parse.status);
			System.out.println(string);
			assertTrue(parse.isSucceeded());
		
		}
	}
}
