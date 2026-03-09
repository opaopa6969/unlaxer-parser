package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.ast.ASTNodeKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.HasChildrenParser;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

public class Choice extends ConstructedCombinatorParser implements ChoiceInterface{

	private static final long serialVersionUID = 1464495138641251351L;

	public Choice(Name name, Parsers parsers) {
		super(name, parsers);
	}

	public Choice(Parsers parsers) {
		super(parsers);
	}

	@SafeVarargs
	public Choice(Name name, Parser... parsers) {
		super(name, parsers);
	}

	@SafeVarargs
	public Choice(Parser... parsers) {
		super(parsers);
		setASTNodeKind(ASTNodeKind.ChoicedOperator);
	}
	
	@SafeVarargs
	public Choice(Class<? extends Parser>... parsers) {
		super(parsers);
		setASTNodeKind(ASTNodeKind.ChoicedOperator);
	}

	
	public Choice(Name name, ASTNodeKind astNodeKind ,  Parsers parsers) {
		super(name, parsers);
		addTag(astNodeKind.tag());
	}

	public Choice(ASTNodeKind astNodeKind ,  Parsers parsers) {
		super(parsers);
		addTag(astNodeKind.tag());
	}

	@SafeVarargs
	public Choice(Name name, ASTNodeKind astNodeKind ,  Parser... parsers) {
		super(name, parsers);
		addTag(astNodeKind.tag());
	}

	@SafeVarargs
	public Choice(ASTNodeKind astNodeKind ,  Parser... parsers) {
		super(parsers);
		addTag(astNodeKind.tag());
	}


	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		return ChoiceInterface.super.parse(parseContext, tokenKind, invertMatch);
	}
	
//	public Choice newWithout(Predicate<Parser> cutFilter){
//		
//		Predicate<Parser> passFilter = cutFilter.negate();
//		List<Parser> newChildren = getChildren().stream()
//			.filter(passFilter)
//			.collect(Collectors.toList());
//		
//		return new Choice(getName() , newChildren);
//	}

	@Override
	public HasChildrenParser createWith(Parsers children) {
		return new Choice(getName() , children);
	}
}
