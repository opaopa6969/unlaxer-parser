package org.unlaxer;

import static org.junit.Assert.*;

import org.junit.Test;
import org.unlaxer.util.SimpleBuilder;

public class SubPositionResolverImplTest {

  @Test
  public void test() {
    
    SimpleBuilder builder = new SimpleBuilder();
    
    SimpleBuilder line = builder
      .line("0123ABC789")//11
      .line("0123ABC789");//22
    
    StringSource rootSource = StringSource.createRootSource(line.toString());
    CursorRange cursorRange = rootSource.cursorRange();
    System.out.println(cursorRange);
    assertEquals(0, cursorRange.endIndexExclusive.positionInLine().value());
    assertEquals(22, cursorRange.endIndexExclusive.positionInRoot().value());
  }

}
