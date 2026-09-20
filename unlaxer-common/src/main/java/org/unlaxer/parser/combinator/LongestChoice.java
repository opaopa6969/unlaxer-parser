package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.ast.ASTNodeKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.HasChildrenParser;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

/** Constructed form of {@link LongestChoiceInterface}. */
public class LongestChoice extends ConstructedCombinatorParser
        implements LongestChoiceInterface {

    private static final long serialVersionUID = 1L;

    public LongestChoice(Name name, Parsers parsers) { super(name, parsers); }
    public LongestChoice(Parsers parsers) { super(parsers); }

    @SafeVarargs
    public LongestChoice(Name name, Parser... parsers) { super(name, parsers); }

    @SafeVarargs
    public LongestChoice(Parser... parsers) {
        super(parsers);
        setASTNodeKind(ASTNodeKind.ChoicedOperator);
    }

    @SafeVarargs
    public LongestChoice(Class<? extends Parser>... parsers) {
        super(parsers);
        setASTNodeKind(ASTNodeKind.ChoicedOperator);
    }

    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        return LongestChoiceInterface.super.parse(parseContext, tokenKind, invertMatch);
    }

    @Override
    public HasChildrenParser createWith(Parsers children) {
        return new LongestChoice(getName(), children);
    }
}
