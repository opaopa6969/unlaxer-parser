package org.unlaxer;

import static org.junit.Assert.*;

import org.junit.Test;

public class SourceSubSourceTest {

  @Test
  public void subSource_keeps_parent_and_offset() {
    Source root = StringSource.createRootSource("ABCDE");

    // "BCD"
    Source sub = root.subSource(
        new CodePointIndex(1),
        new CodePointIndex(4)
    );

    assertEquals("BCD", sub.sourceAsString());

    // 親を持つ（←これが v2.0.2 の implicit 仕様）
    assertTrue(sub.parent().isPresent());
    assertSame(root, sub.parent().get());

    // 親からの offset
    assertEquals(
        new CodePointOffset(1),
        sub.offsetFromParent()
    );

    // root からの offset
    assertEquals(
        new CodePointOffset(1),
        sub.offsetFromRoot()
    );
  }
  
  @Test
  public void subSource_of_subSource_composes_offset() {
    Source root = StringSource.createRootSource("0123456789");

    // "2345"
    Source sub1 = root.subSource(
        new CodePointIndex(2),
        new CodePointIndex(6)
    );

    // "45"
    Source sub2 = sub1.subSource(
        new CodePointIndex(2),
        new CodePointIndex(4)
    );

    assertEquals("45", sub2.sourceAsString());

    // parent は sub1
    assertSame(sub1, sub2.parent().get());

    // root から見ると 2 + 2 = 4
    assertEquals(
        new CodePointOffset(4),
        sub2.offsetFromRoot()
    );
  }
  
  @Test
  public void replace_breaks_origin_mapping() {
    Source root = StringSource.createRootSource("ABCDEF");

    Source sub = root.subSource(
        new CodePointIndex(1),
        new CodePointIndex(5) // "BCDE"
    );

    // replace（長さが変わる）
    Source replaced = sub.replaceAsStringInterface("BC", "X");

    assertEquals("XDE", replaced.sourceAsString());

    // ❗ ここが v2.0.2 の限界
    // 起源をどう扱うべきかが未定義
    // parent があるのか？ offset は？
    // → attach/detach を考えた理由そのもの
  }
  
  @Test
  public void split_creates_sources_with_ambiguous_origin() {
    Source root = StringSource.createRootSource("AA-BB-CC");

    Source[] parts = root.splitAsStringInterface("-");

    assertEquals(3, parts.length);
    assertEquals("AA", parts[0].sourceAsString());
    assertEquals("BB", parts[1].sourceAsString());
    assertEquals("CC", parts[2].sourceAsString());

    // ここで問いたいこと：
    // parts[1] の offsetFromRoot は？
    // parent は root？
    //
    // v2.0.2 では「なんとなく動く」が
    // 意味論的には attach ではない
  }
  
  @Test
  public void codePointIndex_handles_surrogate_pairs() {
    // 😀 は surrogate pair
    Source root = StringSource.createRootSource("A😀B");

    // "😀"
    Source emoji = root.subSource(
        new CodePointIndex(1),
        new CodePointIndex(2)
    );

    assertEquals("😀", emoji.sourceAsString());

    assertEquals(
        new CodePointOffset(1),
        emoji.offsetFromRoot()
    );
  }
  
  @Test
  public void reRoot_resets_coordinate_system() {
    Source root = StringSource.createRootSource("ABCDE");
    Source sub  = root.subSource(new CodePointIndex(1), new CodePointIndex(4)); // "BCD"

    Source newRoot = sub.reRoot(s -> s.replace("BC", "X")); // "XD"

    assertTrue(newRoot.isRoot());
    assertEquals("XD", newRoot.sourceAsString());
    assertEquals(new CodePointOffset(0), newRoot.offsetFromRoot());
  }

}
