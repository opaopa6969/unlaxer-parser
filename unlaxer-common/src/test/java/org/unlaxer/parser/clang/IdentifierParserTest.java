package org.unlaxer.parser.clang;

import org.junit.Test;
import org.unlaxer.ParserTestBase;

public class IdentifierParserTest extends ParserTestBase {

  private final IdentifierParser identifier = new IdentifierParser();

  // --- Simple identifiers ---

  @Test
  public void testSimpleAlpha() {
    testAllMatch(identifier, "foo");
  }

  @Test
  public void testSingleChar() {
    testAllMatch(identifier, "x");
  }

  @Test
  public void testLongerIdentifier() {
    testAllMatch(identifier, "myVariable");
  }

  // --- With numbers ---

  @Test
  public void testAlphaFollowedByDigit() {
    testAllMatch(identifier, "x1");
  }

  @Test
  public void testIdentifierWithMultipleDigits() {
    testAllMatch(identifier, "count2value3");
  }

  // --- With underscores ---

  @Test
  public void testUnderscorePrefix() {
    testAllMatch(identifier, "_private");
  }

  @Test
  public void testUnderscoreInMiddle() {
    testAllMatch(identifier, "my_var");
  }

  @Test
  public void testDoubleUnderscore() {
    testAllMatch(identifier, "__init");
  }

  @Test
  public void testUnderscoreOnly() {
    testAllMatch(identifier, "_");
  }

  @Test
  public void testUnderscoreWithDigits() {
    testAllMatch(identifier, "_123");
  }

  // --- Starting with digit should not fully match ---

  @Test
  public void testStartingWithDigitFails() {
    testUnMatch(identifier, "1abc");
  }

  @Test
  public void testDigitOnlyFails() {
    testUnMatch(identifier, "123");
  }

  // --- Keywords still match as identifiers ---

  @Test
  public void testKeywordIf() {
    testAllMatch(identifier, "if");
  }

  @Test
  public void testKeywordReturn() {
    testAllMatch(identifier, "return");
  }

  @Test
  public void testKeywordClass() {
    testAllMatch(identifier, "class");
  }

  // --- Mixed case ---

  @Test
  public void testUpperCase() {
    testAllMatch(identifier, "FOO");
  }

  @Test
  public void testMixedCase() {
    testAllMatch(identifier, "camelCase");
  }

  @Test
  public void testPascalCase() {
    testAllMatch(identifier, "PascalCase");
  }

  // --- Special characters should not match ---

  @Test
  public void testSpaceFails() {
    testUnMatch(identifier, " ");
  }

  @Test
  public void testHyphenFails() {
    // Partial match: "my" matches, then "-var" is not part of identifier
    testPartialMatch(identifier, "my-var", "my");
  }

  @Test
  public void testDotFails() {
    testPartialMatch(identifier, "a.b", "a");
  }

  // --- Partial matching ---

  @Test
  public void testIdentifierFollowedBySpace() {
    testPartialMatch(identifier, "foo bar", "foo");
  }

  @Test
  public void testIdentifierFollowedByParen() {
    testPartialMatch(identifier, "func()", "func");
  }
}
