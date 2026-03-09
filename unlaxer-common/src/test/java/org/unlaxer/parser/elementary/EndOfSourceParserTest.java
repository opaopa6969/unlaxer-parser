package org.unlaxer.parser.elementary;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.combinator.Chain;

public class EndOfSourceParserTest extends ParserTestBase{

	@Test
	public void test() {
		
		setLevel(OutputLevel.detail);
		
		EndOfSourceParser eosParser = new EndOfSourceParser();
		
		
		testAllMatch(eosParser, "");
		testUnMatch(eosParser, " ");
		
		var chainParser = new Chain(new WordParser("abc"),eosParser);
	    testAllMatch(chainParser, "abc");

			
	    var chainParser2 = new Chain(eosParser,new WordParser("abc"));
	    testUnMatch(chainParser2, "abc");

	}
}
