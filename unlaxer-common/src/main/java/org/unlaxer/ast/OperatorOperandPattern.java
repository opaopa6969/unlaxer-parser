package org.unlaxer.ast;

public enum OperatorOperandPattern{
	
	/**
	 * 	source : "1+2"
	 * 
 	 *   (Plus)    <-operator node  (self)
	 *    /  \
	 *  (1)  (2)   <-operands nodes (child)
	 */
	Tree(HierarchyLevel.self,HierarchyLevel.child),
	
	/**
	 * 	source : "1+2+3"
	 * 
 	 *   (Plus)  (1)  (2)  (3) (child)
	 */
	FlatOneOperator(HierarchyLevel.child,HierarchyLevel.child),
	
	/**
	 * 	source : "1+2-3+4"
	 * 
 	 *   (Plus)  (1)  (2)  (minus) (3) 　(Plus)  (4) (child)
	 */
	FlatSomeOperatorAndOperands(HierarchyLevel.child,HierarchyLevel.child),
	;
	HierarchyLevel operatorLevel;
	HierarchyLevel operandLevel;

	private OperatorOperandPattern(HierarchyLevel operatorLevel, HierarchyLevel operandLevel) {
		this.operatorLevel = operatorLevel;
		this.operandLevel = operandLevel;
	}
	public HierarchyLevel operatorLevel() {
		return operatorLevel;
	}
	public HierarchyLevel operandLevel() {
		return operandLevel;
	}
	
}
