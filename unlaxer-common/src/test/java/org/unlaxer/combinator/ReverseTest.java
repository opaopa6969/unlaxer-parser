package org.unlaxer.combinator;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Flatten;
import org.unlaxer.parser.combinator.Reverse;
import org.unlaxer.parser.posix.AlphabetParser;
import org.unlaxer.parser.posix.DigitParser;
import org.unlaxer.parser.posix.PunctuationParser;

public class ReverseTest extends ParserTestBase{

	@Test
	public void test() {
		Chain chain = new Chain(
			new AlphabetParser(),
			new DigitParser(),
			new PunctuationParser()
		);
		
		Reverse reverse = new Reverse(
			new Flatten(chain)
		);
		
		testAllMatch(chain, "a5$");
		testAllMatch(reverse, "$5a");
	}

}
