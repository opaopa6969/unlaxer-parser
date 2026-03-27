package org.unlaxer;

import static org.junit.Assert.*;

import org.junit.Test;

public class TagNodeKindTest {

  // --- Tag creation and comparison ---

  @Test
  public void testTagFromString() {
    Tag tag1 = Tag.of("myTag");
    Tag tag2 = Tag.of("myTag");
    assertEquals(tag1, tag2);
    assertSame(tag1, tag2); // cached
  }

  @Test
  public void testTagFromDifferentStrings() {
    Tag tag1 = Tag.of("tagA");
    Tag tag2 = Tag.of("tagB");
    assertNotEquals(tag1, tag2);
  }

  @Test
  public void testTagFromClass() {
    Tag tag1 = Tag.of(String.class);
    Tag tag2 = Tag.of(String.class);
    assertEquals(tag1, tag2);
    assertSame(tag1, tag2); // cached
  }

  @Test
  public void testTagFromDifferentClasses() {
    Tag tag1 = Tag.of(String.class);
    Tag tag2 = Tag.of(Integer.class);
    assertNotEquals(tag1, tag2);
  }

  @Test
  public void testTagFromEnum() {
    Tag tag1 = Tag.of(TestEnum.VALUE_A);
    Tag tag2 = Tag.of(TestEnum.VALUE_A);
    assertEquals(tag1, tag2);
    assertSame(tag1, tag2); // cached
  }

  @Test
  public void testTagFromDifferentEnums() {
    Tag tag1 = Tag.of(TestEnum.VALUE_A);
    Tag tag2 = Tag.of(TestEnum.VALUE_B);
    assertNotEquals(tag1, tag2);
  }

  @Test
  public void testTagFromClassAndString() {
    Tag tag1 = Tag.of(String.class, "sub1");
    Tag tag2 = Tag.of(String.class, "sub1");
    assertEquals(tag1, tag2);
  }

  @Test
  public void testTagToString() {
    Tag tag = Tag.of("myTag");
    assertEquals("myTag", tag.toString());
  }

  @Test
  public void testTagGetName() {
    Tag tag = Tag.of("myTag");
    assertEquals("myTag", tag.getName());
  }

  @Test
  public void testTagGetSimpleName() {
    Tag tag = Tag.of(String.class);
    assertEquals("String", tag.getSimpleName());
  }

  @Test
  public void testClassBaseOf() {
    String s = "hello";
    Tag tag = Tag.classBaseOf(s);
    assertNotNull(tag);
    // classBaseOf uses specifierByString cache, Tag.of(Class) uses specifierByClass cache
    // Both produce tags with the same name
    assertEquals(tag.getName(), Tag.of(String.class).getName());
  }

  @Test
  public void testTagHashCode() {
    Tag tag1 = Tag.of("sameTag");
    Tag tag2 = Tag.of("sameTag");
    assertEquals(tag1.hashCode(), tag2.hashCode());
  }

  // --- Kind creation and comparison ---

  @Test
  public void testKindFromString() {
    Kind kind1 = Kind.of("myKind");
    Kind kind2 = Kind.of("myKind");
    assertEquals(kind1, kind2);
    assertSame(kind1, kind2);
  }

  @Test
  public void testKindFromClass() {
    Kind kind1 = Kind.of(String.class);
    Kind kind2 = Kind.of(String.class);
    assertEquals(kind1, kind2);
  }

  @Test
  public void testKindFromEnum() {
    Kind kind1 = Kind.of(TestEnum.VALUE_A);
    Kind kind2 = Kind.of(TestEnum.VALUE_A);
    assertEquals(kind1, kind2);
  }

  @Test
  public void testKindClassBaseOf() {
    Kind kind = Kind.classBaseOf("hello");
    assertNotNull(kind);
  }

  // --- TokenKind ---

  @Test
  public void testTokenKindConsumed() {
    assertTrue(TokenKind.consumed.isConsumed());
    assertFalse(TokenKind.consumed.isMatchOnly());
    assertTrue(TokenKind.consumed.isReal());
    assertFalse(TokenKind.consumed.isVirtual());
  }

  @Test
  public void testTokenKindMatchOnly() {
    assertTrue(TokenKind.matchOnly.isMatchOnly());
    assertFalse(TokenKind.matchOnly.isConsumed());
    assertTrue(TokenKind.matchOnly.isReal());
    assertFalse(TokenKind.matchOnly.isVirtual());
  }

  @Test
  public void testTokenKindVirtualConsumed() {
    assertTrue(TokenKind.virtualTokenConsumed.isConsumed());
    assertFalse(TokenKind.virtualTokenConsumed.isMatchOnly());
    assertFalse(TokenKind.virtualTokenConsumed.isReal());
    assertTrue(TokenKind.virtualTokenConsumed.isVirtual());
  }

  @Test
  public void testTokenKindVirtualMatchOnly() {
    assertTrue(TokenKind.virtualTokenMatchOnly.isMatchOnly());
    assertFalse(TokenKind.virtualTokenMatchOnly.isConsumed());
    assertFalse(TokenKind.virtualTokenMatchOnly.isReal());
    assertTrue(TokenKind.virtualTokenMatchOnly.isVirtual());
  }

  @Test
  public void testTokenKindOf() {
    assertEquals(TokenKind.consumed, TokenKind.of(true));
    assertEquals(TokenKind.matchOnly, TokenKind.of(false));
  }

  // --- Parsed.Status ---

  @Test
  public void testParsedStatusSucceeded() {
    assertTrue(Parsed.Status.succeeded.isSucceeded());
    assertFalse(Parsed.Status.succeeded.isFailed());
    assertFalse(Parsed.Status.succeeded.isStopped());
  }

  @Test
  public void testParsedStatusFailed() {
    assertTrue(Parsed.Status.failed.isFailed());
    assertFalse(Parsed.Status.failed.isSucceeded());
    assertFalse(Parsed.Status.failed.isStopped());
  }

  @Test
  public void testParsedStatusStopped() {
    assertTrue(Parsed.Status.stopped.isStopped());
    assertTrue(Parsed.Status.stopped.isSucceeded()); // stopped is also succeeded
    assertFalse(Parsed.Status.stopped.isFailed());
  }

  @Test
  public void testParsedStatusNegate() {
    assertEquals(Parsed.Status.failed, Parsed.Status.succeeded.negate());
    assertEquals(Parsed.Status.succeeded, Parsed.Status.failed.negate());
    assertEquals(Parsed.Status.failed, Parsed.Status.stopped.negate());
  }

  // --- Specifier ---

  @Test
  public void testSpecifierEquality() {
    Specifier<Tag> s1 = new Tag("test");
    Specifier<Tag> s2 = new Tag("test");
    assertEquals(s1, s2);
    assertEquals(s1.hashCode(), s2.hashCode());
  }

  @Test
  public void testSpecifierInequality() {
    Specifier<Tag> s1 = new Tag("abc");
    Specifier<Tag> s2 = new Tag("xyz");
    assertNotEquals(s1, s2);
  }

  @Test
  public void testTagToEnumConversion() {
    Tag tag = Tag.of(TestEnum.VALUE_A);
    TestEnum result = tag.toEnum(TestEnum.class);
    assertEquals(TestEnum.VALUE_A, result);
  }

  // --- Helper enum ---

  enum TestEnum {
    VALUE_A,
    VALUE_B
  }
}
