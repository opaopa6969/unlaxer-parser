package org.unlaxer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SourceCursorRangeTest {

  @Test
  public void cursorRange_on_subSource_is_in_root_coordinates() {
    Source root = StringSource.createRootSource("ABCDE");
    Source sub = root.subSource(new CodePointIndex(1), new CodePointIndex(4)); // "BCD"

    CursorRange r = sub.cursorRange();

    // ✅ root 座標（positionInRoot）
    assertEquals(new CodePointIndex(1), r.startIndexInclusive().positionInRoot());
    assertEquals(new CodePointIndex(4), r.endIndexExclusive().positionInRoot());

    // 参考：sub 座標（position == positionInSub）
    assertEquals(new CodePointIndex(0), r.startIndexInclusive().position());
    assertEquals(new CodePointIndex(3), r.endIndexExclusive().position());
  }

  @Test
  public void cursorRange_on_nested_subSource_composes_in_root_coordinates() {
    Source root = StringSource.createRootSource("0123456789");
    Source sub1 = root.subSource(new CodePointIndex(2), new CodePointIndex(6)); // "2345"
    Source sub2 = sub1.subSource(new CodePointIndex(2), new CodePointIndex(4)); // "45"

    CursorRange r = sub2.cursorRange();

    // ✅ root 座標: 2 + 2 = 4 → [4,6)
    assertEquals(new CodePointIndex(4), r.startIndexInclusive().positionInRoot());
    assertEquals(new CodePointIndex(6), r.endIndexExclusive().positionInRoot());

    // sub2 上の座標は [0,2)
    assertEquals(new CodePointIndex(0), r.startIndexInclusive().position());
    assertEquals(new CodePointIndex(2), r.endIndexExclusive().position());
  }

  @Test
  public void cursorRange_handles_surrogate_pairs_in_root_coordinates() {
    Source root = StringSource.createRootSource("A😀B");
    Source emoji = root.subSource(new CodePointIndex(1), new CodePointIndex(2)); // "😀"

    CursorRange r = emoji.cursorRange();

    assertEquals(new CodePointIndex(1), r.startIndexInclusive().positionInRoot());
    assertEquals(new CodePointIndex(2), r.endIndexExclusive().positionInRoot());
  }
  
  @Test
  public void detached_cursorRange_is_zero_based1() {
    Source root = StringSource.createRootSource("ABCDE");
    Source sub  = root.subSource(new CodePointIndex(1), new CodePointIndex(4)); // "BCD"

    Source detached = StringSource.createDetachedSource(sub.sourceAsString()); // まずは単純に

    CursorRange r = detached.cursorRange();

    assertEquals(new CodePointIndex(0), r.startIndexInclusive().position());       // sub座標
    assertEquals(new CodePointIndex(3), r.endIndexExclusive().position());         // sub座標
    assertEquals(new CodePointIndex(0), r.startIndexInclusive().positionInRoot()); // root座標（≒0扱い）
  }
  
  @Test
  public void detached_cursorRange_is_zero_based() {
    Source root = StringSource.createRootSource("ABCDE");
    Source sub  = root.subSource(new CodePointIndex(1), new CodePointIndex(4)); // "BCD"

    Source detached = StringSource.createDetachedSource(sub.sourceAsString(), root);

    CursorRange r = detached.cursorRange();

    // detached は独立座標
    assertEquals(new CodePointIndex(0), r.startIndexInclusive().position());
    assertEquals(new CodePointIndex(3), r.endIndexExclusive().position());

    // root座標としても 0 起点（= 自分がroot相当）
    assertEquals(new CodePointIndex(0), r.startIndexInclusive().positionInRoot());
    assertEquals(new CodePointIndex(3), r.endIndexExclusive().positionInRoot());

  }
}
