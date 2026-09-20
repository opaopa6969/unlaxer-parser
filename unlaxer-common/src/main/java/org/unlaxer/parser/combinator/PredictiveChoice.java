package org.unlaxer.parser.combinator;

import java.util.List;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.ast.ASTNodeKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.HasChildrenParser;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

/** Constructed form of {@link PredictiveChoiceInterface}. */
public class PredictiveChoice extends ConstructedCombinatorParser implements PredictiveChoiceInterface {
    private static final long serialVersionUID = 1L;
    private final List<ChoicePredictor> predictors;

    public PredictiveChoice(List<ChoicePredictor> predictors, Parser... parsers) {
        super(parsers);
        this.predictors = List.copyOf(predictors);
        setASTNodeKind(ASTNodeKind.ChoicedOperator);
    }

    private PredictiveChoice(Name name, List<ChoicePredictor> predictors, Parsers parsers) {
        super(name, parsers);
        this.predictors = List.copyOf(predictors);
    }

    @Override public List<ChoicePredictor> getChoicePredictors() { return predictors; }

    @Override
    public Parsed parse(ParseContext context, TokenKind kind, boolean invertMatch) {
        return PredictiveChoiceInterface.super.parse(context, kind, invertMatch);
    }

    @Override
    public HasChildrenParser createWith(Parsers children) {
        return new PredictiveChoice(getName(), predictors, children);
    }
}
