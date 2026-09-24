package org.unlaxer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.unlaxer.Source.SourceKind;

/**
 * The four things #276 round 5 changed about what a parse tree retains: sub-sources are views
 * over the root's code points, empty child lists share one backing list, a source's depth and
 * offsets are unboxed, and the position index is an int array. None of them may change what a
 * caller observes.
 */
public class CstLiveSetTest {

  @Test
  public void subSourceIsAViewThatStillReadsLikeACopy() {
    Source root = StringSource.createRootSource("alpha beta gamma");
    Source sub = root.subSource(new CodePointIndex(6), new CodePointIndex(10));

    assertEquals("beta", sub.sourceAsString());
    assertEquals("beta", sub.toString());
    assertEquals(4, sub.codePointLength().value());
    assertEquals(4, sub.stringLength().value());
    assertEquals(6, sub.offsetFromRoot().value());
    assertEquals(6, sub.offsetFromParent().value());
    assertEquals(1, sub.depth().value());
    assertEquals(SourceKind.subSource, sub.sourceKind());
    assertSame(root, sub.root());
    assertEquals(new Range(0, 4), sub.sourceRange());
    // cursorRange() is positioned at offsetFromRoot and its cursors carry the same offset, so
    // toRange() runs 0..length for every kind of source (see case 33).
    assertEquals(new Range(0, 4), sub.cursorRange().toRange());
    assertEquals(6, sub.cursorRange().startIndexInclusive.positionInRoot().value());
    assertEquals(10, sub.cursorRange().endIndexExclusive.positionInRoot().value());

    Source nested = sub.subSource(new CodePointIndex(1), new CodePointLength(2));
    assertEquals("et", nested.sourceAsString());
    assertEquals(7, nested.offsetFromRoot().value());
    assertEquals(2, nested.depth().value());
    assertEquals(new Range(0, 2), nested.cursorRange().toRange());
    assertEquals(7, nested.cursorRange().startIndexInclusive.positionInRoot().value());
  }

  @Test
  public void aViewOverSupplementaryCodePointsKeepsCodePointSemantics() {
    // Two astral code points then ASCII: char offsets and code point offsets differ.
    String text = "😀😁ab";
    Source root = StringSource.createRootSource(text);
    Source sub = root.subSource(new CodePointIndex(1), new CodePointIndex(3));

    assertEquals("😁a", sub.sourceAsString());
    assertEquals(2, sub.codePointLength().value());
    assertEquals(3, sub.stringLength().value());
    assertEquals(1, sub.offsetFromRoot().value());
  }

  @Test
  public void anOutOfRangeSubSourceStillFailsWhereItIsRequested() {
    Source root = StringSource.createRootSource("abc");
    assertThrows(IndexOutOfBoundsException.class,
        () -> root.subSource(new CodePointIndex(1), new CodePointIndex(9)));
    assertThrows(IndexOutOfBoundsException.class,
        () -> root.subSource(new CodePointIndex(1), new CodePointLength(9)));
  }

  @Test
  public void contiguousChildrenBecomeAViewAndTheTextIsUnchanged() {
    Source root = StringSource.createRootSource("1+2");
    TokenList children = new TokenList();
    children.add(new Token(TokenKind.consumed,
        root.subSource(new CodePointIndex(0), new CodePointIndex(1)), null));
    children.add(new Token(TokenKind.consumed,
        root.subSource(new CodePointIndex(1), new CodePointIndex(2)), null));
    children.add(new Token(TokenKind.consumed,
        root.subSource(new CodePointIndex(2), new CodePointIndex(3)), null));

    Source combined = TokenList.toSource(children, SourceKind.subSource);
    assertEquals("1+2", combined.sourceAsString());
    assertEquals(0, combined.offsetFromRoot().value());
    assertEquals(3, combined.codePointLength().value());
    assertSame(root, combined.root());
  }

