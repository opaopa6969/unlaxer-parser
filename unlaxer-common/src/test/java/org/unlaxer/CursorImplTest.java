package org.unlaxer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.unlaxer.Cursor.EndExclusiveCursor;
import org.unlaxer.Cursor.StartInclusiveCursor;
import org.unlaxer.Source.SourceKind;

public class CursorImplTest {

  @Test
  public void test() {
    
  }

  /**
   * On a subSource, position() returns the sub-relative coordinate
   * (rootPosition - offsetFromRoot), while positionInRoot() keeps the
   * root-absolute coordinate. StartInclusiveCursorImpl drives every
   * CursorRange / TokenList start, so this mapping must not regress.
   */
  @Test
  public void positionInSubSubtractsOffsetFromRoot() {
    StringSource root = StringSource.createRootSource("0123456789");
    Source sub = root.subSource(new CodePointIndex(4), new CodePointIndex(7));
    PositionResolver resolver = sub.positionResolver();
    StartInclusiveCursorImpl cursor = new StartInclusiveCursorImpl(
        SourceKind.subSource, resolver, new CodePointIndex(5), new CodePointOffset(4));

    assertEquals(1, cursor.position().value());
    assertEquals(5, cursor.positionInRoot().value());
  }

  /**
   * copy() must produce an independent cursor: mutating the original via
   * setPosition must not affect the copy. EndExclusiveCursorImpl is used
   * for every token end, so shared-state regressions would corrupt all
   * AST ranges.
   */
  @Test
  public void copyIsIndependentOfOriginal() {
    StringSource root = StringSource.createRootSource("0123456789");
    PositionResolver resolver = root.positionResolver();
    EndExclusiveCursorImpl original = new EndExclusiveCursorImpl(
        SourceKind.root, resolver, new CodePointIndex(3), CodePointOffset.ZERO);

    EndExclusiveCursor copy = original.copy();
    assertEquals(3, copy.position().value());

    original.setPosition(new CodePointIndex(7));
    assertEquals(3, copy.position().value());
    assertEquals(7, original.position().value());
  }

  /**
   * newWithAddPosition must not mutate the source cursor (immutability of
   * the derived cursor). StartInclusiveCursor underpins position advances
   * across the whole parse, so a side-effect here would silently shift
   * every subsequent token.
   */
  @Test
  public void newWithAddPositionDoesNotMutateOriginal() {
    StringSource root = StringSource.createRootSource("0123456789");
    PositionResolver resolver = root.positionResolver();
    StartInclusiveCursorImpl original = new StartInclusiveCursorImpl(
        SourceKind.root, resolver, new CodePointIndex(2), CodePointOffset.ZERO);

    StartInclusiveCursor advanced = original.newWithAddPosition(new CodePointOffset(3));
    assertEquals(5, advanced.position().value());
    assertEquals(2, original.position().value());
  }
}
