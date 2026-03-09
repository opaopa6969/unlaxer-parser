package org.unlaxer;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;
import org.unlaxer.util.SimpleBuilder;

public class StringSourceTest {

  @Test
  public void test() {
    
    StringSource stringSource = StringSource.createRootSource("ABC");
    
    Source peek = stringSource.peek(new CodePointIndex(1), new CodePointLength(0));
    
    CursorRange range = peek.cursorRange();
    
    System.out.println(range);
    String orElse = peek.toString();
    System.out.println("a:" + orElse);
    
    if(orElse.isBlank()) {
      
      System.out.println("blank!!");
    }
  }
  
  
  @Test
  public void testLines() {
    
    Source source = new SimpleBuilder()
      .line("ABC123")
      .line("123ABC")
      .line("ABC123")
      .toSource();
    
    List<Source> collect = source.linesAsSource().collect(Collectors.toList());
    collect.stream()
      .map(_source->_source.sourceAsString()+":"+source.cursorRange())
      .forEach(System.out::println);
    assertEquals(3, collect.size());
    
  }
  
  @Test
  public void testSubSource() {
    
    Source source = new SimpleBuilder()
      .append("ABC123")
      .toSource();
    
    Source subSource = source.subSource(new CodePointIndex(3), new CodePointIndex(6));
    
    assertEquals("123", subSource.sourceAsString());
    {
      CursorRange cursorRange = subSource.cursorRange();
      System.out.println(cursorRange);
      CodePointIndex startPosition = cursorRange.startIndexInclusive().position();
      CodePointIndex endPosition = cursorRange.endIndexExclusive().position();
      assertEquals(0, startPosition.value());
      assertEquals(3, endPosition.value());
    }
    
    {
      CursorRange cursorRange = subSource.cursorRange();
      System.out.println(cursorRange);
      CodePointIndex startPosition = cursorRange.startIndexInclusive().positionInRoot();
      CodePointIndex endPosition = cursorRange.endIndexExclusive().positionInRoot();
      assertEquals(3, startPosition.value());
      assertEquals(6, endPosition.value());
    }
    
  }
  
  
  public static void main(String[] args) {
    
    String word ="a";
    
    String surrogatePare = "𪛊";
    
//    String cr = new String( new byte[] {0x0d});
    String lf = new String(new byte[] {0x0a});
//    String crlf = new String(new byte[] {0x0d , 0x0a});
    
    String lines =
        word + lf + 
        surrogatePare
        ;
    
    StringSource createRootSource = StringSource.createRootSource(lines);
    
    List<Source> sources = createRootSource.linesAsSource().collect(Collectors.toList());
    
    for (Source source : sources) {
      
      System.out.println(source.sourceAsString());
      
    }
  }
}
