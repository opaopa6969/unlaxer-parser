package org.unlaxer.reducer;

import org.unlaxer.Committed;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.TokenList;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.MetaFunctionParser;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.PseudoRootParser;
import org.unlaxer.util.Singletons;

public abstract class AbstractTokenReducer implements CommittedReducer {
	
	public abstract boolean doReduce(Parser parser);


	public Token reduce(Committed committed) {
		Token token = committed.isCollected() ? //
				committed.getTokenOptional().get() : //
				new Token(//
						TokenKind.consumed, //
						committed.getOriginalTokens(), //
						Singletons.get(PseudoRootParser.class) //
				);

		// TokenPrinter.output(token, System.out, 0, DetailLevel.detail,
		// true);

		TokenList children = new TokenList();

		if (doReduce(token.getParser())) {
			PseudoRootParser root = new PseudoRootParser();
			root.getChildren().add(token.parser);
			Token newRootToken = new Token(//
					token.getTokenKind(), //
					token.getSource(), //
					root);
			newRootToken.addChildren(token);
			token = newRootToken;
		}

		for (Token childToken : token.getAstNodeChildren()) {
			if(childToken.source.isEmpty()) {
				continue;
			}
			children.addAll(reduce(childToken));
		}
		token.getAstNodeChildren().clear();
		token.getAstNodeChildren().addAll(children);

		// TokenPrinter.output(token, System.out, 0, DetailLevel.detail,
		// true);

		return token;
	}

	TokenList reduce(Token token) {

		// TokenPrinter.output(token, System.out, 0, DetailLevel.detail,
		// false);
		// System.out.println();

		if (token.getAstNodeChildren().isEmpty()) {
			return reduceWithLeaf(token);
		}
		TokenList tokens = new TokenList();

		token.getAstNodeChildren().stream().map(this::reduce)
			.forEach(tokens::addAll);

		if (doReduce(token.parser)) {
			return tokens;
		}
		token.getAstNodeChildren().clear();
		token.getAstNodeChildren().addAll(tokens);
		TokenList tokenContainer = new TokenList();
		tokenContainer.add(token);
		return tokenContainer;

	}

	private TokenList reduceWithLeaf(Token token) {

	  TokenList tokens = new TokenList();
		if (doReduce(token.parser)) {
			return tokens;
		}

		Parsers childParsers = token.parser.getChildren();

		if (childParsers.isEmpty()) {
			if (false == doReduce(token.parser)) {
				tokens.add(token);
			}
			return tokens;
		}

		Parsers parsers = new Parsers();
		for (Parser childParser : childParsers) {
			parsers.addAll(reduce(childParser));
		}

		token.parser.getChildren().clear();
		token.parser.getChildren().addAll(parsers);
		tokens.add(token);

		return tokens;
	}

	private Parsers reduce(Parser parser) {

		Parsers parsers = new Parsers();

		if (false == parser instanceof MetaFunctionParser) {
			parsers.add(parser);
		}
		ChildOccurs childOccurs = parser.getChildOccurs();
		if (childOccurs.isSingle()) {

			parsers.addAll(reduce(parser.getChild()));

		} else if (childOccurs.isMulti()) {

			for (Parser childParser : parser.getChildren()) {
				parsers.addAll(reduce(childParser));
			}
		}
		return parsers;
	}

}