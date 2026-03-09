package org.unlaxer.parser.combinator;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.TestResult;
import org.unlaxer.Token;
import org.unlaxer.TokenPrinter;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.reducer.TagBasedReducer;

public class WhiteSpaceDelimitedLazyChainTest extends ParserTestBase{

	@Test
	public void test() {
		setLevel(OutputLevel.detail);
		
		WhiteSpaceDelimitedLazyChainTestParser parser = new WhiteSpaceDelimitedLazyChainTestParser();
		testAllMatch(parser, "1234");
		testAllMatch(parser, " 1234 ");
		TestResult testResult = testAllMatch(parser, " 1\n\r2\t 3\r 4 ");
		
		TagBasedReducer tagBasedReducer = new TagBasedReducer();
		Token rootToken = testResult.parsed.getRootToken(tagBasedReducer);
		String string = TokenPrinter.get(rootToken);
		System.out.println(string);
		
	}
	
	public static class WhiteSpaceDelimitedLazyChainTestParser extends WhiteSpaceDelimitedLazyChain{

		private static final long serialVersionUID = -4138533725870179243L;
//		List<Parser> parsers;
		@Override
		public Parsers getLazyParsers() {
			return 
				new Parsers(
					new WordParser("1"),
					new WordParser("2"),
					new WordParser("3"),
					new WordParser("4")
				);
		}
	}

}
