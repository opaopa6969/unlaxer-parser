package org.unlaxer.elementary;

import org.junit.Test;
import org.unlaxer.CodePointIndex;
import org.unlaxer.ParserTestBase;
import org.unlaxer.parser.elementary.IgnoreCaseWordParser;
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
		    .end(word->new CodePointIndex(word.sourceAsString().indexOf(" ")));
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

	/**
	 * IgnoreCaseWordParser is a public entry-point used for case-insensitive
	 * keyword matching but has no direct test. Pin its equalsIgnoreCase
	 * behaviour across all-upper, mixed-case, and mismatch inputs.
	 */
	@Test
	public void ignoreCaseWordParserMatchesCaseVariants() {
		IgnoreCaseWordParser parser = new IgnoreCaseWordParser("Hello");

		testAllMatch(parser, "HELLO");
		testPartialMatch(parser, "hello world", "hello");
		testUnMatch(parser, "xyz");
	}

	/**
	 * A surrogate-pair word has string length 2 but code-point length 1.
	 * WordParser must match the full code point, not half a surrogate.
	 * Regression-prone because Source equality and peek both operate on
	 * code-point indices (see #94 DAP non-BMP fix).
	 */
	@Test
	public void wordParserMatchesSupplementaryCodePoint() {
		String supplementary = "𝄞";
		WordParser parser = new WordParser(supplementary);

		testAllMatch(parser, supplementary);
		testPartialMatch(parser, "𝄞abc", supplementary);
		testUnMatch(parser, "abc");
	}
}
