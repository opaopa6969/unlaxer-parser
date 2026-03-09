package org.unlaxer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

import org.unlaxer.parser.MetaFunctionParser;
import org.unlaxer.parser.PseudoRootParser;
import org.unlaxer.reducer.CommittedReducer;
import org.unlaxer.reducer.MetaFunctionTokenReducer;
import org.unlaxer.util.Singletons;

public class Committed implements Serializable {

	private static final long serialVersionUID = -5413329267448242699L;

	TokenList originalTokens;
	Optional<Token> collectedToken;
	boolean collected;
	
	Collection<TransactionElement> mostConsumeds = new ArrayList<>();

	public Committed(Committed committed) {
		this.originalTokens = TokenList.of(committed.getOriginalTokens());
		this.collectedToken = committed.getTokenOptional();
		this.collected = committed.isCollected();
	}

	public Committed(TokenList originalTokens) {
		super();
		this.originalTokens = TokenList.of(originalTokens);
		collected = false;
		collectedToken = Optional.empty();
	}

	public Committed(Token token, TokenList originalTokens) {
		super();
		this.collectedToken = Optional.of(token);
		this.originalTokens = TokenList.of(originalTokens);
		collected = true;
	}

	public Committed() {
		super();
		this.collectedToken = Optional.empty();
		collected = false;
		originalTokens = new TokenList();
	}

	public Optional<Token> getTokenOptional() {
		return collectedToken;
	}

	public TokenList getOriginalTokens() {
		return originalTokens;
	}

	public boolean isCollected() {
		return collected;
	}

	@SuppressWarnings("unchecked")
	public <T> T get() {
		return (T) (collected ? collectedToken.get() : originalTokens);
	}

	public boolean isEmpty() {
		return originalTokens.isEmpty();
	}
	
	public Token getConsumed(){
	  
		TokenList collect =TokenList.of(
		    originalTokens.stream()
  		    .filter(TokenKind.consumed.passFilter)
  		    .collect(Collectors.toList())
		 );
    return new Token(
				TokenKind.consumed, 
				collect,
				collected ? 
						collectedToken.get().parser:
							Singletons.get(PseudoRootParser.class));
	}

	
	/**
	 * @return root token. if collected == false , then create Chained token with original tokens
	 */
	public Token getRootToken(){
		return getRootToken(false);
	}
	
	/**
	 * @param reduceMetaFunctionParser if parameter == true , reduce {@link MetaFunctionParser}
	 * @return root token. if collected == false , then create Chained token with original tokens
	 */
	public Token getRootToken(boolean reduceMetaFunctionParser){
		if(reduceMetaFunctionParser){
			return new MetaFunctionTokenReducer().reduce(this);
		}
		Token token = collected ? 
			collectedToken.get() : 
			new Token(TokenKind.consumed, originalTokens, Singletons.get(PseudoRootParser.class));
		return token;
	}

	
	public Token getRootToken(CommittedReducer committedReducer){
		return committedReducer.reduce(this);
	}
	
	
}