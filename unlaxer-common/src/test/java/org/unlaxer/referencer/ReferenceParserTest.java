package org.unlaxer.referencer;

import org.junit.Test;
import org.unlaxer.Name;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.elementary.ParenthesesParser;
import org.unlaxer.parser.posix.AlphabetParser;
import org.unlaxer.parser.referencer.ReferenceParser;

public class ReferenceParserTest extends ParserTestBase {

	@Test
	public void testReferenceAtSingleElement() {

		setLevel(OutputLevel.simple);

		Name elementsName = Name.of("closure");
		Chain parser = new Chain(
				new ParenthesesParser(//
					new OneOrMore(elementsName, AlphabetParser.class)
				),
				new ReferenceParser(current -> 
					current.getName().equals(elementsName))
		);

		testAllMatch(parser, "(abc)abc");
		testAllMatch(parser, "(abc)z");
		testUnMatch(parser, "(abc)123");
	}
	
	@Test
	public void testReferenceByNameAtSingleElement() {

		setLevel(OutputLevel.simple);

		Name elementsName = Name.of("closure");
		Chain parser = new Chain(
				new ParenthesesParser(//
					new OneOrMore(elementsName, AlphabetParser.class)
				),
				ReferenceParser.of(elementsName)
		);

		testAllMatch(parser, "(abc)abc");
		testAllMatch(parser, "(abc)z");
		testUnMatch(parser, "(abc)123");
	}

}
