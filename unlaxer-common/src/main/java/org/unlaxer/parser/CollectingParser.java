package org.unlaxer.parser;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.TokenList;



public interface CollectingParser extends Parser {
	
	public default Token collect(List<Token> tokens, TokenKind tokenKind ,
			Predicate<Token> tokenFilter){
			
		TokenList collect = TokenList.of( 
		    tokens.stream()
					.filter(tokenFilter)
					.collect(Collectors.toList()));
		
    return new Token(tokenKind,
				collect
				, this //
				);

	}
	
	public default Token collect(List<Token> tokens, TokenKind tokenKind){
		return collect(tokens, tokenKind , token->true);
	}
}
