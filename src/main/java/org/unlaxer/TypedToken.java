package org.unlaxer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.unlaxer.parser.Parser;

public class TypedToken<P extends Parser> extends Token{
	
	P parser;

	// 必要になったら実装を行う
//  public TypedToken(TokenKind tokenKind, TokenList tokens, P parser, int position) {
//    super(tokenKind, tokens, parser, position);
//    this.parser = parser;
//  }
	
  public TypedToken(TokenKind tokenKind, TokenList tokens, P parser ){
    super(tokenKind, tokens, parser);
    this.parser = parser;
  }

	public TypedToken(TokenKind tokenKind, Source token, P parser, TokenList children) {
		super(tokenKind, token, parser, children);
		this.parser = parser;
	}

	public TypedToken(TokenKind tokenKind, Source token, P parser) {
		super(tokenKind, token, parser);
		this.parser = parser;
	}
	
	public TypedToken(Token token , P parser) {
		super(
			token.tokenKind, 
			token.source,
			parser,
			token.getOriginalChildren()
		);
		this.parser = parser;
	}
	public TypedToken<P> setParent(Token parentToken) {
		parent = Optional.ofNullable(parentToken);
		return this;
	}

	public TypedToken<P> setParent(Optional<Token> parentToken) {
		parent = parentToken;
		return this;
	}

	@SuppressWarnings("unchecked")
	public P getParser() {
		return (P) super.getParser();
	}
	
	@SuppressWarnings("unchecked")
	public Class<? extends P> getParserClass(){
		return (Class<? extends P>) getParser().getClass();
	}

	public TypedToken<P> newCreatesOfTyped(TokenList newChildrens) {
		return super.newCreatesOf(newChildrens).typed(parser);
	}
	
	@Deprecated
	public TypedToken<P> newCreatesOfTyped(List<? extends Token> newChildrens) {
		return super.newCreatesOf(TokenList.of(new ArrayList<>(newChildrens))).typed(parser);
	}

	public TypedToken<P> newCreatesOfTyped(Token... newChildrens) {
		return super.newCreatesOf(newChildrens).typed(parser);
	}

	public TypedToken<P> newCreatesOfTyped(@SuppressWarnings("unchecked") Predicate<Token>... filterForChildrens) {
		return super.newCreatesOf(filterForChildrens).typed(parser);
	}

	public TypedToken<P> newCreatesOfTyped(TokenEffecterWithMatcher... tokenEffecterWithMatchers) {
		return super.newCreatesOf(tokenEffecterWithMatchers).typed(parser);
	}

	public TypedToken<P> newCreatesOfTyped(ChildrenKind kind, TokenEffecterWithMatcher... tokenEffecterWithMatchers) {
		return super.newCreatesOf(kind, tokenEffecterWithMatchers).typed(parser);
	}

	public TypedToken<P> getDirectAncestorTyped(Predicate<Token> predicates) {
		return super.getDirectAncestor(predicates).typed(parser);
	}

	public Optional<TypedToken<P>> getDirectAncestorAsOptionalTyped(Predicate<Token> predicates) {
		return super.getDirectAncestorAsOptional(predicates).map(token->token.typed(parser));
	}

	public TypedToken<P> getAncestorTyped(Predicate<Token> predicates) {
		return super.getAncestor(predicates).typed(parser);
	}

	public Optional<TypedToken<P>> getAncestorAsOptionalTyped(Predicate<Token> predicates) {
		return super.getAncestorAsOptional(predicates).map(token->token.typed(parser));
	}

	public TypedToken<P> getChildTyped(Predicate<Token> predicates) {
		return super.getChild(predicates).typed(parser);
	}

	public TypedToken<P> getChildTyped(Predicate<Token> predicates, ChildrenKind childrenKind) {
		return super.getChild(predicates, childrenKind).typed(parser);
	}

	public TypedToken<P> getChildWithParserTyped(Predicate<Parser> predicatesWithTokensParser) {
		return super.getChildWithParser(predicatesWithTokensParser).typed(parser);
	}

	public TypedToken<P> getChildWithParserTyped(Predicate<Parser> predicatesWithTokensParser, ChildrenKind childrenKind) {
		return super.getChildWithParser(predicatesWithTokensParser, childrenKind).typed(parser);
	}



	public Optional<TypedToken<P>> getChildAsOptionalTyped(Predicate<Token> predicates) {
		return super.getChildAsOptional(predicates).map(token->token.typed(parser));
	}

	public Optional<TypedToken<P>> getChildAsOptionalTyped(Predicate<Token> predicates, ChildrenKind childrenKind) {
		return super.getChildAsOptional(predicates, childrenKind).map(token->token.typed(parser));
	}

	public Optional<TypedToken<P>> getChildWithParserAsOptionalTyped(Predicate<Parser> predicatesWithTokensParser) {
		return super.getChildWithParserAsOptional(predicatesWithTokensParser).map(token->token.typed(parser));
	}

	public Optional<TypedToken<P>> getChildWithParserAsOptionalTyped(Predicate<Parser> predicatesWithTokensParser,
			ChildrenKind childrenKind) {
		return super.getChildWithParserAsOptional(predicatesWithTokensParser, childrenKind).map(token->token.typed(parser));
	}
}
