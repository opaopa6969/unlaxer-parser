package org.unlaxer.parser.combinator;

import java.util.List;

import org.unlaxer.Parsed;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.PackratMemoTable;
import org.unlaxer.parser.FirstSets;
import org.unlaxer.parser.Parser;
import org.unlaxer.util.annotation.TokenExtractor;

public interface ChoiceInterface extends Parser{

	@Override
	public default Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {

		// Opt-in safe failure memoization: a cached failure short-circuits after the original call
		// exhausted every alternative. Safe successes replay the chosen token tree.
		PackratMemoTable.Entry memo = PackratMemoTable.lookup(parseContext, this, tokenKind, invertMatch);
		if (memo != null) {
			return PackratMemoTable.replay(parseContext, this, tokenKind, invertMatch, memo);
		}
		var memoDiagnostic = PackratMemoTable.beginFailure(parseContext, this);
		var memoStart = PackratMemoTable.successKey(parseContext, this, tokenKind, invertMatch, memoDiagnostic);

		parseContext.startParse(this, parseContext, tokenKind, invertMatch);
		List<Parser> children = getChildren();

		/*
		 * An alternative whose FIRST set does not contain the next code point cannot succeed here, so
		 * it is skipped before a transaction is begun, a token is built or the memo table is probed.
		 * FirstSet is a conservative over-approximation and answers "unknown" for everything the
		 * analysis does not model, so this never removes an alternative that could have matched.
		 * invertMatch flips what the terminals accept, so the set does not apply under inversion.
		 */
		boolean excludeCandidates = false == invertMatch && parseContext.isCandidateExclusionEnabled();
		int lookahead = excludeCandidates ? parseContext.lookaheadCodePoint(tokenKind) : 0;

		for (Parser parser : children) {
			if (excludeCandidates && false == FirstSets.of(parser).mayStartWith(lookahead)) {
				continue;
			}
			parseContext.begin(this);
			Parsed parsed = parser.parse(parseContext, tokenKind, invertMatch);

			if (parsed.isSucceeded()) {
				return PackratMemoTable.commitSuccess(parseContext, this, tokenKind, invertMatch,
					memoStart, memoDiagnostic, parser, parsed);
			}
			parseContext.rollback(this);
		}
		parseContext.endParse(this, Parsed.FAILED , parseContext, tokenKind, invertMatch);
		PackratMemoTable.memoizeFailure(parseContext, this, tokenKind, invertMatch, memoDiagnostic);
		return Parsed.FAILED;
	}

	@TokenExtractor
	public static Token choiced(Token thisChoiceToken) {
		return thisChoiceToken.getChildFromAstNodes(0);
	}
}
