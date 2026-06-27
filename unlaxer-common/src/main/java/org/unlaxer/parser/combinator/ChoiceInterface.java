package org.unlaxer.parser.combinator;

import java.util.List;

import org.unlaxer.Parsed;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.TokenList;
import org.unlaxer.TransactionElement;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.PackratMemoTable;
import org.unlaxer.parser.Parser;
import org.unlaxer.util.annotation.TokenExtractor;

public interface ChoiceInterface extends Parser{

	@Override
	public default Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {

		// Opt-in packrat memoization (issue #40): a cached failure short-circuits (the choice
		// already exhausted every alternative here); a cached success replays the winning
		// alternative without re-deriving it. Off by default, so default parsing is unaffected.
		PackratMemoTable.Entry memo = PackratMemoTable.lookup(parseContext, this, tokenKind, invertMatch);
		if (memo != null) {
			if (memo.isFailed()) {
				return Parsed.FAILED;
			}
			return PackratMemoTable.replaySuccess(
				parseContext, this, tokenKind, invertMatch, memo, new ChoiceCommitAction(memo.chosenChild()));
		}
		PackratMemoTable.PositionKey startKey = parseContext.isMemoizeEnabled()
			? PackratMemoTable.positionKeyOf(parseContext, tokenKind, invertMatch) : null;

		parseContext.startParse(this, parseContext, tokenKind, invertMatch);
		List<Parser> children = getChildren();

		for (Parser parser : children) {
			parseContext.begin(this);
			Parsed parsed = parser.parse(parseContext, tokenKind, invertMatch);

			if (parsed.isSucceeded()) {
				TransactionElement current = parseContext.getCurrent();
				int endConsumed = current.getPosition(TokenKind.consumed).value();
				int endMatched = current.getPosition(TokenKind.matchOnly).value();
				TokenList ruleTokens = current.getTokens();
				parseContext.commit(this, tokenKind , new ChoiceCommitAction(parser));
				parseContext.endParse(this, parsed , parseContext, tokenKind, invertMatch);
				if (startKey != null) {
					PackratMemoTable.memoizeSuccess(
						parseContext, this, startKey, ruleTokens, endConsumed, endMatched, parser);
				}
				return parsed;
			}
			parseContext.rollback(this);
		}
		parseContext.endParse(this, Parsed.FAILED , parseContext, tokenKind, invertMatch);
		PackratMemoTable.memoizeFailure(parseContext, this, tokenKind, invertMatch);
		return Parsed.FAILED;
	}

	@TokenExtractor
	public static Token choiced(Token thisChoiceToken) {
		return thisChoiceToken.getChildFromAstNodes(0);
	}
}