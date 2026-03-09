package org.unlaxer.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.unlaxer.Range;


public class StringUtilTest {

	@Test
	public void testDelete() {
		int position = -1;
		String word = "A0123";
		String[]  expecteds = new String[]{"A0123",  "0123",  "A123",  "A023",  "A013",  "A012",  "A0123",  "A0123"}; 
		for(String expected :expecteds ){
			String actual = StringUtil.delete(word, position++);
			assertEquals(expected, actual);
		}
	}
	
	@Test
	public void testDeleteWithRange() {
		String word = "01234";
		
		assertEquals("", StringUtil.delete(word, new Range(0,6)));
		assertEquals("0", StringUtil.delete(word, new Range(1,6)));
		assertEquals("0", StringUtil.delete(word, new Range(1,5)));
		assertEquals("04", StringUtil.delete(word, new Range(1,4)));
		assertEquals("014", StringUtil.delete(word, new Range(2,4)));
		assertEquals("0124", StringUtil.delete(word, new Range(3,4)));
		assertEquals("01234", StringUtil.delete(word, new Range(3,3)));
	}
	
	@Test
	public void testInsert() {
		String word = "01234";
		String insertion = "_A_";
		
		assertEquals("_A_01234", StringUtil.insert(word, insertion , 0));
		assertEquals("0_A_1234", StringUtil.insert(word, insertion , 1));
		assertEquals("01_A_234", StringUtil.insert(word, insertion , 2));
		assertEquals("012_A_34", StringUtil.insert(word, insertion , 3));
		assertEquals("0123_A_4", StringUtil.insert(word, insertion , 4));
		assertEquals("01234_A_", StringUtil.insert(word, insertion , 5));
	}
	
	@Test
	public void testDeleteAndInsert() {
		String word = "01234";
		String insertion = "_A_";
		
		assertEquals("_A_01234", StringUtil.deleteAndInsert(word, new Range(0,0), insertion));
		assertEquals("_A_1234", StringUtil.deleteAndInsert(word, new Range(0,1), insertion));
		assertEquals("0_A_234", StringUtil.deleteAndInsert(word, new Range(1,2), insertion));
		assertEquals("0_A_34", StringUtil.deleteAndInsert(word, new Range(1,3), insertion));
		assertEquals("0_A_4", StringUtil.deleteAndInsert(word, new Range(1,4), insertion));
		assertEquals("0_A_", StringUtil.deleteAndInsert(word, new Range(1,5), insertion));
		assertEquals("_A_", StringUtil.deleteAndInsert(word, new Range(0,5), insertion));
		assertEquals("_A_", StringUtil.deleteAndInsert(word, new Range(-1,6), insertion));
		
		assertEquals("012_A_4", StringUtil.deleteAndInsert(word, 3, insertion));

	}

	
}
