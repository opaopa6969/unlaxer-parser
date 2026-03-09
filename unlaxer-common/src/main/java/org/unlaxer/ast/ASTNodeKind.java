package org.unlaxer.ast;

import org.unlaxer.Tag;

public enum ASTNodeKind{
	Operator,
	Operand,
	ChoicedOperatorRoot,
	ChoicedOperator,
	ChoicedOperandRoot,
	ChoicedOperand,
	ZeroOrMoreOperatorOperandSuccessor,
	OneOrMoreOperatorOperandSuccessor,
	ZeroOrMoreChoicedOperatorOperandSuccessor,
	OneOrMoreChoicedOperatorOperandSuccessor,
	ZeroOrMoreOperandSuccessor,
	OneOrMoreOperandSuccessor,
	ZeroOrMoreOperatorSuccessor,
	OneOrMoreOperatorSuccessor,
	Space,
	Comment,
	Annotation,
	AnnotationAttribute,
	Other,
	NotSpecified,
	;
  
  Tag tag;
  String description;
	
	private ASTNodeKind() {
    this.tag = Tag.of(this);
    this.description = name();
  }
	
	 private ASTNodeKind(String description) {
    this.tag = Tag.of(this);
    this.description = description;
  }

  public Tag tag() {
    return tag;
	}
  
  public String description() {
    return description;
  }
}