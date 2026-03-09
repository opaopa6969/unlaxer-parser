package org.unlaxer.parser.combinator;

import java.util.Optional;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.RecursiveMode;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.HasChildrenParser;
import org.unlaxer.parser.LazyParserChildrenSpecifier;
import org.unlaxer.parser.Parsers;

public abstract class LazyChain extends LazyCombinatorParser implements ChainInterface , LazyParserChildrenSpecifier{

	private static final long serialVersionUID = -8543021717975687755L;

	public LazyChain() {
		super();
	}

	public LazyChain(Name name) {
		super(name);
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		return ChainInterface.super.parse(parseContext, tokenKind, invertMatch);
	}

	@Override
	public HasChildrenParser createWith(Parsers children) {
		
		return new LazyChain(getName()) {
			
			private static final long serialVersionUID = 8782702949441507130L;

			@Override
			public Parsers getLazyParsers() {
				return children;
			}
		};
	}

	@Override
	public Optional<RecursiveMode> getNotAstNodeSpecifier() {
		return Optional.empty();
	}
}