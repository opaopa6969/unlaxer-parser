package org.unlaxer.parser.combinator;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.HasChildrenParser;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

public class Chain extends ConstructedCombinatorParser implements ChainInterface{

	private static final long serialVersionUID = 6972883578352108945L;

	public Chain(Parsers children) {
		super(children);
	}

	@SafeVarargs
	public Chain(Parser... children) {
		super(children);
	}
	
	public Chain(Name name, Parsers children) {
		super(name, children);
	}

	public Chain(Name name, Parser... children) {
		super(name, children);
	}
	
	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		return ChainInterface.super.parse(parseContext, tokenKind, invertMatch);
	}
	
	@Override
	public Chain newFiltered(Predicate<Parser> cutFilter){
		
		Predicate<Parser> passFilter = cutFilter.negate();
		List<Parser> newChildren = getChildren().stream()
			.filter(passFilter)
			.collect(Collectors.toList());
		
		return new Chain(getName() , Parsers.of(newChildren));
	}

	@Override
	public HasChildrenParser createWith(Parsers children) {
		return new Chain(getName() , children);
	}
}
