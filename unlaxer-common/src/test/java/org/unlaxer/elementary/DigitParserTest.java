package org.unlaxer.elementary;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.posix.DigitParser;


public class DigitParserTest extends ParserTestBase{

	@Test
	public void testAllMatched() {
		
		setLevel(OutputLevel.simple);
		
		Parser parser = new DigitParser();
		
		IntStream.range(0, 10)
			.mapToObj(String::valueOf)
			.forEach(x->testAllMatch(parser,x));
		
		Stream.of("abcdefghijk+-.").forEach(x->testUnMatch(parser, x));
		
	}
	
}
