package org.unlaxer.elementary;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.ChainInterface;
import org.unlaxer.parser.elementary.SpaceDelimitor;
import org.unlaxer.parser.posix.AlphabetParser;

public class SpaceDelimitorTest extends ParserTestBase{

	@Test
	public void test() {
		
		ChainInterface chain = new Chain(
			new AlphabetParser(),
			new SpaceDelimitor()
		);
		
		testAllMatch(chain, "a");
		testAllMatch(chain, "a  ");
		
		
	}

}
