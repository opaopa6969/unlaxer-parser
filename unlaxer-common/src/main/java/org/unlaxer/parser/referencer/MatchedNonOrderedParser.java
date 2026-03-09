package org.unlaxer.parser.referencer;

import java.util.Optional;
import java.util.function.Predicate;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.NonOrdered;
import org.unlaxer.parser.combinator.NoneChildParser;
import org.unlaxer.parser.combinator.Ordered;

public class MatchedNonOrderedParser extends NoneChildParser{

	private static final long serialVersionUID = -7305620477370104733L;

	boolean predicated = false;
	
	Optional<Parser> matchedParser;
	
	Predicate<Parser> predicate;

	public MatchedNonOrderedParser(Predicate<Parser> predicate) {
		super();
		this.predicate = predicate;
	}

	public MatchedNonOrderedParser(Name name , Predicate<Parser> predicate) {
		super(name);
		this.predicate = predicate;
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		if(false == predicated){
			matchedParser = findFirstFromRoot(predicate);
			predicated = true;
		}
		return 
			matchedParser.map(parser->{
				
					if(false == parser instanceof NonOrdered){
						throw new IllegalArgumentException("you must specify reference to Choice instance");
					}
					Parsers ordered = parseContext.getOrdered((NonOrdered) parser);
					if(ordered.isEmpty()){
						return Parsed.FAILED;
					}
					Ordered orderedParser = new Ordered(ordered);
					return orderedParser.parse(parseContext,tokenKind,invertMatch);
				})
				.orElse(Parsed.FAILED);
	}

	@Override
	public Parser createParser() {
		return this;
	}

}