package org.unlaxer.combinator;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.combinator.Zero;
import org.unlaxer.parser.elementary.WildCardCharacterParser;

public class EmptyTest extends ParserTestBase{
	
	@Test
	public void testEmpty(){
		
		setLevel(OutputLevel.detail);
		
		Zero emptyParser = new Zero(WildCardCharacterParser.class);
		
		testAllMatch(emptyParser, "");
		testUnMatch(emptyParser, " ");
	}

}
