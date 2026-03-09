package org.unlaxer.parser.combinator;

import java.util.Optional;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.CollectingParser;
import org.unlaxer.parser.LazyAbstractParser;
import org.unlaxer.parser.LazyOccursParserSpecifier;
import org.unlaxer.parser.Parser;

public abstract class LazyOccurs extends LazyAbstractParser 
	implements Occurs , CollectingParser, LazyOccursParserSpecifier{

	private static final long serialVersionUID = -3137448600028071351L;

	public LazyOccurs() {
		super();
	}

	public LazyOccurs(Name name) {
		super(name);
	}

	@Override
	public Optional<Parser> getTerminator() {
		return getLazyTerminatorParser();
	}

	@Override
	public Parser createParser() {
		return this;
	}

	@Override
	public ChildOccurs getChildOccurs() {
		return ChildOccurs.multi;
	}
	
	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		return Occurs.super.parse(parseContext, tokenKind, invertMatch);
	}
}