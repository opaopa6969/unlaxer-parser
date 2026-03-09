package org.unlaxer.parser;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.unlaxer.ast.ASTNodeKind;
import org.unlaxer.parser.ParserFactoryByClass.ClassAndKind;
import org.unlaxer.parser.elementary.SpaceDelimitor;

public class ParserFactoryByClassTest {

	@Test
	public void test() {
		
		ClassAndKind classAndKind1 = new ClassAndKind( ASTNodeKind.ChoicedOperand , SpaceDelimitor.class);
		ClassAndKind classAndKind2 = new ClassAndKind(ASTNodeKind.ChoicedOperand , SpaceDelimitor.class);
		
		assertEquals(classAndKind1.hashCode, classAndKind2.hashCode);
		
//		SpaceDelimitor oneOrMore1 = Parser.get(ASTNodeKind.OneOrMoreOperatorOperandSuccessor , SpaceDelimitor.class);
//		SpaceDelimitor oneOrMore3 = Parser.get(ASTNodeKind.OneOrMoreOperatorOperandSuccessor , SpaceDelimitor.class);
//		
//		SpaceDelimitor oneOrMore2= Parser.get(ASTNodeKind.Other , SpaceDelimitor.class);
		
//		assertEquals(oneOrMore1, oneOrMore3);
//		assertNotEquals(oneOrMore1, oneOrMore2);
	}

}
