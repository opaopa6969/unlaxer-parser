package org.unlaxer.elementary;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.ParserTestBase;
import org.unlaxer.Range;
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

	@Test
	public void invertedSetMatchesNonAsciiBmp() {
		MappedSingleCharacterParser notA = new MappedSingleCharacterParser(true, "a");
		testSucceededOnly(notA, "È");
		testSucceededOnly(notA, "Ω");
		testSucceededOnly(notA, "あ");
	}

	@Test
	public void invertedSetRejectsListedChar() {
		MappedSingleCharacterParser notA = new MappedSingleCharacterParser(true, "a");
		testUnMatch(notA, "a");
	}

	@Test
	public void nonInvertedSetRejectsNonAsciiBmp() {
		MappedSingleCharacterParser onlyA = new MappedSingleCharacterParser(false, "a");
		testUnMatch(onlyA, "È");
	}

	@Test
	public void invertedSetMatchesSupplementaryCodePoint() {
		MappedSingleCharacterParser notA = new MappedSingleCharacterParser(true, "a");
		Parsed parsed = parse(notA, "𝄞");
		assertTrue(parsed.isSucceeded());
	}

	@Test
	public void nonInvertedSetRejectsSupplementaryCodePoint() {
		MappedSingleCharacterParser onlyA = new MappedSingleCharacterParser(false, "a");
		Parsed parsed = parse(onlyA, "𝄞");
		assertFalse(parsed.isSucceeded());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsCharacter128() {
		new MappedSingleCharacterParser((char) 128);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsRangeEndingAt128() {
		new MappedSingleCharacterParser(new Range(0, 128));
	}

}
