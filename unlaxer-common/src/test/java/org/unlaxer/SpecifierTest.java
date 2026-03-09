package org.unlaxer;

import static org.junit.Assert.*;

import org.junit.Test;
import org.unlaxer.util.collection.ID;

public class SpecifierTest {

	@Test
	public void test() {
		
		ID id1 = ID.of(String.class);
		ID id2 = ID.of(String.class);
		
		assertTrue(id1==id2);
		assertTrue(id1.equals(id2));
	}

}
