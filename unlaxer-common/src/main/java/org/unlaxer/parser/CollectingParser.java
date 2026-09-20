package org.unlaxer.parser;

import java.util.List;
import java.util.function.Predicate;

import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.TokenList;



public interface CollectingParser extends Parser {
	
	public default Token collect(List<Token> tokens, TokenKind tokenKind ,
			Predicate<Token> tokenFilter){
			
		// Runs on every commit: collect into the child list directly instead of building a
		// Stream pipeline, an intermediate List and a copy of it.
		int size = tokens.size();
		TokenList collect = new TokenList(size);
		for (int i = 0; i < size; i++) {
			Token token = tokens.get(i);
			if (tokenFilter.test(token)) collect.add(token);
		}
		return new Token(tokenKind, collect, this);

	}
	
	public default Token collect(List<Token> tokens, TokenKind tokenKind){
		return collect(tokens, tokenKind , token->true);
	}
}
