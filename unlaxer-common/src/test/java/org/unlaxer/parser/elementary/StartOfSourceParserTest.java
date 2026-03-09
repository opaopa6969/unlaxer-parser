package org.unlaxer.parser.elementary;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.combinator.Chain;

public class StartOfSourceParserTest extends ParserTestBase{

	@Test
	public void test() {
		
		setLevel(OutputLevel.mostDetail);
		
		StartOfSourceParser startOfSourceParser = new StartOfSourceParser();
		WordParser wordParser = new WordParser("abc");
		EndOfSourceParser endOfSourceParser = new EndOfSourceParser();
		{
			Chain chain = new Chain(startOfSourceParser , wordParser,endOfSourceParser);
			testAllMatch(chain, "abc");
		}
		{
			Chain chain = new Chain( wordParser,startOfSourceParser);
			testUnMatch(chain, "abc");
		}
		{
			Chain chain = new Chain( wordParser,endOfSourceParser);
			testAllMatch(chain, "abc");
		}
		
		{
			Chain chain = new Chain( endOfSourceParser , wordParser);
			testUnMatch(chain, "abc");
		}
	}

}
