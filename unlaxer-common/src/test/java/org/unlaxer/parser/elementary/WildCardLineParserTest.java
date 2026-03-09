package org.unlaxer.parser.elementary;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.TestResult;
import org.unlaxer.Token;
import org.unlaxer.TokenList;
import org.unlaxer.parser.combinator.OneOrMore;

public class WildCardLineParserTest extends ParserTestBase{

	@Test
	public void test() {
		OneOrMore oneOrMore = new OneOrMore(WildCardLineParser.class);
		String text=
				"\n"+
				"  asda \n"+
				"#asda \n"+
				"\n"+
				"asdsad \n";
		TestResult testAllMatch = testAllMatch(oneOrMore, text);
		TokenList filteredChildren = testAllMatch.parsed.getRootToken().filteredChildren;
		for (Token token : filteredChildren) {
			System.out.print(token.getParser());
			System.out.println(":"+token.getSource().sourceToStgring());
		}

		assertEquals(6, filteredChildren.size());
		
	}

}
