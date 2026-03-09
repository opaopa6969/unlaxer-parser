package org.unlaxer.elementary;


import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.EmptyParser;
import org.unlaxer.parser.elementary.NumberParser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.AlphabetParser;

public class EmptyParserTest extends ParserTestBase{

	@Test
	public void test() {
		
		setLevel(OutputLevel.detail);

		// accepts a=1; or b=;
		Chain chain = new Chain(
			new AlphabetParser(),
			new WordParser("="),
			new Choice(
				NumberParser.class,
				EmptyParser.class
			),
			new WordParser(";")
		);
		
		
		
		testAllMatch(chain, "a=1;");
		testAllMatch(chain, "a=;");
	}
	
	@Test
	public void testMatchOnly() {
		
		setLevel(OutputLevel.detail);

		EmptyParser emptyParser = new EmptyParser();
		
		testSucceededOnly(emptyParser, "a=1;");
		
		Chain chain = new Chain(
			new EmptyParser(),
			new AlphabetParser()
		);
		
		testAllMatch(chain, "a");
	}
	
	@Test
	public void testInfinite() {
		
		setLevel(OutputLevel.detail);

		ZeroOrMore zeroOrMore = new ZeroOrMore(EmptyParser.class);
		
		testSucceededOnly(zeroOrMore, "");
		
	}
}
