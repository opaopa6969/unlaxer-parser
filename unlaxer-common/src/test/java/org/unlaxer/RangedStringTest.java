package org.unlaxer;

import static org.junit.Assert.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.Test;

public class RangedStringTest {

  @Test
  public void test() {
    
    String word ="a";
    
    String surrogatePare = "𪛊";
    
    String cr = new String( new byte[] {0x0d});
    String lf = new String(new byte[] {0x0a});
    String crlf = new String(new byte[] {0x0d , 0x0a});
    
    String lines =
        word + cr + 
        word + crlf + 
        word + lf + 
        word + crlf+
        lf+
        cr+
        surrogatePare+cr+
        surrogatePare+crlf+
        surrogatePare+lf+
        surrogatePare
        ;
    
    System.out.println(lines);
    System.out.println(SymbolMap.replaceSymbol(lines, SymbolMap.lf));
    
    Source  source = StringSource.createRootSource(lines);
    
    List<Source> sources = source.linesAsSource().collect(Collectors.toList());
    
    System.out.println("size = " +sources.size());
    
    AtomicInteger counter = new AtomicInteger();
    
    sources.stream().forEach(x->{
      int andIncrement = counter.getAndIncrement();
      
      System.out.println(andIncrement + ": " + x);
      
    });
    
    
    
    assertEquals(word+cr, sources.get(0).toString());
    assertEquals(word+crlf, sources.get(1).toString());
    assertEquals(word+lf, sources.get(2).toString());
    assertEquals(word+crlf, sources.get(3).toString());
    assertEquals(lf, sources.get(4).toString());
    assertEquals(cr, sources.get(5).toString());
    assertEquals(surrogatePare+cr, sources.get(6).toString());
    assertEquals(surrogatePare+crlf, sources.get(7).toString());
    assertEquals(surrogatePare+lf, sources.get(8).toString());
    assertEquals(surrogatePare, sources.get(9).toString());
  }
  /*
      a⇠[[L:0,X:0,P:0],[L:11,X:0,P:20]]
      a⇠⇣[[L:0,X:3,P:3],[L:11,X:0,P:20]]
      a⇠⇣[[L:1,X:2,P:5],[L:11,X:0,P:20]]
      a⇣[[L:2,X:1,P:6],[L:11,X:0,P:20]]
      a⇣a⇠⇣[[L:3,X:1,P:7],[L:11,X:0,P:20]]
      a⇣a⇠⇣⇣[[L:4,X:3,P:10],[L:11,X:0,P:20]]
      a⇣a⇠⇣⇣[[L:5,X:1,P:11],[L:11,X:0,P:20]]
      ⇠[[L:6,X:1,P:12],[L:11,X:0,P:20]]
      છ?⇠[[L:7,X:1,P:13],[L:11,X:0,P:20]]
      છ?⇠⇣[[L:8,X:3,P:16],[L:11,X:0,P:20]]
      છ?⇠⇣[[L:9,X:3,P:19],[L:11,X:0,P:20]]
      */
  
  public static void main(String[] args) {
    String surrogatePare = "𪛊";
    
    String text= surrogatePare+"a"+surrogatePare+"a";
    
    int[] array = text.codePoints().toArray();
    for (int i : array) {
      System.out.println(i);
    }
    
    System.out.println(surrogatePare.codePointBefore(0));
  }
}
