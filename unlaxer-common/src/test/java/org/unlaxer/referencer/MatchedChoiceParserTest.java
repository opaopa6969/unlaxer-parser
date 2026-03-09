package org.unlaxer.referencer;

import org.junit.Test;
import org.unlaxer.Name;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.elementary.ParenthesesParser;
import org.unlaxer.parser.posix.AlphabetParser;
import org.unlaxer.parser.posix.DigitParser;
import org.unlaxer.parser.posix.PunctuationParser;
import org.unlaxer.parser.referencer.MatchedChoiceParser;

public class MatchedChoiceParserTest extends ParserTestBase{

	@Test
	public void testReferenceChoicedElement() {
		
		setLevel(OutputLevel.simple);
		
		Name elementsName = Name.of("choosable");
		Chain parser = new Chain(
			new ParenthesesParser(//
				new Choice(elementsName,//
					new OneOrMore(AlphabetParser.class),//
					new OneOrMore(DigitParser.class),//
					// punctuation contains '(' and ')' -> excludes '()'//
					new OneOrMore(//
						new PunctuationParser().newWithout("()")//
					)
				)
			),
			new MatchedChoiceParser(current-> 
				current.getName().equals(elementsName)
			)
		);

		testAllMatch(parser, "(abc)abc");
		testAllMatch(parser, "(abc)z");
		testAllMatch(parser, "(123)9");
		testAllMatch(parser, "(%)&%");
		testUnMatch(parser, "(abc)123");
		testUnMatch(parser, "(abc)%");
		testUnMatch(parser, "(&)123");
	}

}
