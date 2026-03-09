package org.unlaxer.elementary;

import org.junit.Test;
import org.unlaxer.CodePointIndex;
import org.unlaxer.ParserTestBase;
import org.unlaxer.parser.elementary.WordParser;

public class WordParserTest extends ParserTestBase{

	@Test
	public void test() {
		
		String source = "This is a pen.";
		WordParser wordParser = new WordParser(source);
		testPartialMatch(wordParser, source, source);
		testUnMatch(wordParser, source.toLowerCase());
		
		WordParser ignoreCaseWordParser = new WordParser(source,true);
		testPartialMatch(ignoreCaseWordParser, source, source);
		testPartialMatch(ignoreCaseWordParser, source.toLowerCase(), source.toLowerCase());

	}
	
	@Test
	public void testSlice() {
		
		String source = "This is a pen.";
		WordParser wordParser = new WordParser(source);
		testPartialMatch(wordParser, source, source);
		testUnMatch(wordParser, source.toLowerCase());
		
		WordParser slice = wordParser.slice(slicer->{slicer
		    .begin(new CodePointIndex(0))
		    .end(word->new CodePointIndex(word.indexOf(" ")));
		});
		testAllMatch(slice, "This");

	}
	
	@Test
	public void testBlockComment() {

		{
			String source = "/*";
			WordParser wordParser = new WordParser(source);
			testAllMatch(wordParser, source);
		}
		
		{
			String source = "*/";
			WordParser wordParser = new WordParser(source);
			testAllMatch(wordParser, source);
		}
		
	}
}
