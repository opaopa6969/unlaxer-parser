package org.unlaxer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.PseudoRootParser;

public class TaggableTest {

	@Test
	public void test() {
		Tag foo = Tag.of("foo");
		Tag bar = Tag.of("bar");
		
		Parser parser = new PseudoRootParser();
		parser.addTag(foo);
		assertTrue(parser.hasTag(foo));
		assertFalse(parser.hasTag(bar));
		
		parser.removeTag(foo);
		
		assertFalse(parser.hasTag(foo));
	}

}
