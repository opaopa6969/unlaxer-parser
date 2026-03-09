package org.unlaxer.parser.elementary;

import org.junit.Test;
import org.unlaxer.ParserTestBase;

public class WildCardCharacterParserTest extends ParserTestBase{

	@Test
	public void test() {
		
		var parser = new WildCardCharacterParser();
		
		testAllMatch(parser, ":");
		
	}

}
