package org.unlaxer.ast;

public enum OperatorOperandPattern{
	
	/**
	 * 	source : "1+2"
	 * 
 	 *   (Plus)    <-operator node  (self)
	 *    /  \
	 *  (1)  (2)   <-operands nodes (child)
	 */
	Tree(HierarcyLevel.self,HierarcyLevel.child),
	
	/**
	 * 	source : "1+2+3"
	 * 
 	 *   (Plus)  (1)  (2)  (3) (child)
	 */
	FlatOneOperator(HierarcyLevel.child,HierarcyLevel.child),
	
	/**
	 * 	source : "1+2-3+4"
	 * 
 	 *   (Plus)  (1)  (2)  (minus) (3) 　(Plus)  (4) (child)
	 */
	FlatSomeOperatorAndOperands(HierarcyLevel.child,HierarcyLevel.child),
	;
	HierarcyLevel operatorLevel;
	HierarcyLevel operandLevel;
	
	private OperatorOperandPattern(HierarcyLevel operatorLevel, HierarcyLevel operandLevel) {
		this.operatorLevel = operatorLevel;
		this.operandLevel = operandLevel;
	}
	HierarcyLevel operatorLevel() {
		return operatorLevel;
	}
	HierarcyLevel operandLevel() {
		return operandLevel;
	}
	
}