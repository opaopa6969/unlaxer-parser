package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.ast.ASTNodeKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.HasChildrenParser;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

/** Opt-in unique maximum: reject tied or empty winners without committing their state. */
public class UniqueLongestChoice extends ConstructedCombinatorParser
        implements LongestChoiceInterface {

    @Override
    public boolean requiresUniqueLongestChoice() { return true; }

    private static final long serialVersionUID = 1L;

    public UniqueLongestChoice(Name name, Parsers parsers) { super(name, parsers); }
    public UniqueLongestChoice(Parsers parsers) { super(parsers); }

    @SafeVarargs
    public UniqueLongestChoice(Name name, Parser... parsers) { super(name, parsers); }

    @SafeVarargs
    public UniqueLongestChoice(Parser... parsers) {
        super(parsers);
        setASTNodeKind(ASTNodeKind.ChoicedOperator);
    }

    @SafeVarargs
    public UniqueLongestChoice(Class<? extends Parser>... parsers) {
        super(parsers);
        setASTNodeKind(ASTNodeKind.ChoicedOperator);
    }

    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        return LongestChoiceInterface.super.parse(parseContext, tokenKind, invertMatch);
    }

    @Override
    public HasChildrenParser createWith(Parsers children) {
        return new UniqueLongestChoice(getName(), children);
    }
}
