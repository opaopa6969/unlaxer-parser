package org.unlaxer;

import static org.junit.Assert.*;

import org.junit.Test;

public class CodePointTest {

  @Test
  public void testAsciiCodePoint() {
    CodePoint cp = new CodePoint('A');
    assertEquals(65, cp.value());
    assertEquals("A", cp.toString());
    assertArrayEquals(new char[]{'A'}, cp.toChars());
  }

  @Test
  public void testDigitCodePoint() {
    CodePoint cp = new CodePoint('0');
    assertEquals(48, cp.value());
    assertEquals("0", cp.toString());
  }

  @Test
  public void testSpaceCodePoint() {
    CodePoint cp = new CodePoint(' ');
    assertEquals(32, cp.value());
    assertEquals(" ", cp.toString());
  }

  @Test
  public void testJapaneseCodePoint() {
    // U+3042 HIRAGANA LETTER A
    int codePoint = "\u3042".codePointAt(0);
    CodePoint cp = new CodePoint(codePoint);
    assertEquals(0x3042, cp.value());
    assertEquals("\u3042", cp.toString());
  }

  @Test
  public void testSurrogatePairCodePoint() {
    // U+2A6B2 CJK Unified Ideograph (surrogate pair in UTF-16)
    String surrogate = "\uD869\uDEB2"; // U+2A6B2
    int codePoint = surrogate.codePointAt(0);
    CodePoint cp = new CodePoint(codePoint);
    assertEquals(codePoint, cp.value());
    char[] chars = cp.toChars();
    assertEquals(2, chars.length); // surrogate pair produces 2 chars
  }

  @Test
  public void testEmojiCodePoint() {
    // U+1F600 GRINNING FACE
    int codePoint = 0x1F600;
    CodePoint cp = new CodePoint(codePoint);
    assertEquals(0x1F600, cp.value());
    char[] chars = cp.toChars();
    assertEquals(2, chars.length); // emoji needs surrogate pair
  }

  @Test
  public void testBoundaryValueZero() {
    CodePoint cp = new CodePoint(0);
    assertEquals(0, cp.value());
  }

  @Test
  public void testBoundaryValueMax() {
    // U+10FFFF is the maximum valid Unicode code point
    CodePoint cp = new CodePoint(0x10FFFF);
    assertEquals(0x10FFFF, cp.value());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNegativeCodePointThrows() {
    new CodePoint(-1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testExceedMaxCodePointThrows() {
    new CodePoint(0x10FFFF + 1);
  }

  @Test
  public void testEquality() {
    CodePoint cp1 = new CodePoint('A');
    CodePoint cp2 = new CodePoint(65);
    assertEquals(cp1, cp2);
    assertEquals(cp1.hashCode(), cp2.hashCode());
  }

  @Test
  public void testInequality() {
    CodePoint cp1 = new CodePoint('A');
    CodePoint cp2 = new CodePoint('B');
    assertNotEquals(cp1, cp2);
  }

  @Test
  public void testCompareTo() {
    CodePoint cpA = new CodePoint('A');
    CodePoint cpB = new CodePoint('B');
    assertTrue(cpA.compareTo(cpB) < 0);
    assertTrue(cpB.compareTo(cpA) > 0);
    assertEquals(0, cpA.compareTo(new CodePoint('A')));
  }

  @Test
  public void testArithmetic() {
    CodePoint cp = new CodePoint('A');
    CodePoint incremented = cp.newWithIncrements();
    assertEquals('B', incremented.value());

    CodePoint decremented = cp.newWithDecrements();
    assertEquals('@', decremented.value());

    CodePoint added = cp.newWithAdd(5);
    assertEquals('F', added.value());
  }

  @Test
  public void testCreateFromIntegerValue() {
    CodePoint cp1 = new CodePoint(65);
    CodePoint cp2 = new CodePoint(cp1);
    assertEquals(cp1, cp2);
  }

  @Test
  public void testCreateMethod() {
    CodePoint cp = new CodePoint(65);
    CodePoint created = cp.create(66);
    assertEquals(66, created.value());
  }

  @Test
  public void testCreateFromIntegerValueMethod() {
    CodePoint cp1 = new CodePoint(65);
    CodePoint cp2 = cp1.create(cp1);
    assertEquals(cp1, cp2);
  }
}
