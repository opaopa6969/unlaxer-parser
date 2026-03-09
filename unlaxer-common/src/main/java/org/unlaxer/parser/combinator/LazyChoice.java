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

public abstract class LazyChoice extends LazyCombinatorParser implements ChoiceInterface , LazyParserChildrenSpecifier{

	private static final long serialVersionUID = 1346285131943887894L;

	public LazyChoice() {
		super();
	}

	public LazyChoice(Name name) {
		super(name);
	}
	
	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		return ChoiceInterface.super.parse(parseContext, tokenKind, invertMatch);
	}

	@Override
	public HasChildrenParser createWith(Parsers children) {
		return new LazyChoice(getName()) {
			
			private static final long serialVersionUID = 6650564064744230492L;

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