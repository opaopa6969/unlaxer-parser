package org.unlaxer.parser;

import java.util.Optional;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;

public class PseudoRootParser extends AbstractParser{

	private static final long serialVersionUID = 8993640266252280628L;

	
	public PseudoRootParser() {
		super();
	}

	public PseudoRootParser(Parsers children) {
		super(children);
	}

	public PseudoRootParser(Name name, Parsers children) {
		super(name, children);
	}

	public PseudoRootParser(Name name) {
		super(name);
	}

	@Override
	public boolean getInvertMatchFromParent() throws IllegalStateException {
		return false;
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		if(getChildOccurs().isSingle()){
			parseContext.startParse(this, parseContext, tokenKind, invertMatch);
			Parsed parsed = getChildren().get(0).parse(parseContext,tokenKind,invertMatch);
			parseContext.endParse(this, parsed , parseContext, tokenKind, invertMatch);
			return parsed;
			
		}
		throw new RuntimeException(new IllegalAccessException());
	}

	@Override
	public Name getName() {
		return Name.of(PseudoRootParser.class);
	}

	@Override
	public Optional<Parser> getParent() {
		return Optional.empty();
	}

	@Override
	public void setParent(Parser parent) {
		throw new RuntimeException(new IllegalAccessException());
	}

	@Override
	public ChildOccurs getChildOccurs() {
		return children.isEmpty() ? ChildOccurs.none :
			children.size() == 1 ? ChildOccurs.single:
				ChildOccurs.multi;
	}

	@Override
	public Parser getRoot() {
		return this;
	}

	@Override
	public Parser createParser() {
		return this;
	}

	@Override
	public void prepareChildren(Parsers childrenContainer) {
	}
}