package org.unlaxer.combinator;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.combinator.Repeat;
import org.unlaxer.parser.posix.AlphabetParser;

public class RepeatTest extends ParserTestBase{

	@Test
	public void test() {
		
		setLevel(OutputLevel.detail);
		
		Repeat repeat = new Repeat(AlphabetParser.class, 2, 3);
		
		testAllMatch(repeat, "ab");
		testAllMatch(repeat, "abc");
		testPartialMatch(repeat, "abcd","abc");
		
		testUnMatch(repeat, "a");
		testUnMatch(repeat, "");
	}

}
