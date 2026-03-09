package org.unlaxer.combinator;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.TokenKind;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.combinator.MatchOnly;
import org.unlaxer.parser.combinator.ParserWrapper;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.MappedSingleCharacterParser;
import org.unlaxer.parser.posix.AlphabetNumericParser;
import org.unlaxer.parser.posix.DigitParser;


public class ZeroOrMoreTest extends ParserTestBase{

	@Test
	public void test() {
		
		ZeroOrMore digits = new ZeroOrMore(DigitParser.class);
		testSucceededOnly(digits, "");
		testPartialMatch(digits, "123", "123");
		testPartialMatch(digits, "123e", "123");
		testPartialMatch(digits, "1-23e", "1");
		
	}
	
	@Test
	public void testTerminator() {
		
		setLevel(OutputLevel.simple);

		{
			ZeroOrMore terminatored = //
					new ZeroOrMore(//
						AlphabetNumericParser.class//
					).newWithTerminator(
						new ParserWrapper(//
							new MappedSingleCharacterParser("Z")
						)
					);
			testPartialMatch(terminatored, "abcdefghiZjklikmn", "abcdefghiZ");
		}
		{
			ZeroOrMore terminatored = //
					new ZeroOrMore(//
						AlphabetNumericParser.class//
					).newWithTerminator(
						new ParserWrapper(//
							new MappedSingleCharacterParser("Z"),TokenKind.matchOnly,false
						)
					);
			testPartialMatch(terminatored, "aZcdefghiZjklikmn", "a");
		}
		{
			ZeroOrMore terminatored = //
					new ZeroOrMore(//
						AlphabetNumericParser.class//
					).newWithTerminator(
						new MatchOnly(//
							new  MappedSingleCharacterParser("Z")
						)
					);
			testPartialMatch(terminatored, "aZcdefghiZjklikmn", "a");
		}
		{
			ZeroOrMore terminatored = //
					new ZeroOrMore(//
						AlphabetNumericParser.class//
					).newWithTerminator(
						new MappedSingleCharacterParser("Z")
					);
			testPartialMatch(terminatored, "ZcdefghiZjklikmn", "Z");
		}
		{
			ZeroOrMore terminatored = //
					new ZeroOrMore(//
						AlphabetNumericParser.class//
					).newWithTerminator(
						new MatchOnly(//
							new  MappedSingleCharacterParser("Z")
						)
					);
			testSucceededOnly(terminatored, "ZcdefghiZjklikmn");
		}
	}

}
