package org.unlaxer.parser.combinator;

import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.PackratMemoTable;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

public interface ChainInterface extends Parser{

	// TransactionListener を実装する parser への通知は、begin/commit/rollback の
	// 発火元である TransactionListenerContainer が正しいペイロード
	// (collect 済みトークン) で自己通知する。ここでは通知しない。
	@Override
	public default Parsed parse(ParseContext parseContext,TokenKind tokenKind,boolean invertMatch) {

		parseContext.getCurrent().setResetMatchedWithConsumed(false);

		// Opt-in packrat failure memoization (issue #40): a rule that already failed here cannot
		// succeed on a retry. Off by default. (The setResetMatchedWithConsumed flag above is the
		// only pre-children side effect, so it is preserved before short-circuiting.)
		if (PackratMemoTable.isMemoizedFailure(parseContext, this, tokenKind, invertMatch)) {
			return Parsed.FAILED;
		}

		parseContext.startParse(this, parseContext, tokenKind, invertMatch);
		parseContext.begin(this);

		Parsers children = getChildren();

		for (Parser parser : children) {
			Parsed parsed = parser.parse(parseContext,tokenKind,invertMatch);

			if(parsed.isStopped()){
				break;
			}
			if (parsed.isFailed()) {
				parseContext.rollback(this);
				parseContext.endParse(this, Parsed.FAILED , parseContext, tokenKind, invertMatch);
				PackratMemoTable.memoizeFailure(parseContext, this, tokenKind, invertMatch);
				return Parsed.FAILED;
			}
		}
		Parsed committed = new Parsed(parseContext.commit(this,tokenKind));
		parseContext.endParse(this, committed, parseContext, tokenKind, invertMatch);
		return committed;
	}
}
