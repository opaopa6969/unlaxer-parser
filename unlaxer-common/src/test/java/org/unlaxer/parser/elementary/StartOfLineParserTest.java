package org.unlaxer.parser.elementary;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.combinator.Chain;

public class StartOfLineParserTest extends ParserTestBase{

	@Test
	public void test() {
		setLevel(OutputLevel.mostDetail);

		LineTerminatorParser lineTerminatorParser = new LineTerminatorParser();
		StartOfLineParser startOfLineParser = new StartOfLineParser();
		WordParser wordParser = new WordParser("abc");
		EndOfSourceParser endOfSourceParser = new EndOfSourceParser();
		{
			Chain chain = new Chain(wordParser, lineTerminatorParser,startOfLineParser , wordParser,endOfSourceParser);
			testAllMatch(chain, "abc\nabc");
		}
		{
			Chain chain = new Chain(startOfLineParser , wordParser,endOfSourceParser);
			testAllMatch(chain, "abc");
		}
		{
			Chain chain = new Chain( wordParser,startOfLineParser);
			testUnMatch(chain, "abc");
		}
		{
			Chain chain = new Chain(lineTerminatorParser,startOfLineParser , wordParser,endOfSourceParser);
			testAllMatch(chain, "\nabc");
		}

	}

}
