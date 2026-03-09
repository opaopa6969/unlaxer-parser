package org.unlaxer.parser.referencer;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.AbstractParser;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

public class ReferenceByNameParser extends AbstractParser{

	private static final long serialVersionUID = 2490853452667336115L;
	
	Name referenceName;

	public ReferenceByNameParser(Name referenceName) {
		super();
		this.referenceName = referenceName;
	}

	public ReferenceByNameParser(Name referenceName , Name name) {
		super(name);
		this.referenceName = referenceName;
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		parseContext.startParse(this, parseContext, tokenKind, invertMatch);
		Parsed parsed = getParser(referenceName)
			.map(parser->parser.parse(parseContext,tokenKind,invertMatch))
			.orElse(Parsed.FAILED);
		parseContext.endParse(this, parsed ,  parseContext, tokenKind, invertMatch);
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

	@Override
	public void prepareChildren(Parsers childrenContainer) {
	}
}