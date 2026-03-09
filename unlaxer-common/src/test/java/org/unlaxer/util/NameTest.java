package org.unlaxer.util;

import static org.junit.Assert.*;

import org.junit.Test;
import org.unlaxer.Name;

public class NameTest {
	
	public enum Acme{
		foo,
		bar
		;
	}

	@Test
	public void test() {
		
		assertTrue(Name.of(Acme.foo).equals(Name.of(Acme.foo)));
	}

}
