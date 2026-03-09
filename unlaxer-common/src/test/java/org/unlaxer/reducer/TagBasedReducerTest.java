package org.unlaxer.reducer;

import static org.junit.Assert.assertTrue;

import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.ParserTestBase;
import org.unlaxer.TestResult;
import org.unlaxer.Token;
import org.unlaxer.TokenPrinter;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.NotASTNode;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.combinator.Repeat;
import org.unlaxer.parser.posix.AlphabetParser;
import org.unlaxer.parser.posix.DigitParser;
import org.unlaxer.reducer.TagBasedReducer.NodeKind;


public class TagBasedReducerTest extends ParserTestBase{

	@Test
	public void test() {
		
		
		setLevel(OutputLevel.detail);
		
		TagBasedReducer tagBasedReducer = new TagBasedReducer();
		Stream.of(
			new TestUnit("raw" , getRawParser() , token ->token.contains("OneOrMore")),
			new TestUnit("reeduced with tagged" , getParserTaggedWithReduced() ,
					token ->false == token.contains("OneOrMore")),
			new TestUnit("reeduced with tag wrapped" , getParserTagWrappedWithReduced() ,
					token ->false == token.contains("OneOrMore"))
		).forEach(unit->{
			
			TestResult testAllMatch = testAllMatch(unit.parser, "abc1");
			
			Parsed parsed = testAllMatch.parsed;
			Token rootToken = parsed.getRootToken(tagBasedReducer);
			
			String token = TokenPrinter.get(rootToken);
			
			System.out.println(unit.header);
			System.out.println(token);
			
			assertTrue(unit.tokenPredicator.test(token));
		});
	}
	
	public static class TestUnit{
		
		public String header;
		public Parser parser;
		public Predicate<String> tokenPredicator;
		public TestUnit(String header, Parser parser, Predicate<String> tokenPredicator) {
			super();
			this.header = header;
			this.parser = parser;
			this.tokenPredicator = tokenPredicator;
		}
	}
	
	static Parser getRawParser(){
		
		Chain chain = new Chain(
				
			new OneOrMore(AlphabetParser.class),
			new Repeat(DigitParser.class, 1, 2)
		);
		return chain;
	}
	
	static Parser getParserTaggedWithReduced(){
		
		
		Chain chain = new Chain(
				
			new OneOrMore(AlphabetParser.class).addTag(NodeKind.notNode.getTag()),
			new Repeat(DigitParser.class, 1, 2).addTag(NodeKind.notNode.getTag())
		);
		return chain;
	}

	static Parser getParserTagWrappedWithReduced(){
		
		
		Chain chain = new Chain(
			new NotASTNode(new OneOrMore(AlphabetParser.class)),
			new NotASTNode(new Repeat(DigitParser.class, 1, 2))
		);
		return chain;
	}
}
