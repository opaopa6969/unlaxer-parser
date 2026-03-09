package org.unlaxer.elementary;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.elementary.EscapeInQuotedParser;

public class EscapeInQuotedParserTest extends ParserTestBase{

	@Test
	public void test() {
	  
    setLevel(OutputLevel.detail);
    
		EscapeInQuotedParser escapeInQuotedParser = new EscapeInQuotedParser();
		
		testAllMatch(escapeInQuotedParser, "\\b");
		testAllMatch(escapeInQuotedParser, "\\\\");
		testAllMatch(escapeInQuotedParser, "\\\"");
		
		testUnMatch(escapeInQuotedParser, "\\");
		testUnMatch(escapeInQuotedParser, "b\\");
	}

}
