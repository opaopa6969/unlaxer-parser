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

		// Opt-in safe failure memoization. The cursor flag above remains observable on a hit;
		// successes are never cached and default parsing remains unchanged.
		PackratMemoTable.Entry memo = PackratMemoTable.lookup(parseContext, this, tokenKind, invertMatch);
		if (memo != null) {
			return Parsed.FAILED;
		}
		var memoDiagnostic = PackratMemoTable.beginFailure(parseContext, this);

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
				PackratMemoTable.memoizeFailure(parseContext, this, tokenKind, invertMatch, memoDiagnostic);
				return Parsed.FAILED;
			}
		}
		Parsed committed = new Parsed(parseContext.commit(this,tokenKind));
		parseContext.endParse(this, committed, parseContext, tokenKind, invertMatch);
		PackratMemoTable.memoizeSuccess(parseContext, memoDiagnostic);
		return committed;
	}
}
