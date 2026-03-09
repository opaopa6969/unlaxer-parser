package org.unlaxer;

import org.junit.Test;

public class SymbolMapTest {

  @Test
  public void test() {
    
//    String word ="a";
    String surrogatePare = "𪛊";
    
    String cr = new String( new byte[] {0x0d});
    String lf = new String(new byte[] {0x0a});
    String crlf = new String(new byte[] {0x0d , 0x0a});
    
    String lines =
        surrogatePare+cr+
        surrogatePare+crlf+
        surrogatePare+lf+
        surrogatePare
        ;
    
    System.out.println(lines);
    System.out.println(SymbolMap.replaceSymbol(lines, SymbolMap.lf));


  }

}
