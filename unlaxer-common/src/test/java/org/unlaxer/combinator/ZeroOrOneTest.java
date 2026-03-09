package org.unlaxer.combinator;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.combinator.ZeroOrOne;
import org.unlaxer.parser.posix.AlphabetParser;

public class ZeroOrOneTest extends ParserTestBase{

	@Test
	public void test() {
		
		setLevel(OutputLevel.detail);
		
		ZeroOrOne zeroOrOne = new ZeroOrOne(AlphabetParser.class);
		
		testAllMatch(zeroOrOne, "");
		testAllMatch(zeroOrOne, "a");
		testPartialMatch(zeroOrOne, "ab","a");
		testPartialMatch(zeroOrOne, "abc","a");
	}

}
