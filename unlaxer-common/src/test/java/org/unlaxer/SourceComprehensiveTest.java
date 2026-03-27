package org.unlaxer;

import static org.junit.Assert.*;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;
import org.unlaxer.Source.SourceKind;

public class SourceComprehensiveTest {

  // --- StringSource creation ---

  @Test
  public void testCreateRootSource() {
    StringSource source = StringSource.createRootSource("hello");
    assertEquals("hello", source.toString());
    assertEquals("hello", source.sourceAsString());
    assertTrue(source.isRoot());
    assertEquals(SourceKind.root, source.sourceKind());
  }

  @Test
  public void testCreateEmptySource() {
    StringSource source = StringSource.createRootSource("");
    assertEquals("", source.toString());
    assertTrue(source.isEmpty());
    assertFalse(source.isPresent());
    assertEquals(0, source.codePointLength().value());
    assertEquals(0, source.stringLength().value());
  }

  @Test
  public void testCreateDetachedSource() {
    StringSource source = StringSource.createDetachedSource("detached");
    assertEquals("detached", source.toString());
    assertTrue(source.isRoot()); // detached sources are treated as roots
    assertEquals(SourceKind.detached, source.sourceKind());
  }

  // --- charAt, length ---

  @Test
  public void testCharAt() {
    StringSource source = StringSource.createRootSource("abc");
    assertEquals('a', source.charAt(0));
    assertEquals('b', source.charAt(1));
    assertEquals('c', source.charAt(2));
  }

  @Test
  public void testLength() {
    StringSource source = StringSource.createRootSource("hello");
    assertEquals(5, source.length());
    assertEquals(5, source.stringLength().value());
    assertEquals(5, source.codePointLength().value());
  }

  // --- Unicode content handling ---

  @Test
  public void testUnicodeContent() {
    String japanese = "\u3053\u3093\u306B\u3061\u306F"; // konnichiwa
    StringSource source = StringSource.createRootSource(japanese);
    assertEquals(japanese, source.toString());
    assertEquals(5, source.codePointLength().value());
    assertEquals(5, source.stringLength().value());
  }

  @Test
  public void testSurrogatePairLength() {
    String surrogate = "\uD869\uDEB2"; // U+2A6B2
    StringSource source = StringSource.createRootSource(surrogate);
    assertEquals(1, source.codePointLength().value());
    assertEquals(2, source.stringLength().value()); // 2 chars in UTF-16
  }

  // --- SubSource behavior ---

  @Test
  public void testSubSourceByCodePointIndex() {
    StringSource source = StringSource.createRootSource("ABCDEF");
    Source sub = source.subSource(new CodePointIndex(2), new CodePointIndex(4));
    assertEquals("CD", sub.sourceAsString());
    assertFalse(sub.isRoot());
    assertEquals(SourceKind.subSource, sub.sourceKind());
  }

  @Test
  public void testSubSourceByCodePointLength() {
    StringSource source = StringSource.createRootSource("ABCDEF");
    Source sub = source.subSource(new CodePointIndex(1), new CodePointLength(3));
    assertEquals("BCD", sub.sourceAsString());
  }

  @Test
  public void testSubSourceFromBeginIndex() {
    StringSource source = StringSource.createRootSource("ABCDEF");
    Source sub = source.subSource(new CodePointIndex(3));
    assertEquals("DEF", sub.sourceAsString());
  }

  @Test
  public void testSubSourceCursorRangeInRoot() {
    StringSource source = StringSource.createRootSource("ABCDEF");
    Source sub = source.subSource(new CodePointIndex(2), new CodePointIndex(5));
    CursorRange range = sub.cursorRange();
    assertEquals(2, range.startIndexInclusive().positionInRoot().value());
    assertEquals(5, range.endIndexExclusive().positionInRoot().value());
  }

  @Test
  public void testSubSourceEmptyRange() {
    StringSource source = StringSource.createRootSource("ABC");
    Source sub = source.subSource(new CodePointIndex(1), new CodePointIndex(1));
    assertEquals("", sub.sourceAsString());
    assertTrue(sub.isEmpty());
  }

  // --- Peek ---

  @Test
  public void testPeek() {
    StringSource source = StringSource.createRootSource("ABCDEF");
    Source peeked = source.peek(new CodePointIndex(1), new CodePointLength(2));
    assertEquals("BC", peeked.sourceAsString());
  }

  @Test
  public void testPeekBeyondEnd() {
    StringSource source = StringSource.createRootSource("AB");
    Source peeked = source.peek(new CodePointIndex(1), new CodePointLength(10));
    // When length exceeds available, returns empty
    assertEquals("", peeked.sourceAsString());
  }

