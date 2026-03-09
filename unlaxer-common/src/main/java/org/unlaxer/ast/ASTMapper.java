package org.unlaxer.ast;

import org.unlaxer.Token;

public interface ASTMapper {
	

	//以下の二つのenumを組み合わせて定義を行おうかと思ったが、ちょっと凝りすぎなので
	//この二つを組み合わせてフラットにする
	/*
	public enum ASTNodeKind{
		Operator,
		Operand,
		Space,
		Comment,
		Other,
		;
		public ASTNodeDefenition create(ASTCombinator astCombinator) {
			return new ASTNodeDefenition(astCombinator, this);
		}
	}
	
	public enum ASTCombinator{
		zero,
		one,
		two,
		optional,
		zeroOrMore,
		oneOrMore,
		choice,
		chain,
		interleave,
		;
		public ASTNodeDefenition create(ASTNodeKind astNodeKind) {
			return new ASTNodeDefenition(this, astNodeKind);
		}
	}
	 */


	//この定義の要らない。
	//Parserに直接Tag、もしくはASTNodeKindをつけることで対応する
	/*
	public static class ASTNodeDefenition{
		
		public final ASTCombinator astcombinator;
		public final ASTNodeKind astNodeKind;
		List<ASTNodeDefenition> childASTNodeDefenition;
		ASTNodeDefenition parentASTNodeDefenition;
		
		public ASTNodeDefenition(ASTCombinator astcombinator, ASTNodeKind astNodeKind) {
			super();
			this.astcombinator = astcombinator;
			this.astNodeKind = astNodeKind;
			this.childASTNodeDefenition = new ArrayList<>();
		}
		
		public Optional<ASTNodeDefenition> parent(){
			return Optional.of(parentASTNodeDefenition);
		}
	}
	
	
	public enum OperatorOperandOrder{
		Operand_Operator_Operand
		;
		List<ASTNodeDefenition> astNodedeDefenitions;

		private OperatorOperandOrder(List<ASTNodeDefenition> astNodedeDefenitions) {
			this.astNodedeDefenitions = astNodedeDefenitions;
		}

		private OperatorOperandOrder(ASTNodeDefenition... astNodedeDefenitions) {
			this.astNodedeDefenitions = List.of(astNodedeDefenitions);
		}
	}
	*/
	
	/**
	 * example:
	 * 
	 *  Tree pattern
	 * 
	 * 	"1+2" -> parsed 
	 *   list ['1' (Number) , '+' (Plus) , '2' (Number)] -> toASTToken
	 *    
	 *   (Plus)    <-operator node  (root)
	 *    /  \
	 *  (1)  (2)   <-operands nodes (leaf)
	 *  
	 *  
	 *  Flat pattern ( one operator and some operands)
	 *  
  	 * 	"1+2+3" -> parsed 
	 *   list ['1' (Number) , '+' (Plus) , '2' (Number), '+' (Plus) , '3' (Number)] -> toASTToken
	 *   
	 *   (+)  (1)  (2)  (3) 
	 *   
	 *  Flat pattern ( some operator and some operands)
	 *  
  	 * 	"1+2-3" -> parsed 
	 *   list ['1' (Number) , '+' (Plus) , '2' (Number), '-' (Minus) , '3' (Number)] -> toASTToken
	 *   
	 *   (-)  (+)  (1)  (2)  (3) 
	 * 
	 * @param context for AST mapper for other token
	 * @param parsedToken
	 * @return operator-operand tree or operator operand list
	 */
	Token toAST(ASTMapperContext context, Token parsedToken);
	
	default boolean canASTMapping(Token parsedToken) {
		return parsedToken.parser.getClass() == getClass();
	}

}
