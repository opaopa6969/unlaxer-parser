package org.unlaxer;

import org.junit.Test;

public class SpecialCharacterSymbolsTest {

  @Test
  public void test() {
    char CR  = (char) 0x0D;
    char LF  = (char) 0x0A; 
    char TAB  = (char) 0x09; 

    String text ="this is tab->"+TAB+"Next"+CR+LF;
    String replaceSymbol = SymbolMap.replaceSymbol(text , SymbolMap.crlf);
    System.out.println(text);
    System.out.println(replaceSymbol);
      
  }

}
