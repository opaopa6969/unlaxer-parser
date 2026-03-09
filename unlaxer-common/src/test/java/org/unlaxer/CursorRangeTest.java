package org.unlaxer;

import static org.junit.Assert.*;

import org.junit.Test;
import org.unlaxer.Source.SourceKind;

public class CursorRangeTest {

  @Test
  public void test() {
    
    Source source = StringSource.createRootSource("abc\nabc");
    PositionResolver positionResolver = source.positionResolver();
    
    CodePointIndex position0 = new CodePointIndex(0);
    CodePointIndex position4 = new CodePointIndex(4);
    CodePointIndex position7 = new CodePointIndex(7);
    CursorRange cursorRange0 = CursorRange.of(
        position0,
        position4,
        CodePointOffset.ZERO,
        SourceKind.subSource,
        positionResolver);
        
    CursorRange cursorRange1 = CursorRange.of(
        position4,
        position7,
        CodePointOffset.ZERO,
        SourceKind.subSource,
        positionResolver);
    
    assertTrue(cursorRange0.lt(cursorRange1));
    assertTrue(cursorRange0.lessThan(cursorRange1));
    
    assertTrue(cursorRange1.graterThan(cursorRange0));
    assertTrue(cursorRange1.gt(cursorRange0));

  }

}
