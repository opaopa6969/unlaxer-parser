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
import org.unlaxer.parser.elementary.SpaceDelimitor;
import org.unlaxer.reducer.TagBasedReducer.NodeKind;

public class WhiteSpaceDelimitedChain extends Chain {

	private static final long serialVersionUID = 7516040092056055656L;
	
	static final SpaceDelimitor spaceDelimitor = new SpaceDelimitor();
	static {
		spaceDelimitor.addTag(NodeKind.notNode.getTag());
	}

	public WhiteSpaceDelimitedChain(Parsers children) {
		super(setup(children));
	}

	@SafeVarargs
	public WhiteSpaceDelimitedChain(Parser... children) {
		super(setup(children));
	}
	
	public WhiteSpaceDelimitedChain(Name name, Parsers children) {
		super(name, setup(children));
	}

	public WhiteSpaceDelimitedChain(Name name, Parser... children) {
		super(name, setup(children));
	}
	
	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		return super.parse(parseContext, tokenKind, invertMatch);
	}
	
	@Override
	public WhiteSpaceDelimitedChain newFiltered(Predicate<Parser> cutFilter){
		
		Predicate<Parser> passFilter = cutFilter.negate();
		List<Parser> newChildren = getChildren().stream()
			.filter(passFilter)
			.collect(Collectors.toList());
		
		return new WhiteSpaceDelimitedChain(getName() ,Parsers.of(newChildren));
	}

	@Override
	public HasChildrenParser createWith(Parsers children) {
		return new WhiteSpaceDelimitedChain(getName() , children);
	}
	
	static Parser[] setup(Parser...parsers) {
		Parser[] newParsers = new Parser[parsers.length * 2 +1];
		int i = 0;
		newParsers[i++] = spaceDelimitor;
		for (Parser parser : parsers) {
			newParsers[i++] = parser;
			newParsers[i++] = spaceDelimitor;
		}
		return newParsers;
	}
	
	static Parsers setup(Parsers parsers) {
		Parsers results = new Parsers();
		results.add(spaceDelimitor);
		for (Parser parser : parsers) {
			results.add(parser);
			results.add(spaceDelimitor);
		}
		return results;
	}

}
