package org.unlaxer.combinator;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.combinator.Optional;
import org.unlaxer.parser.posix.DigitParser;


public class OptionalTest extends ParserTestBase{

	@Test
	public void test() {
		
		setLevel(OutputLevel.simple);
		
		Optional digits = new Optional(DigitParser.class);
		
		testPartialMatch(digits, "123", "1");
		testPartialMatch(digits, "123e", "1");
		testPartialMatch(digits, "1-23e", "1");
		testPartialMatch(digits, "1", "1");
		testSucceededOnly(digits, "");
		testSucceededOnly(digits, "e");
		testSucceededOnly(digits, "-1");
	}

}
