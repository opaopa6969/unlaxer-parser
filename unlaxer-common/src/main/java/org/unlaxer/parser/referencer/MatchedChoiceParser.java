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

	/** The predicate result, {@code null} until resolved; published as in ReferenceParser (#1007). */
	Optional<Parser> matchedParser;
	
	Predicate<Parser> predicate;

	public MatchedChoiceParser(Predicate<Parser> predicate) {
		super();
		this.predicate = predicate;
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		parseContext.enterParserCall();
		try {
		
			parseContext.startParse(this, parseContext, tokenKind, invertMatch);

			Optional<Parser> resolved = matchedParser;
			if(resolved == null){
				resolved = findFirstFromRoot(predicate);
				matchedParser = resolved;
			}
			Parsed parsed =
				resolved.map(parser->{
				
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
		} catch (RuntimeException | Error failure) {
			parseContext.failParserCall(failure);
			throw failure;
		} finally {
			parseContext.leaveParserCall();
		}
	}

	@Override
	public Parser createParser() {
		return this;
	}

}