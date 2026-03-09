package org.unlaxer.expressiontree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.unlaxer.Kind;

public class ExpressionTreeNode {
	
	public enum OperatorOrOperand{
		operator,
		operand
	}
	
	public final OperatorOrOperand operatorOrOperand;
	
	public final Kind kind;
	
	public final Optional<Object> element;
	
	public final List<ExpressionTreeNode> children;

	ExpressionTreeNode(OperatorOrOperand operatorOrOperand, Kind kind, Optional<Object> element,
			List<ExpressionTreeNode> children) {
		super();
		this.operatorOrOperand = operatorOrOperand;
		this.kind = kind;
		this.element = element;
		this.children = Collections.unmodifiableList(children);
	}
	
	public static ExpressionTreeNode operatorOf(Kind kind , List<ExpressionTreeNode> children) {
		return new ExpressionTreeNode(OperatorOrOperand.operator, kind, Optional.empty(), children);
	}
	
	public static ExpressionTreeNode operatorOf(Kind kind , ExpressionTreeNode... children) {
		return new ExpressionTreeNode(OperatorOrOperand.operator, kind, Optional.empty(), Arrays.asList(children));
	}
	
	public static ExpressionTreeNode operatorOf(Kind kind) {
		return new ExpressionTreeNode(OperatorOrOperand.operator, kind, Optional.empty(), new ArrayList<>());
	}
	
	
	public static ExpressionTreeNode operandOf(Kind kind , Object element , List<ExpressionTreeNode> children) {
		return new ExpressionTreeNode(OperatorOrOperand.operand, kind, Optional.of(element), children);
	}
	
	public static ExpressionTreeNode operandOf(Kind kind , Object element , ExpressionTreeNode... children) {
		return new ExpressionTreeNode(OperatorOrOperand.operand, kind, Optional.of(element), Arrays.asList(children));
	}
	
	public static ExpressionTreeNode operandOf(Kind kind , Object element ) {
		return new ExpressionTreeNode(OperatorOrOperand.operand , kind, Optional.of(element), new ArrayList<>());
	}

	public boolean isOperator() {
		return operatorOrOperand == OperatorOrOperand.operator;
	}
	
	public boolean isOperand() {
		return operatorOrOperand == OperatorOrOperand.operand;
	}
	
	public boolean isLeaf() {
		return children.isEmpty();
	}
}
