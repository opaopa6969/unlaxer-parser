package org.unlaxer.ast;

import static org.junit.Assert.*;

import org.junit.Test;
import org.unlaxer.Tag;

public class ASTMapperTest {

	@Test
	public void test() {
		
		Tag tag1 = ASTNodeKind.ChoicedOperand.tag();
		Tag tag2 = ASTNodeKind.ChoicedOperand.tag();
		
		assertEquals(tag1.hashCode(), tag2.hashCode());
		
	}

}
