package org.unlaxer.parser.combinator;

import java.util.List;
import java.util.Optional;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.RecursiveMode;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.HasChildrenParser;
import org.unlaxer.parser.LazyParserChildrenSpecifier;
import org.unlaxer.parser.Parsers;

/** Lazy generated-rule form of {@link PredictiveChoiceInterface}. */
public abstract class LazyPredictiveChoice extends LazyCombinatorParser
        implements PredictiveChoiceInterface, LazyParserChildrenSpecifier {
    private static final long serialVersionUID = 1L;

    public LazyPredictiveChoice() { super(); }
    public LazyPredictiveChoice(Name name) { super(name); }

    @Override
    public Parsed parse(ParseContext context, TokenKind kind, boolean invertMatch) {
        return PredictiveChoiceInterface.super.parse(context, kind, invertMatch);
    }

    @Override
    public HasChildrenParser createWith(Parsers children) {
        List<ChoicePredictor> predictors = getChoicePredictors();
        return new LazyPredictiveChoice(getName()) {
            private static final long serialVersionUID = 1L;
            @Override public Parsers getLazyParsers() { return children; }
            @Override public List<ChoicePredictor> getChoicePredictors() { return predictors; }
        };
    }

    @Override
    public Optional<RecursiveMode> getNotAstNodeSpecifier() { return Optional.empty(); }
}
