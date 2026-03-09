package org.unlaxer.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.unlaxer.CodePointIndex;
import org.unlaxer.Range;
import org.unlaxer.Source;
import org.unlaxer.StringSource;


public class SourceUtilTest {

	@Test
	public void testDelete() {
		CodePointIndex position = new CodePointIndex(-1);
		Source word = StringSource.createRootSource("A0123");
		String[]  expecteds = new String[]{"A0123",  "0123",  "A123",  "A023",  "A013",  "A012",  "A0123",  "A0123"}; 
		for(String expected :expecteds ){
		  
//		  Source expectedSource = StringSource.createRootSource(expected);
		  
			String actual = SourceUtil.newWithDelete(word, position).sourceAsString();
			position = position.newWithAdd(1);
			assertEquals(expected, actual);
		}
	}
	

  @Test
  public void testDeleteWithRange() {
    Source word = StringSource.createRootSource("01234");
    
    assertEquals("", SourceUtil.newWithDelete(word, new Range(0,6)).sourceAsString());
    assertEquals("0", SourceUtil.newWithDelete(word, new Range(1,6)).sourceAsString());
    assertEquals("0", SourceUtil.newWithDelete(word, new Range(1,5)).sourceAsString());
    assertEquals("04", SourceUtil.newWithDelete(word, new Range(1,4)).sourceAsString());
    assertEquals("014", SourceUtil.newWithDelete(word, new Range(2,4)).sourceAsString());
    assertEquals("0124", SourceUtil.newWithDelete(word, new Range(3,4)).sourceAsString());
    assertEquals("01234", SourceUtil.newWithDelete(word, new Range(3,3)).sourceAsString());
  }
  
  @Test
  public void testInsert() {
    Source word = StringSource.createRootSource("01234");
    Source insertion =StringSource.createRootSource("_A_");
    
    assertEquals("_A_01234", SourceUtil.newWithInsert(word, insertion , new CodePointIndex(0)).sourceAsString());
    assertEquals("0_A_1234", SourceUtil.newWithInsert(word, insertion , new CodePointIndex(1)).sourceAsString());
    assertEquals("01_A_234", SourceUtil.newWithInsert(word, insertion , new CodePointIndex(2)).sourceAsString());
    assertEquals("012_A_34", SourceUtil.newWithInsert(word, insertion , new CodePointIndex(3)).sourceAsString());
    assertEquals("0123_A_4", SourceUtil.newWithInsert(word, insertion , new CodePointIndex(4)).sourceAsString());
    assertEquals("01234_A_", SourceUtil.newWithInsert(word, insertion , new CodePointIndex(5)).sourceAsString());
  }
  
  @Test
  public void testnewWithDeleteAndInsert() {
    Source word = StringSource.createRootSource("01234");
    Source insertion =StringSource.createRootSource("_A_");
    
    assertEquals("_A_01234", SourceUtil.newWithDeleteAndInsert(word, new Range(0,0), insertion).sourceAsString());
    assertEquals("_A_1234", SourceUtil.newWithDeleteAndInsert(word, new Range(0,1), insertion).sourceAsString());
    assertEquals("0_A_234", SourceUtil.newWithDeleteAndInsert(word, new Range(1,2), insertion).sourceAsString());
    assertEquals("0_A_34", SourceUtil.newWithDeleteAndInsert(word, new Range(1,3), insertion).sourceAsString());
    assertEquals("0_A_4", SourceUtil.newWithDeleteAndInsert(word, new Range(1,4), insertion).sourceAsString());
    assertEquals("0_A_", SourceUtil.newWithDeleteAndInsert(word, new Range(1,5), insertion).sourceAsString());
    assertEquals("_A_", SourceUtil.newWithDeleteAndInsert(word, new Range(0,5), insertion).sourceAsString());
    assertEquals("_A_", SourceUtil.newWithDeleteAndInsert(word, new Range(-1,6), insertion).sourceAsString());
    
    assertEquals("012_A_4", SourceUtil.newWithDeleteAndInsert(word, new CodePointIndex(3), insertion).sourceAsString());

  }
}
