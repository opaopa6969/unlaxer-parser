package org.unlaxer.elementary;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.parser.elementary.ParenthesesParser;
import org.unlaxer.parser.posix.DigitParser;

public class ParenthesesTest extends ParserTestBase{

	@Test
	public void test() {
		
		ParenthesesParser parentheses = new ParenthesesParser(DigitParser.class);
		
		testPartialMatch(parentheses, "(1)", "(1)");
		testPartialMatch(parentheses, "(0)", "(0)");
		testUnMatch(parentheses, "()");
		testUnMatch(parentheses, "1+(1)");
		testUnMatch(parentheses, "(a)");
		testUnMatch(parentheses, "(1+1)");

	}

}
