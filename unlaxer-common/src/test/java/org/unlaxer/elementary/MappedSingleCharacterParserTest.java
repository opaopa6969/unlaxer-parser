package org.unlaxer.elementary;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.elementary.MappedSingleCharacterParser;
import org.unlaxer.parser.posix.PunctuationParser;

public class MappedSingleCharacterParserTest extends ParserTestBase {

	@Test
	public void testExcludes() {
		MappedSingleCharacterParser parser = new PunctuationParser().newWithout("()");
		OneOrMore oneOrMore = new OneOrMore(parser);

		testAllMatch(oneOrMore, "$%&");
		testPartialMatch(oneOrMore, "$%(&", "$%");
		testUnMatch(oneOrMore, "()");

	}

}
