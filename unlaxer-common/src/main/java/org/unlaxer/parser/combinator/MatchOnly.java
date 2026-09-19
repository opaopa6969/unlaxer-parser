package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.MetaFunctionParser;
import org.unlaxer.parser.Parser;

public class MatchOnly extends ConstructedSingleChildParser implements MetaFunctionParser {

	private static final long serialVersionUID = 3691720461774778800L;

	public MatchOnly(Name name, Parser child) {
		super(name, child);
	}

	public MatchOnly(Parser child) {
		super(child);
	}

	@Override
	public Parsed parse(ParseContext parseContext) {
		return parse(parseContext, TokenKind.matchOnly, false);
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind,boolean invertMatch) {
		
		parseContext.startParse(this, parseContext, tokenKind, invertMatch);

		parseContext.begin(this);
		org.unlaxer.TransactionElement stateBoundary = parseContext.getCurrent();
		
		Parsed parsed = getChild().parse(parseContext,TokenKind.matchOnly,invertMatch);

		if (parsed.isFailed()) {
			parseContext.rollback(this);
			parseContext.endParse(this, Parsed.FAILED , parseContext, tokenKind, invertMatch);
			return Parsed.FAILED;
		}
		Parsed committed;
		try {
			committed = new Parsed(parseContext.commit(this ,TokenKind.matchOnly));
		} finally {
			// Child commits remain visible within this lookahead. Publish only the
			// existing match cursor/token result, never its semantic side effects.
			stateBoundary.restoreState();
		}
		parseContext.endParse(this, committed , parseContext, tokenKind, invertMatch);
		return committed;
	}

	@Override
	public TokenKind getTokenKind() {
		return TokenKind.matchOnly;
	}

}
