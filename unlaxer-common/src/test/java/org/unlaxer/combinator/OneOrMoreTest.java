package org.unlaxer.combinator;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.posix.DigitParser;


public class OneOrMoreTest extends ParserTestBase{

	@Test
	public void test() {
	  
	  setLevel(OutputLevel.detail);
		
		OneOrMore digits = new OneOrMore(DigitParser.class);
		
		testPartialMatch(digits, "123", "123");
		testPartialMatch(digits, "123e", "123");
		testPartialMatch(digits, "1-23e", "1");
		testUnMatch(digits, "");
		testUnMatch(digits, "e");
		testUnMatch(digits, "-1");
	}


}
