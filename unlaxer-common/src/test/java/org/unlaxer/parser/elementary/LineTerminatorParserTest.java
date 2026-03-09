package org.unlaxer.parser.elementary;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.unlaxer.Cursor.EndExclusiveCursor;
import org.unlaxer.ParserCursor;
import org.unlaxer.ParserTestBase;
import org.unlaxer.TestResult;
import org.unlaxer.TokenKind;
import org.unlaxer.parser.combinator.Chain;

public class LineTerminatorParserTest extends ParserTestBase{

	@Test
	public void test() {
		LineTerminatorParser lineTerminatorParser = new LineTerminatorParser();
		WordParser abcParser = new WordParser("abc");

		{
			testUnMatch(lineTerminatorParser, "abc");
			testAllMatch(new Chain(abcParser,lineTerminatorParser), "abc");
			TestResult testPartialMatch = testPartialMatch(new Chain(abcParser,lineTerminatorParser), "abc\ndef","abc\n");
			
			ParserCursor parserCursor = testPartialMatch.parseContext.getCurrent().getParserCursor();
			EndExclusiveCursor consumed = parserCursor.getCursor(TokenKind.consumed);
			EndExclusiveCursor matchOnly= parserCursor.getCursor(TokenKind.matchOnly);
			
			assertEquals(4, consumed.position().value());
      assertEquals(1, consumed.lineNumber().value());
      //commitが行われると、consumedとmatchOnlyは等しくなる
      assertEquals(4, matchOnly.position().value());
      assertEquals(1, matchOnly.lineNumber().value());
		}
		
		{
      TestResult testAllMatch = testAllMatch(
          new Chain(abcParser,lineTerminatorParser), "abc\n");
      
      ParserCursor parserCursor = testAllMatch.parseContext.getCurrent().getParserCursor();
      EndExclusiveCursor consumed = parserCursor.getCursor(TokenKind.consumed);
      assertEquals(1, consumed.lineNumber().value());
      
      //commitが行われると、consumedとmatchOnlyは等しくなる
      EndExclusiveCursor matchOnly= parserCursor.getCursor(TokenKind.matchOnly);
      assertEquals(1, matchOnly.lineNumber().value());

    }
		
		{
      TestResult testAllMatch = testAllMatch(
          new Chain(abcParser), "abc");
      
      ParserCursor parserCursor = testAllMatch.parseContext.getCurrent().getParserCursor();
      EndExclusiveCursor consumed = parserCursor.getCursor(TokenKind.consumed);
      
      assertEquals(0, consumed.lineNumber().value());
      
      //commitが行われると、consumedとmatchOnlyは等しくなる
      EndExclusiveCursor matchOnly= parserCursor.getCursor(TokenKind.matchOnly);
      assertEquals(0, matchOnly.lineNumber().value());

    }
		
		{
			TestResult testAllMatch = testAllMatch(
					new Chain(abcParser,lineTerminatorParser,abcParser,lineTerminatorParser), "abc\nabc");
			
			ParserCursor parserCursor = testAllMatch.parseContext.getCurrent().getParserCursor();
			EndExclusiveCursor consumed = parserCursor.getCursor(TokenKind.consumed);
			parserCursor.getCursor(TokenKind.matchOnly);
			
			assertEquals(1, consumed.lineNumber().value());
			
      //commitが行われると、consumedとmatchOnlyは等しくなる
      EndExclusiveCursor matchOnly= parserCursor.getCursor(TokenKind.matchOnly);
//      assertEquals(0, matchOnly.getLineNumber().value());
      assertEquals(1, matchOnly.lineNumber().value());

		}
		
		{
      TestResult testAllMatch = testAllMatch(
          new Chain(abcParser,lineTerminatorParser,abcParser,lineTerminatorParser,abcParser,lineTerminatorParser),
          "abc\nabc\nabc");
      
      ParserCursor parserCursor = testAllMatch.parseContext.getCurrent().getParserCursor();
      EndExclusiveCursor consumed = parserCursor.getCursor(TokenKind.consumed);
      parserCursor.getCursor(TokenKind.matchOnly);
      
      assertEquals(2, consumed.lineNumber().value());
      
      EndExclusiveCursor matchOnly= parserCursor.getCursor(TokenKind.matchOnly);
      assertEquals(2, matchOnly.lineNumber().value());
    }
		
		{
      TestResult testAllMatch = testAllMatch(
          new Chain(abcParser,lineTerminatorParser,abcParser,lineTerminatorParser
              ,abcParser,lineTerminatorParser,abcParser,lineTerminatorParser),
          "abc\nabc\nabc\nabc");
      
      ParserCursor parserCursor = testAllMatch.parseContext.getCurrent().getParserCursor();
      EndExclusiveCursor consumed = parserCursor.getCursor(TokenKind.consumed);
      parserCursor.getCursor(TokenKind.matchOnly);
      
      assertEquals(3, consumed.lineNumber().value());
      
      //commitが行われると、consumedとmatchOnlyは等しくなる
      EndExclusiveCursor matchOnly= parserCursor.getCursor(TokenKind.matchOnly);
//      assertEquals(3, matchOnly.getLineNumber().value());
      assertEquals(3, matchOnly.lineNumber().value());

    }
		
	}

}
