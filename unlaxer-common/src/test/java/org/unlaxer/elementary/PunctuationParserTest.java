package org.unlaxer.elementary;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.posix.PunctuationParser;

public class PunctuationParserTest extends ParserTestBase{

	@Test
	public void test() {
		Parser punctual = new PunctuationParser();
		
		testAllMatch(punctual, "=");
		testAllMatch(punctual, "&");
		testAllMatch(punctual, "%");
		testUnMatch(punctual, " ");
		testUnMatch(punctual, "1");
		testUnMatch(punctual, "\n");
	}

}
