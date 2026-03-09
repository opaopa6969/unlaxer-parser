package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;

public class InvertMatchPropagationStopper extends AbstractPropagatableSource implements PropagationStopper{

	
	private static final long serialVersionUID = -8284593921421909894L;
	
	public InvertMatchPropagationStopper(Name name, Parser children) {
		super(name, children);
	}

	public InvertMatchPropagationStopper(Parser child) {
		super(child);
	}

	@Override
	public boolean computeInvertMatch(boolean fromParentValue, boolean thisSourceValue) {
		return false;
	}

	@Override
	public boolean getThisInvertedSourceValue() {
		return false;
	}

	@Override
	public Parsed parseDelegated(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		
		parseContext.startParse(this, parseContext, tokenKind, invertMatch);
		Parsed parsed = getChild().parse(parseContext,tokenKind,false);
		parseContext.endParse(this, parsed , parseContext, tokenKind, invertMatch);
		return parsed;
	}

}