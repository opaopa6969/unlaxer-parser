package org.unlaxer.parser.combinator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.unlaxer.ErrorMessage;
import org.unlaxer.Parsed;
import org.unlaxer.ParserTestBase;
import org.unlaxer.RangedContent;
import org.unlaxer.TestResult;
import org.unlaxer.Token;
import org.unlaxer.TokenPrinter;
import org.unlaxer.parser.ErrorMessageParser;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.posix.SemiColonParser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.DigitParser;

/**
 * Tests for SyncPointRecoveryParser — error recovery via sync-point strategy.
 */
public class SyncPointRecoveryParserTest extends ParserTestBase {

	/**
	 * Valid input: the child parser succeeds, so no recovery is needed.
	 */
	@Test
	public void testValidInputNoRecoveryNeeded() {
		// Parse "hello" with recovery on ";"
		Parser child = new WordParser("hello");
		Parser parser = new SyncPointRecoveryParser(child, ";");

		TestResult result = testAllMatch(parser, "hello");
		assertTrue(result.parsed.isSucceeded());
	}

	/**
	 * Invalid input with sync point: child fails, recovery skips to ";".
	 * The skipped region should be marked as an error.
	 */
	@Test
	public void testInvalidInputRecoveryToSemicolon() {
		// Child expects "hello", input is "xyz;rest"
		// Recovery should skip "xyz;" and succeed
		Parser child = new WordParser("hello");
		Parser parser = new SyncPointRecoveryParser(child, ";");

		TestResult result = testPartialMatch(parser, "xyz;rest", "xyz;");
		assertTrue("Recovery should succeed", result.parsed.isSucceeded());

		// Verify error message is embedded in the parse tree
		Token rootToken = result.parsed.getRootToken();
		List<RangedContent<String>> errors =
			ErrorMessageParser.getRangedContents(rootToken, ErrorMessageParser.class);
		assertFalse("Should contain error markers", errors.isEmpty());
	}

	/**
	 * Multiple errors with recovery in a repeating context.
	 * Uses OneOrMore with recovery to parse multiple statements.
	 */
	@Test
	public void testMultipleRecoveries() {
		// Each "statement" is digits followed by ";".
		// Invalid tokens before ";" should be recovered.
		Parser statement = new Chain(
			new OneOrMore(DigitParser.class),
			new SemiColonParser()
		);
		Parser recoverableStatement = new SyncPointRecoveryParser(statement, ";");
		Parser parser = new OneOrMore(recoverableStatement);

		// "123;abc;456;" — "abc;" is invalid but recoverable
		TestResult result = testAllMatch(parser, "123;abc;456;");
		assertTrue("Should recover and parse all", result.parsed.isSucceeded());
	}

	/**
	 * No sync point found in remaining input: recovery should fail.
	 */
	@Test
	public void testNoSyncPointFails() {
		Parser child = new WordParser("hello");
		Parser parser = new SyncPointRecoveryParser(child, ";");

		// "xyz" has no ";" — recovery should fail
		TestResult result = testUnMatch(parser, "xyz");
		assertTrue("Should fail when no sync point found", result.parsed.isFailed());
	}

	/**
	 * Sync point at the very beginning of remaining input.
	 * The error region is empty (just the sync token itself).
	 */
	@Test
	public void testSyncPointAtStart() {
		Parser child = new WordParser("hello");
		Parser parser = new SyncPointRecoveryParser(child, ";");

		// ";rest" — sync point is immediately at position 0
		TestResult result = testPartialMatch(parser, ";rest", ";");
		assertTrue("Should recover at immediate sync point", result.parsed.isSucceeded());
	}

	/**
	 * Multiple sync tokens configured: recovery picks the nearest one.
	 */
	@Test
	public void testMultipleSyncTokens() {
		Parser child = new WordParser("hello");
		Parser parser = new SyncPointRecoveryParser(child, ";", "}");

		// "xyz}rest" — "}" comes before any ";"
		TestResult result = testPartialMatch(parser, "xyz}rest", "xyz}");
		assertTrue("Should recover at nearest sync token", result.parsed.isSucceeded());
	}

	/**
	 * Custom error message is embedded in the parse tree.
	 */
	@Test
	public void testCustomErrorMessage() {
		String customMsg = "expected statement";
		Parser child = new WordParser("hello");
		Parser parser = SyncPointRecoveryParser.withMessage(child, customMsg, ";");

		TestResult result = testPartialMatch(parser, "xyz;rest", "xyz;");
		assertTrue(result.parsed.isSucceeded());

		Token rootToken = result.parsed.getRootToken();
		List<RangedContent<String>> errors =
			ErrorMessageParser.getRangedContents(rootToken, ErrorMessageParser.class);
		assertFalse("Should have error markers", errors.isEmpty());
		assertEquals(customMsg, errors.get(0).getContent());
	}

	/**
	 * Sync point "---END_OF_PART---" (multi-character sync token).
	 */
	@Test
	public void testMultiCharSyncToken() {
		Parser child = new WordParser("hello");
		Parser parser = new SyncPointRecoveryParser(child, "---END_OF_PART---");

		TestResult result = testPartialMatch(
			parser,
			"xyz---END_OF_PART---rest",
			"xyz---END_OF_PART---"
		);
		assertTrue("Should recover at multi-char sync token", result.parsed.isSucceeded());
	}

	/**
	 * Verify getSyncTokens returns the configured tokens.
	 */
	@Test
	public void testGetSyncTokens() {
		Parser child = new WordParser("hello");
		SyncPointRecoveryParser parser = new SyncPointRecoveryParser(child, ";", "}", "---END_OF_PART---");

		assertEquals(3, parser.getSyncTokens().size());
		assertTrue(parser.getSyncTokens().contains(";"));
		assertTrue(parser.getSyncTokens().contains("}"));
		assertTrue(parser.getSyncTokens().contains("---END_OF_PART---"));
	}
}
