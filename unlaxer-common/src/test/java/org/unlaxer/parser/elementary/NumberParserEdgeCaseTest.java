package org.unlaxer.parser.elementary;

import org.junit.Test;
import org.unlaxer.ParserTestBase;

public class NumberParserEdgeCaseTest extends ParserTestBase {

  private final NumberParser number = new NumberParser();

  // --- Integer values ---

  @Test
  public void testZero() {
    testPartialMatch(number, "0", "0");
  }

  @Test
  public void testSimpleInteger() {
    testPartialMatch(number, "123", "123");
  }

  @Test
  public void testSingleDigit() {
    testPartialMatch(number, "9", "9");
  }

  @Test
  public void testNegativeInteger() {
    testPartialMatch(number, "-1", "-1");
  }

  @Test
  public void testPositiveSign() {
    testPartialMatch(number, "+42", "+42");
  }

  @Test
  public void testLargeInteger() {
    testPartialMatch(number, "999999999", "999999999");
  }

  // --- Float values ---

  @Test
  public void testSimpleFloat() {
    testPartialMatch(number, "3.14", "3.14");
  }

  @Test
  public void testLeadingDotFloat() {
    testPartialMatch(number, ".5", ".5");
  }

  @Test
  public void testZeroPointZero() {
    testPartialMatch(number, "0.0", "0.0");
  }

  @Test
  public void testTrailingDot() {
    testPartialMatch(number, "123.", "123.");
  }

  @Test
  public void testNegativeFloat() {
    testPartialMatch(number, "-3.14", "-3.14");
  }

  @Test
  public void testPositiveFloat() {
    testPartialMatch(number, "+0.5", "+0.5");
  }

  // --- Exponent values ---

  @Test
  public void testExponentLowerCase() {
    testPartialMatch(number, "1e10", "1e10");
  }

  @Test
  public void testExponentUpperCase() {
    testPartialMatch(number, "1E10", "1E10");
  }

  @Test
  public void testExponentNegative() {
    testPartialMatch(number, "1.5e-3", "1.5e-3");
  }

  @Test
  public void testExponentPositive() {
    testPartialMatch(number, "1e+10", "1e+10");
  }

  @Test
  public void testExponentWithFloat() {
    testPartialMatch(number, "1.23e5", "1.23e5");
  }

  @Test
  public void testNegativeWithExponent() {
    testPartialMatch(number, "-.0e10", "-.0e10");
  }

  // --- Invalid inputs ---

  @Test
  public void testAlphabeticFails() {
    testUnMatch(number, "abc");
  }

  @Test
  public void testDotOnlyFails() {
    testUnMatch(number, ".");
  }

  @Test
  public void testMinusOnlyFails() {
    testUnMatch(number, "-");
  }

  @Test
  public void testMinusDotFails() {
    testUnMatch(number, "-.");
  }

  @Test
  public void testPlusOnlyFails() {
    testUnMatch(number, "+");
  }

  // --- Partial matching (number followed by extra content) ---

  @Test
  public void testDoubleDot() {
    // "123." matches, ".4" remains
    testPartialMatch(number, "123..4", "123.");
  }

  @Test
  public void testNumberFollowedByAlpha() {
    testPartialMatch(number, "42abc", "42");
  }

  @Test
  public void testExponentFollowedByExtra() {
    testPartialMatch(number, "1.23e-5.4", "1.23e-5");
  }

  @Test
  public void testMultipleDecimals() {
    // Only first valid number is matched
    testPartialMatch(number, "1.2.3", "1.2");
  }
}
