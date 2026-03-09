package org.unlaxer.ast;

import java.util.Optional;

import org.unlaxer.Token;

public interface ASTMapperContext{
	
	public static ASTMapperContext create(ASTMapper... astMappers) {
		
		return new ASTMapperContext() {
			
			@Override
			public Token toAST(Token parsedToken) {
				for (ASTMapper astMapper : astMappers) {
					if(astMapper.canASTMapping(parsedToken)) {
						Token ast = astMapper.toAST(this, parsedToken);
						return ast;
					}
				}
				return parsedToken;
			}

			@Override
			public Optional<Token> toASTexpectsMapping(Token parsedToken) {
				for (ASTMapper astMapper : astMappers) {
					if(astMapper.canASTMapping(parsedToken)) {
						Token ast = astMapper.toAST(this, parsedToken);
						return Optional.of(ast);
					}
				}
				return Optional.empty();
			}
		};
	}
	
	/**
	 * @param parsedToken
	 * @return if ASTMappers does not effect token, then return present token.
	 */
	Token toAST(Token parsedToken);
	
	/**
	 * @param parsedToken
	 * @return value is empty , ASTMappers does not effect token.
	 */
	Optional<Token> toASTexpectsMapping(Token parsedToken);
}