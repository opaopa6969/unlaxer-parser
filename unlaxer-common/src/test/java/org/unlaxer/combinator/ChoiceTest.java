package org.unlaxer.combinator;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.ChoiceInterface;
import org.unlaxer.parser.elementary.SignParser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.DigitParser;



public class ChoiceTest extends ParserTestBase{

	@Test
	public void test() {
		ChoiceInterface digitOrSign = new Choice(
			DigitParser.class,
			SignParser.class
		);
		
		testPartialMatch(digitOrSign, "+1", "+");
		testPartialMatch(digitOrSign, "1+1", "1");
		testPartialMatch(digitOrSign, "+-", "+");
		testPartialMatch(digitOrSign, "11", "1");
		testUnMatch(digitOrSign, "*1+1");
	}
	
	
	@Test
	public void choiceWord() {
		ChoiceInterface choice = new Choice(
			new Chain(new WordParser("abc"),new WordParser("xyz")),
			new WordParser("ax")
		);
		
		testAllMatch(choice, "ax");
	}

}
