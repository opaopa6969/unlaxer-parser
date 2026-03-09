package org.unlaxer;

import org.unlaxer.util.SimpleBuilder;

public enum SymbolMap{
  crlf(0x21b2,13,10),
  tab(0x21a6,9),
  lf(0x21e3,10),
  cr(0x21e0,13),
  ;
  public int[] codes;
  public int arrowSymbol;

  private SymbolMap(int arrowSymbol,int...codes) {
    this.codes = codes;
    this.arrowSymbol = arrowSymbol;
  }
  public static String replaceSymbol(String original , SymbolMap lineTerminator) {
    if(lineTerminator == tab) {
      throw new IllegalArgumentException();
    }
    StringBuilder buillder = new StringBuilder();
    int[] codePoints = original.codePoints().toArray();
    
    int codePointCount = codePoints.length;
    for (int i = 0; i < codePointCount; i++) {
      int codePointAt = codePoints[i];
      if(codePointAt == tab.codes[0]) {
        buillder.appendCodePoint(tab.arrowSymbol);
      }else if(codePointAt == lf.codes[0]) {
        buillder.appendCodePoint(lf.arrowSymbol);
        for(int code:lineTerminator.codes) {
          buillder.appendCodePoint(code);            
        }
      }else if(codePointAt == cr.codes[0]) {
        if(codePointCount-1!=i && codePoints[i+1] ==lf.codes[0]) {
          buillder.appendCodePoint(crlf.arrowSymbol);
          i++;
        }else {
          buillder.appendCodePoint(cr.arrowSymbol);
        }
        for(int code:lineTerminator.codes) {
          buillder.appendCodePoint(code);            
        }
      }else {
        buillder.appendCodePoint(codePointAt);
      }
    }
    return buillder.toString();
  }
  
  public static Source replaceSymbol(Source original , SymbolMap lineTerminator) {
    if(lineTerminator == tab) {
      throw new IllegalArgumentException();
    }
    SimpleBuilder buillder = new SimpleBuilder();
    int[] codePoints = original.codePoints().toArray();
    
    int codePointCount = codePoints.length;
    for (int i = 0; i < codePointCount; i++) {
      int codePointAt = codePoints[i];
      if(codePointAt == tab.codes[0]) {
        buillder.appendCodePoint(tab.arrowSymbol);
      }else if(codePointAt == lf.codes[0]) {
        buillder.appendCodePoint(lf.arrowSymbol);
        for(int code:lineTerminator.codes) {
          buillder.appendCodePoint(code);            
        }
      }else if(codePointAt == cr.codes[0]) {
        if(codePointCount-1!=i && codePoints[i+1] ==lf.codes[0]) {
          buillder.appendCodePoint(crlf.arrowSymbol);
          i++;
        }else {
          buillder.appendCodePoint(cr.arrowSymbol);
        }
        for(int code:lineTerminator.codes) {
          buillder.appendCodePoint(code);            
        }
      }else {
        buillder.appendCodePoint(codePointAt);
      }
    }
    return buillder.toSource();
  }
  
}