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
	
	/**
	 * The predicate result, {@code null} until resolved. One field instead of a flag and a value:
	 * a thread that reads a non-null Optional sees its contents (final field semantics, JLS 17.5),
	 * so a shared frozen graph never observes "resolved" without the parser (#1007). Threads that
	 * race on the first use may each evaluate the predicate and publish an equal result.
	 */
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
		parseContext.enterParserCall();
		try {
			parseContext.startParse(this, parseContext, tokenKind, invertMatch);

			Parsed parsed = getMatchedParser()
					.map(parser->parser.parse(parseContext,tokenKind,invertMatch))
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
		Optional<Parser> resolved = matchedParser;
		if(resolved == null){
			resolved = findFirstFromRoot(predicate);
			matchedParser = resolved;
		}
		return resolved;
	}


	@Override
	public void prepareChildren(Parsers childrenContainer) {
	}
	
}
