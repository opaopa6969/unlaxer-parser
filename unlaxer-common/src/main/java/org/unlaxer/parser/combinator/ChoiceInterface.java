package org.unlaxer.parser.combinator;

import java.util.List;

import org.unlaxer.Parsed;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.PackratMemoTable;
import org.unlaxer.parser.Parser;
import org.unlaxer.util.annotation.TokenExtractor;

public interface ChoiceInterface extends Parser{

	@Override
	public default Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {

		// Opt-in safe failure memoization: a cached failure short-circuits after the original call
		// exhausted every alternative. Successes are never cached; default parsing is unchanged.
		PackratMemoTable.Entry memo = PackratMemoTable.lookup(parseContext, this, tokenKind, invertMatch);
		if (memo != null) {
			return Parsed.FAILED;
		}
		var memoDiagnostic = PackratMemoTable.beginFailure(parseContext, this);

		parseContext.startParse(this, parseContext, tokenKind, invertMatch);
		List<Parser> children = getChildren();

		for (Parser parser : children) {
			parseContext.begin(this);
			Parsed parsed = parser.parse(parseContext, tokenKind, invertMatch);

			if (parsed.isSucceeded()) {
				parseContext.commit(this, tokenKind , new ChoiceCommitAction(parser));
				parseContext.endParse(this, parsed , parseContext, tokenKind, invertMatch);
				PackratMemoTable.memoizeSuccess(parseContext, memoDiagnostic);
				return parsed;
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
