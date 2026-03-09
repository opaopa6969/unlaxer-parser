package org.unlaxer.parser.referencer;

import java.util.Optional;
import java.util.function.Predicate;

import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.ChoiceInterface;
import org.unlaxer.parser.combinator.NoneChildParser;

public class MatchedChoiceParser extends NoneChildParser{

	private static final long serialVersionUID = -5984917954997243613L;

	boolean predicated = false;
	
	Optional<Parser> matchedParser;
	
	Predicate<Parser> predicate;

	public MatchedChoiceParser(Predicate<Parser> predicate) {
		super();
		this.predicate = predicate;
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		
		parseContext.startParse(this, parseContext, tokenKind, invertMatch);

		if(false == predicated){
			matchedParser = findFirstFromRoot(predicate);
			predicated = true;
		}
		Parsed parsed =  
			matchedParser.map(parser->{
				
					if(false == parser instanceof ChoiceInterface){
						throw new IllegalArgumentException("you must specify reference to Choice instance");
					}
					Optional<Parser> chosen = parseContext.getChosen((ChoiceInterface) parser);
					return chosen
						.map(chosenParser->chosenParser.parse(parseContext,tokenKind,invertMatch))
						.orElse(Parsed.FAILED);
				})
				.orElse(Parsed.FAILED);
		
		parseContext.endParse(this, parsed , parseContext, tokenKind, invertMatch);
		return parsed;

	}

	@Override
	public Parser createParser() {
		return this;
	}

}