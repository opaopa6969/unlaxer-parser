package org.unlaxer.combinator;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.combinator.Repeat;
import org.unlaxer.parser.posix.AlphabetParser;

public class RepeatTest extends ParserTestBase{

	@Test
	public void test() {
		
		setLevel(OutputLevel.detail);
		
		Repeat repeat = new Repeat(AlphabetParser.class, 2, 3);
		
		testAllMatch(repeat, "ab");
		testAllMatch(repeat, "abc");
		testPartialMatch(repeat, "abcd","abc");
		
		testUnMatch(repeat, "a");
		testUnMatch(repeat, "");
	}

	/**
	 * Repeat(child, 0, 0) commits only when the child matches zero times.
	 * A non-empty input where the child succeeds even once exceeds max=0
	 * and must fail; an empty input where the child fails commits at 0.
	 * This pins the Occurs commit condition `matchCount >= min && matchCount <= max`.
	 */
	@Test
	public void zeroZeroRejectsAnyMatchButAcceptsEmpty() {
		Repeat zeroZero = new Repeat(AlphabetParser.class, 0, 0);

		testUnMatch(zeroZero, "a");
		testAllMatch(zeroZero, "");
	}

	/**
	 * min > max can never satisfy the commit condition, so it must always
	 * fail regardless of input. This pins the behaviour that Repeat does
	 * not validate min<=max at construction; callers relying on this
	 * invariant would regress if the constructor started throwing.
	 */
	@Test
	public void minGreaterThanMaxAlwaysFails() {
		Repeat minGreaterThanMax = new Repeat(AlphabetParser.class, 3, 2);

		testUnMatch(minGreaterThanMax, "abc");
		testUnMatch(minGreaterThanMax, "");
	}

	/**
	 * Exactly-one boundary: succeeds on a single match, fails on zero and
	 * on two. Guards the `matchCount >= max()` break in Occurs.
	 */
	@Test
	public void exactlyOneBoundary() {
		Repeat exactlyOne = new Repeat(AlphabetParser.class, 1, 1);

		testAllMatch(exactlyOne, "a");
		testUnMatch(exactlyOne, "");
		testPartialMatch(exactlyOne, "ab", "a");
	}

}
