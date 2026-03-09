package org.unlaxer.parser.referencer;

import java.util.Optional;
import java.util.function.Predicate;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.AbstractParser;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

public class ReferenceParser extends AbstractParser{

	private static final long serialVersionUID = 9076824441322103190L;
	
	boolean predicated = false;
	
	Optional<Parser> matchedParser;
	
	Predicate<Parser> predicate;

	public ReferenceParser(Predicate<Parser> predicate) {
		super();
		this.predicate = predicate;
	}

	public ReferenceParser(Name name , Predicate<Parser> predicate) {
		super(name);
		this.predicate = predicate;
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		parseContext.startParse(this, parseContext, tokenKind, invertMatch);

		Parsed parsed = getMatchedParser()
				.map(parser->parser.parse(parseContext,tokenKind,invertMatch))
				.orElse(Parsed.FAILED);
		parseContext.endParse(this, parsed , parseContext, tokenKind, invertMatch);
		return parsed;

	}

	@Override
	public ChildOccurs getChildOccurs() {
		return ChildOccurs.none;
	}

	@Override
	public Parser createParser() {
		return this;
	}
	
	public static ReferenceParser of(Name targetParserName){
		return new ReferenceParser(parser->parser.getName().equals(targetParserName));
	}
	
	public Optional<Parser> getMatchedParser(){
		if(false == predicated){
			matchedParser = findFirstFromRoot(predicate);
			predicated = true;
		}
		return matchedParser;
	}


	@Override
	public void prepareChildren(Parsers childrenContainer) {
	}
	
}
