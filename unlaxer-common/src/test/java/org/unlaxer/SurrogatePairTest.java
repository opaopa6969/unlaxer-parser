package org.unlaxer;

public class SurrogatePairTest {
  
  
  public static void main(String[] args) {
    String surrogatePare = "abc𪛊";
    System.out.println(surrogatePare.offsetByCodePoints(1,0));
    System.out.println(surrogatePare.codePointBefore(1));
    System.out.println(surrogatePare.codePointAt(1));
    
  }
}
