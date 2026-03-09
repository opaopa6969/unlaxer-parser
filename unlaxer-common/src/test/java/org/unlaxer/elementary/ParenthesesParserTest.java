package org.unlaxer.elementary;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.TestResult;
import org.unlaxer.Token;
import org.unlaxer.parser.elementary.ParenthesesParser;
import org.unlaxer.parser.elementary.WordParser;


public class ParenthesesParserTest extends ParserTestBase{

	@Test
	public void test() {
		
		ParenthesesParser parenthesesParser = new ParenthesesParser(new WordParser("a"));
		
		TestResult result = testAllMatch(parenthesesParser, "(a)");
		assertTrue(result.lastToken.isPresent());
		
		System.out.println(result.lastToken.sourceAsString()); 
		
//		System.out.println(JSON.encode(getResultParsed()));
		
		Token parenthesesed = ParenthesesParser.getParenthesesed(result.parsed.getRootToken());
		System.out.println(parenthesesed.getSource().sourceAsString());
		
		testAllMatch(parenthesesParser, "( a)");
		testAllMatch(parenthesesParser, "(a )");
		result = testAllMatch(parenthesesParser, "(   	a   )");
		
		assertTrue(result.lastToken.isPresent());
		
		
		testUnMatch(parenthesesParser, "(   	    )");
		
	}

}
