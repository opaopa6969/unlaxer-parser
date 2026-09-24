package org.unlaxer.parser.combinator;

import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.PackratMemoTable;
import org.unlaxer.parser.FirstSets;
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
		// safe successes replay through the same transaction path as other rules.
		PackratMemoTable.Entry memo = PackratMemoTable.lookup(parseContext, this, tokenKind, invertMatch);
		if (memo != null) {
			return PackratMemoTable.replay(parseContext, this, tokenKind, invertMatch, memo);
		}
		var memoDiagnostic = PackratMemoTable.beginFailure(parseContext, this);
		var memoStart = PackratMemoTable.successKey(parseContext, this, tokenKind, invertMatch, memoDiagnostic);

		parseContext.startParse(this, parseContext, tokenKind, invertMatch);
		parseContext.begin(this);

		Parsers children = getChildren();

		/*
		 * An element whose FIRST set does not contain the code point the cursor is on cannot match
		 * there, so the sequence is already lost and the element is not evaluated at all. The test is
		 * repeated per element because earlier elements move the cursor. FirstSet is conservative and
		 * answers "unknown" for every construct the analysis does not model, and invertMatch flips
		 * what terminals accept, so neither can lose an element that could have matched.
		 */
		boolean excludeElements = false == invertMatch && parseContext.isCandidateExclusionEnabled();

		for (Parser parser : children) {
			if (excludeElements && false == FirstSets.of(parser)
					.mayStartWith(parseContext.lookaheadCodePoint(tokenKind))) {
				parseContext.rollback(this);
				parseContext.endParse(this, Parsed.FAILED , parseContext, tokenKind, invertMatch);
				PackratMemoTable.memoizeFailure(parseContext, this, tokenKind, invertMatch, memoDiagnostic);
				return Parsed.FAILED;
			}
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
		return PackratMemoTable.commitSuccess(parseContext, this, tokenKind, invertMatch,
            memoStart, memoDiagnostic, null, null);
	}
}
