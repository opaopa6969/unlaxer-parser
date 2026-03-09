package org.unlaxer.elementary;

import java.io.IOException;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.elementary.NumberParser;

public class NumberParserTest extends ParserTestBase{

	
	@Test
	public void test() throws IOException {
		
		ParserTestBase.setLevel(OutputLevel.simple);
		
		NumberParser number = new NumberParser();

		testPartialMatch(number, ".0", ".0");
		testPartialMatch(number, "+123.456", "+123.456");
		testPartialMatch(number, "-.456", "-.456");
		testPartialMatch(number, "123.", "123.");
		testAllMatch(number, "123.");
		testPartialMatch(number, "123.", "123.");
		testPartialMatch(number, ".456", ".456");
		testPartialMatch(number, "-.0", "-.0" );
		testPartialMatch(number, "-.0e10", "-.0e10" );
		testPartialMatch(number, "1.23e5", "1.23e5" );
		testPartialMatch(number, "1.23e-5", "1.23e-5" );
		
		// remain is ".4" . have to lookahead binary operator ? 
		testPartialMatch(number, "123..4", "123." );
		testPartialMatch(number, "1.23e-5.4", "1.23e-5" );
		
		testUnMatch(number, "." );
		testUnMatch(number, "-" );
		testUnMatch(number, "-." );
	}

}
