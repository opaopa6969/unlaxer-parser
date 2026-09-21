package org.unlaxer.parser.combinator;

import java.util.List;

import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.PackratMemoTable;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;

/**
 * Opt-in longest ordered choice.
 *
 * <p>Every alternative is evaluated from the same transaction state.  The alternative that
 * advances the active cursor farthest wins; equal lengths retain declaration order.  Since a
 * {@link org.unlaxer.context.TransactionalState} only supplies rollback (not a forward-state
 * snapshot), the winning alternative is evaluated once more and only that evaluation commits.
 */
public interface LongestChoiceInterface extends ChoiceInterface {

    @Override
    default Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        PackratMemoTable.Entry memo =
            PackratMemoTable.lookup(parseContext, this, tokenKind, invertMatch);
        if (memo != null) return PackratMemoTable.replay(parseContext, this, tokenKind, invertMatch, memo);
        var memoDiagnostic = PackratMemoTable.beginFailure(parseContext, this);
        var memoStart = PackratMemoTable.successKey(parseContext, this, tokenKind, invertMatch, memoDiagnostic);

        parseContext.startParse(this, parseContext, tokenKind, invertMatch);
        List<Parser> children = getChildren();
        Parser winner = null;
        int winnerEnd = -1;

        for (Parser parser : children) {
            parseContext.begin(this);
            Parsed parsed = parser.parse(parseContext, tokenKind, invertMatch);
            if (parsed.isSucceeded()) {
                int candidateEnd = tokenKind.isConsumed()
                    ? parseContext.getConsumedPosition().value()
                    : parseContext.getMatchedPosition().value();
                if (candidateEnd > winnerEnd) {
                    winner = parser;
                    winnerEnd = candidateEnd;
                }
            }
            parseContext.rollback(this);
        }

        if (winner == null) {
            parseContext.endParse(this, Parsed.FAILED, parseContext, tokenKind, invertMatch);
            PackratMemoTable.memoizeFailure(
                parseContext, this, tokenKind, invertMatch, memoDiagnostic);
            return Parsed.FAILED;
        }

        parseContext.begin(this);
        Parsed replayed = winner.parse(parseContext, tokenKind, invertMatch);
        if (replayed.isSucceeded()) {
            return PackratMemoTable.commitSuccess(parseContext, this, tokenKind, invertMatch,
                    memoStart, memoDiagnostic, winner, replayed);
        }

        // A custom parser may be non-deterministic.  Do not commit a different candidate than
        // the one selected from the transactionally isolated trials.
        parseContext.rollback(this);
        parseContext.endParse(this, Parsed.FAILED, parseContext, tokenKind, invertMatch);
        PackratMemoTable.memoizeFailure(
            parseContext, this, tokenKind, invertMatch, memoDiagnostic);
        return Parsed.FAILED;
    }
}
