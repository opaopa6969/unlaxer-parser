package org.unlaxer.util;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.posix.AlphabetParser;

public class SingletonsTest {

	@Test
	public void test() {
		AlphabetParser singleton = Singletons.get(AlphabetParser.class);
		Parsed parse = singleton.parse(new ParseContext(StringSource.createRootSource("abcde")));
		assertTrue(parse.isSucceeded());
	}

}
