package org.unlaxer.parser.combinator;

import java.util.List;

import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.PackratMemoTable;
import org.unlaxer.parser.Parser;

/** Ordered choice that conservatively skips alternatives with an impossible FIRST prefix. */
public interface PredictiveChoiceInterface extends ChoiceInterface {

    /** One predictor per child. Missing, {@code null}, and {@link ChoicePredictor.Any} mean unknown. */
    List<ChoicePredictor> getChoicePredictors();

    @Override
    default Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        PackratMemoTable.Entry memo = PackratMemoTable.lookup(parseContext, this, tokenKind, invertMatch);
        if (memo != null) return PackratMemoTable.replay(parseContext, this, tokenKind, invertMatch, memo);
        var memoDiagnostic = PackratMemoTable.beginFailure(parseContext, this);
        var memoStart = PackratMemoTable.successKey(parseContext, this, tokenKind, invertMatch, memoDiagnostic);

        parseContext.startParse(this, parseContext, tokenKind, invertMatch);
        List<Parser> children = getChildren();
        List<ChoicePredictor> predictors = getChoicePredictors();
        int candidateCount = 0;
        for (int i = 0; i < children.size(); i++) {
            ChoicePredictor predictor = i < predictors.size() ? predictors.get(i) : ChoicePredictor.any();
            if (invertMatch || predictor == null || predictor.mayMatch(parseContext, tokenKind)) {
                candidateCount++;
            }
        }
        // A malformed or overly narrow predictor must never turn a valid choice into failure.
        boolean useAllChildren = candidateCount == 0;

        for (int i = 0; i < children.size(); i++) {
            ChoicePredictor predictor = i < predictors.size() ? predictors.get(i) : ChoicePredictor.any();
            if (!useAllChildren && !invertMatch && predictor != null
                    && !predictor.mayMatch(parseContext, tokenKind)) continue;
            Parser parser = children.get(i);
            parseContext.begin(this);
            Parsed parsed = parser.parse(parseContext, tokenKind, invertMatch);
            if (parsed.isSucceeded()) {
                return PackratMemoTable.commitSuccess(parseContext, this, tokenKind, invertMatch,
                    memoStart, memoDiagnostic, parser, parsed);
            }
            parseContext.rollback(this);
        }
        // Preserve ordered Choice correctness and diagnostics if generated metadata is ever
        // too narrow: after the fast path fails, retry the complete list in declaration order.
        if (!useAllChildren && candidateCount < children.size()) {
            for (Parser parser : children) {
                parseContext.begin(this);
                Parsed parsed = parser.parse(parseContext, tokenKind, invertMatch);
                if (parsed.isSucceeded()) {
                    return PackratMemoTable.commitSuccess(parseContext, this, tokenKind, invertMatch,
                        memoStart, memoDiagnostic, parser, parsed);
                }
                parseContext.rollback(this);
            }
        }
        parseContext.endParse(this, Parsed.FAILED, parseContext, tokenKind, invertMatch);
        PackratMemoTable.memoizeFailure(parseContext, this, tokenKind, invertMatch, memoDiagnostic);
        return Parsed.FAILED;
    }
}
