package org.unlaxer.combinator;


import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.parser.combinator.NonOrdered;
import org.unlaxer.parser.elementary.MappedSingleCharacterParser;

public class NonOrderedTest extends ParserTestBase{

	@Test
	public void test() {
		
		
		NonOrdered nonOrdered = new NonOrdered(
				
				new MappedSingleCharacterParser("a"),
				new MappedSingleCharacterParser("b"),
				new MappedSingleCharacterParser("c")
		);
		
		testAllMatch(nonOrdered, "abc");
		testAllMatch(nonOrdered, "bca");
		testAllMatch(nonOrdered, "cab");
		testPartialMatch(nonOrdered, "abcc","abc");
		testUnMatch(nonOrdered, "azc");
		testUnMatch(nonOrdered, "bc");
	}

}
