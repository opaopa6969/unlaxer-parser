package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;

public class Not extends ConstructedSingleChildParser {

	private static final long serialVersionUID = -1735074020146586037L;

	public Not(Name name, Parser children) {
		super(name, children);
	}

	public Not(Parser child) {
		super(child);
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {

		parseContext.startParse(this, parseContext, tokenKind, invertMatch);

		parseContext.begin(this);

		Parsed parsed = getChild().parse(parseContext, TokenKind.matchOnly, invertMatch);

		if (parsed.isSucceeded()) {
			// child succeeded → Not fails; rollback any state changes
			parseContext.rollback(this);
			parseContext.endParse(this, Parsed.FAILED, parseContext, tokenKind, invertMatch);
			return Parsed.FAILED;
		}

		// child failed → Not succeeds; commit (no tokens consumed)
		Parsed committed = new Parsed(parseContext.commit(this, TokenKind.matchOnly));
		parseContext.endParse(this, committed, parseContext, tokenKind, invertMatch);
		return committed;
	}

}