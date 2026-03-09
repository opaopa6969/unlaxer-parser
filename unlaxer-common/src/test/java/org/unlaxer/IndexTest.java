package org.unlaxer;

import static org.junit.Assert.*;

import org.junit.Test;

public class IndexTest {

  @Test
  public void test() {
    
    assertEquals(8 , new Index(3).newWithAdd(5).value());
    assertEquals(8 , new Index(3).newWithAdd(new CodePointIndex(5)).value());
    
  }

}
