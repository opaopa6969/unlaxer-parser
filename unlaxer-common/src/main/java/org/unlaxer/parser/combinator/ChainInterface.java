package org.unlaxer.parser.combinator;

import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.TokenList;
import org.unlaxer.context.ParseContext;
import org.unlaxer.listener.TransactionListener;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

public interface ChainInterface extends Parser{

	@Override
	public default Parsed parse(ParseContext parseContext,TokenKind tokenKind,boolean invertMatch) {

		parseContext.getCurrent().setResetMatchedWithConsumed(false);

		parseContext.startParse(this, parseContext, tokenKind, invertMatch);
		parseContext.begin(this);

		if (this instanceof TransactionListener tl) {
			tl.onBegin(parseContext, this);
		}

		Parsers children = getChildren();

		for (Parser parser : children) {
			Parsed parsed = parser.parse(parseContext,tokenKind,invertMatch);

			if(parsed.isStopped()){
				break;
			}
			if (parsed.isFailed()) {
				// Capture tokens before rollback clears the transaction frame
				TokenList rolledBackTokens = TokenList.of(parseContext.getCurrent().getTokens());
				parseContext.rollback(this);
				if (this instanceof TransactionListener tl) {
					tl.onRollback(parseContext, this, rolledBackTokens);
				}
				parseContext.endParse(this, Parsed.FAILED , parseContext, tokenKind, invertMatch);
				return Parsed.FAILED;
			}
		}
		Parsed committed = new Parsed(parseContext.commit(this,tokenKind));
		if (this instanceof TransactionListener tl) {
			tl.onCommit(parseContext, this, committed.getOriginalTokens());
		}
		parseContext.endParse(this, committed, parseContext, tokenKind, invertMatch);
		return committed;
	}
}