  @Test
  public void nonContiguousChildrenFallBackToConcatenation() {
    Source root = StringSource.createRootSource("abcdef");
    TokenList children = new TokenList();
    children.add(new Token(TokenKind.consumed,
        root.subSource(new CodePointIndex(0), new CodePointIndex(1)), null));
    // Skips "b": the concatenation is no longer a slice of the root.
    children.add(new Token(TokenKind.consumed,
        root.subSource(new CodePointIndex(2), new CodePointIndex(3)), null));

    Source combined = TokenList.toSource(children, SourceKind.subSource);
    assertEquals("ac", combined.sourceAsString());
    assertEquals(2, combined.codePointLength().value());
    assertEquals(0, combined.offsetFromRoot().value());
  }

  @Test
  public void emptyTokenListsShareABackingListUntilWritten() {
    TokenList first = new TokenList();
    TokenList second = new TokenList(0);
    assertTrue(first.isEmpty());
    assertEquals(0, second.size());
    assertEquals(List.of(), first.subList(0, 0));

    Token token = new Token(TokenKind.consumed, StringSource.createRootSource("a"), null);
    first.add(token);
    assertEquals(1, first.size());
    assertEquals(0, second.size());
    assertSame(token, first.get(0));
  }

  @Test
  public void anEmptyTokenListFailsExactlyWhereAnArrayListWould() {
    TokenList empty = new TokenList();
    Token token = new Token(TokenKind.consumed, StringSource.createRootSource("a"), null);

    assertThrows(IndexOutOfBoundsException.class, () -> empty.set(0, token));
    assertThrows(IndexOutOfBoundsException.class, () -> empty.remove(0));
    assertThrows(IndexOutOfBoundsException.class, () -> empty.addAll(3, List.of()));
    assertEquals(false, empty.addAll(0, List.of()));
    assertEquals(false, empty.remove(token));
    assertEquals(false, empty.removeAll(List.of(token)));
    assertEquals(false, empty.retainAll(List.of(token)));
    assertEquals(false, empty.removeIf(candidate -> true));
    empty.clear();
    assertTrue(empty.isEmpty());

    // listIterator() hands out a mutable view, as ArrayList's does.
    empty.listIterator().add(token);
    assertEquals(1, empty.size());
  }

  @Test
  public void thePositionIndexAnswersLikeTheMapItReplaced() {
    Source root = StringSource.createRootSource("ab\r\ncd\n😀e");
    PositionResolver resolver = root.positionResolver();

    assertEquals(0, resolver.stringIndexInRootFrom(new CodePointIndex(0)).value());
    assertEquals(2, resolver.stringIndexInRootFrom(new CodePointIndex(2)).value());
    // The supplementary code point occupies two chars, so later string indexes shift.
    assertEquals(7, resolver.stringIndexInRootFrom(new CodePointIndex(7)).value());
    assertEquals(9, resolver.stringIndexInRootFrom(new CodePointIndex(8)).value());
    assertEquals(8, resolver.rootCodePointIndexFrom(new StringIndex(9)).value());
    assertNull("the surrogate's trailing char is not a code point boundary",
        resolver.rootCodePointIndexFrom(new StringIndex(8)));
    assertNull("one past the end has no string index", resolver.stringIndexInRootFrom(new CodePointIndex(9)));
    assertNull("far out of range must not throw", resolver.stringIndexInRootFrom(new CodePointIndex(9999)));

    assertEquals(0, resolver.lineNumberFrom(new CodePointIndex(0)).value());
    assertEquals(1, resolver.lineNumberFrom(new CodePointIndex(4)).value());
    assertEquals(2, resolver.lineNumberFrom(new CodePointIndex(7)).value());
    assertEquals(0, resolver.codePointIndexInLineFrom(new CodePointIndex(4)).value());
    assertEquals(1, resolver.codePointIndexInLineFrom(new CodePointIndex(5)).value());
    assertNotNull(resolver.rootCursorRange());
  }
}
