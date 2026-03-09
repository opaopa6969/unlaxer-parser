package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.MetaFunctionParser;
import org.unlaxer.parser.Parser;

public class NotPropagatableSource extends AbstractPropagatableSource implements MetaFunctionParser {

	private static final long serialVersionUID = 8433613177749527212L;

	public NotPropagatableSource(Parser parser) {
		super(parser);
	}
	
	public NotPropagatableSource(Name name, Parser children) {
		super(name, children);
	}
	
	@Override
	public Parsed parseDelegated(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {

		parseContext.startParse(this, parseContext, tokenKind, invertMatch);
		parseContext.begin(this);

		Parsed parsed = getChild().parse(parseContext, tokenKind, invertMatch);

		if (parsed.isFailed()) {
			parseContext.rollback(this);
			parseContext.endParse(this, Parsed.FAILED , parseContext, tokenKind, invertMatch);
			return Parsed.FAILED;
		}
		Parsed committed = new Parsed(parseContext.commit(this,tokenKind));
		parseContext.endParse(this, committed , parseContext, tokenKind, invertMatch);
		return committed;
	}

	@Override
	public boolean computeInvertMatch(boolean fromParentValue, boolean thisSourceValue) {
		return false == fromParentValue;
	}

	@Override
	public boolean getThisInvertedSourceValue() {
		return true;
	}

}