  // --- String operations ---

  @Test
  public void testContains() {
    StringSource source = StringSource.createRootSource("hello world");
    assertTrue(source.contains("world"));
    assertFalse(source.contains("xyz"));
  }

  @Test
  public void testStartsWith() {
    StringSource source = StringSource.createRootSource("hello world");
    assertTrue(source.startsWith("hello"));
    assertFalse(source.startsWith("world"));
  }

  @Test
  public void testEndsWith() {
    StringSource source = StringSource.createRootSource("hello world");
    assertTrue(source.endsWith("world"));
    assertFalse(source.endsWith("hello"));
  }

  @Test
  public void testIndexOf() {
    StringSource source = StringSource.createRootSource("abcabc");
    assertEquals(0, source.indexOf("abc"));
    assertEquals(3, source.indexOf("abc", 1));
    assertEquals(0, source.indexOf('a'));
  }

  @Test
  public void testEquals() {
    StringSource s1 = StringSource.createRootSource("hello");
    StringSource s2 = StringSource.createRootSource("hello");
    StringSource s3 = StringSource.createRootSource("world");
    assertEquals(s1, s2);
    assertNotEquals(s1, s3);
  }

  @Test
  public void testIsBlank() {
    assertTrue(StringSource.createRootSource("").isBlank());
    assertTrue(StringSource.createRootSource("  ").isBlank());
    assertFalse(StringSource.createRootSource("a").isBlank());
  }

  // --- Source joining ---

  @Test
  public void testJoiningWithDelimiter() {
    List<StringSource> list = List.of(
        StringSource.createDetachedSource("a"),
        StringSource.createDetachedSource("b"),
        StringSource.createDetachedSource("c"));
    Source joined = list.stream().collect(Source.joining(","));
    assertEquals("a,b,c", joined.toString());
  }

  @Test
  public void testJoiningWithPrefixSuffix() {
    List<StringSource> list = List.of(
        StringSource.createDetachedSource("x"),
        StringSource.createDetachedSource("y"));
    Source joined = list.stream().collect(Source.joining(",", "[", "]"));
    assertEquals("[x,y]", joined.toString());
  }

  @Test
  public void testJoiningEmpty() {
    List<StringSource> list = List.of(
        StringSource.createDetachedSource("ab"),
        StringSource.createDetachedSource("cd"));
    Source joined = list.stream().collect(Source.joining());
    assertEquals("abcd", joined.toString());
  }

  // --- Lines ---

  @Test
  public void testLinesAsSource() {
    Source source = StringSource.createRootSource("line1\nline2\nline3");
    List<Source> lines = source.linesAsSource().collect(Collectors.toList());
    assertEquals(3, lines.size());
  }

  // --- Depth ---

  @Test
  public void testRootDepthIsZero() {
    StringSource source = StringSource.createRootSource("test");
    assertEquals(0, source.depth().value());
  }

  @Test
  public void testSubSourceDepth() {
    StringSource root = StringSource.createRootSource("ABCDEF");
    Source sub = root.subSource(new CodePointIndex(0), new CodePointIndex(3));
    assertEquals(1, sub.depth().value());
  }

  // --- Parent / root ---

  @Test
  public void testRootParent() {
    StringSource source = StringSource.createRootSource("test");
    // root's parent is itself
    assertTrue(source.parent().isPresent());
    assertEquals(source, source.root());
  }

  @Test
  public void testSubSourceParent() {
    StringSource root = StringSource.createRootSource("ABCDEF");
    Source sub = root.subSource(new CodePointIndex(1), new CodePointIndex(4));
    assertTrue(sub.hasParent());
    assertEquals(root, sub.root());
  }

  // --- Offset ---

  @Test
  public void testOffsetFromRoot() {
    StringSource root = StringSource.createRootSource("ABCDEF");
    assertEquals(0, root.offsetFromRoot().value());
  }

  @Test
  public void testCreateSubSource() {
    StringSource root = StringSource.createRootSource("test");
    StringSource sub = StringSource.createSubSource("es", root, new CodePointOffset(1));
    assertEquals("es", sub.sourceAsString());
    assertEquals(SourceKind.subSource, sub.sourceKind());
  }

  @Test
  public void testCreateSubSourceWithNullParent() {
    // When parent is null, createSubSource falls back to detached
    StringSource sub = StringSource.createSubSource("test", null, new CodePointOffset(0));
    assertEquals("test", sub.sourceAsString());
    assertEquals(SourceKind.detached, sub.sourceKind());
  }
}
