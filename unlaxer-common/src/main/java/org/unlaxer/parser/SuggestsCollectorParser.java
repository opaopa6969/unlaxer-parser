package org.unlaxer.parser;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.unlaxer.CursorRange;
import org.unlaxer.Parsed;
import org.unlaxer.Parsed.Status;
import org.unlaxer.RangedContent;
import org.unlaxer.Source;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.combinator.ContainerParser;

public class SuggestsCollectorParser extends ContainerParser<Suggests>{

	private static final long serialVersionUID = -4902736660169378528L;
	Suggests suggests;
	
	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		Parsed parsed = super.parse(parseContext, tokenKind, invertMatch);
		parsed.status = Status.stopped;
		//TODO reamin with terminator. 
		//eg. terminator=';'
		Source remain = parseContext.getRemain(TokenKind.consumed);
		List<Suggest> collect = getSiblings(false).stream()
			.filter(SuggestableParser.class::isInstance)
			.map(SuggestableParser.class::cast)
			.map(suggestableParser->suggestableParser.getSuggests(remain.toString()))
			.filter(Optional::isPresent)
			.map(Optional::get)
			.collect(Collectors.toList());
		suggests = new Suggests(collect);
		return parsed;
	}


	@Override
	public Suggests get() {
		return suggests;
	}

	@Override
	public Parser createParser() {
		return this;
	}

	@Override
	public RangedContent<Suggests> get(CursorRange position) {
		
		return new RangedContent<Suggests>() {
			
			@Override
			public CursorRange getRange() {
				return position;
			}
			
			@Override
			public Suggests getContent() {
				return suggests;
			}
		};
	}
}