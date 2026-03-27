package org.unlaxer.parser.elementary;

import org.junit.Test;
import org.unlaxer.ParserTestBase;

public class QuotedParserEdgeCaseTest extends ParserTestBase {

  private final DoubleQuotedParser dq = new DoubleQuotedParser();
  private final SingleQuotedParser sq = new SingleQuotedParser();

  // --- Double quoted ---

  @Test
  public void testDoubleQuotedSimple() {
    testAllMatch(dq, "\"hello\"");
  }

  @Test
  public void testDoubleQuotedEmpty() {
    testAllMatch(dq, "\"\"");
  }

  @Test
  public void testDoubleQuotedWithSpaces() {
    testAllMatch(dq, "\"hello world\"");
  }

  @Test
  public void testDoubleQuotedWithEscapedQuote() {
    testAllMatch(dq, "\"ab\\\"c\"");
  }

  @Test
  public void testDoubleQuotedWithBackslash() {
    testAllMatch(dq, "\"ab\\\\c\"");
  }

  @Test
  public void testDoubleQuotedWithNewline() {
    testAllMatch(dq, "\"line1\\nline2\"");
  }

  @Test
  public void testDoubleQuotedWithTab() {
    testAllMatch(dq, "\"col1\\tcol2\"");
  }

  @Test
  public void testDoubleQuotedUnclosedFails() {
    testUnMatch(dq, "\"unclosed");
  }

  @Test
  public void testDoubleQuotedPartial() {
    testPartialMatch(dq, "\"hello\" world", "\"hello\"");
  }

  @Test
  public void testDoubleQuotedNumbers() {
    testAllMatch(dq, "\"12345\"");
  }

  @Test
  public void testDoubleQuotedSpecialChars() {
    testAllMatch(dq, "\"!@#$%^&*()\"");
  }

  // --- Single quoted ---

  @Test
  public void testSingleQuotedSimple() {
    testAllMatch(sq, "'hello'");
  }

  @Test
  public void testSingleQuotedEmpty() {
    testAllMatch(sq, "''");
  }

  @Test
  public void testSingleQuotedWithSpaces() {
    testAllMatch(sq, "'hello world'");
  }

  @Test
  public void testSingleQuotedWithEscapedQuote() {
    testAllMatch(sq, "'ab\\'c'");
  }

  @Test
  public void testSingleQuotedUnclosedFails() {
    testUnMatch(sq, "'unclosed");
  }

  @Test
  public void testSingleQuotedPartial() {
    testPartialMatch(sq, "'hello' world", "'hello'");
  }

  // --- Edge cases ---

  @Test
  public void testNotQuotedFails() {
    testUnMatch(dq, "hello");
    testUnMatch(sq, "hello");
  }

  @Test
  public void testWrongQuoteFails() {
    testUnMatch(dq, "'hello'");
    testUnMatch(sq, "\"hello\"");
  }
}
