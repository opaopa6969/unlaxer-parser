package org.unlaxer.elementary;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.ExponentParser;

public class ExponentParserTest extends ParserTestBase{

	@Test
	public void test() {
	  
	  setLevel(OutputLevel.detail);

		Parser exponentParser = new ExponentParser();
		testPartialMatch(exponentParser, "e+1", "e+1");
		testPartialMatch(exponentParser, "e+1", "e+1");
		testPartialMatch(exponentParser, "E-10", "E-10");
		testPartialMatch(exponentParser, "E10", "E10");
		testPartialMatch(exponentParser, "e1.1" , "e1");  // remain is ".1"
		testUnMatch(exponentParser, "E");
		testUnMatch(exponentParser, "e");
		testUnMatch(exponentParser, "e/1");
		testUnMatch(exponentParser, "ea");
		testUnMatch(exponentParser, "e(1)");
	}

}
