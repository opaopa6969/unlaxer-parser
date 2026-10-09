package org.unlaxer.parser.combinator;

import java.util.Optional;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.RecursiveMode;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.HasChildrenParser;
import org.unlaxer.parser.LazyParserChildrenSpecifier;
import org.unlaxer.parser.Parsers;

/** Lazy generated-rule form of {@link UniqueLongestChoice}. */
public abstract class LazyUniqueLongestChoice extends LazyCombinatorParser
        implements LongestChoiceInterface, LazyParserChildrenSpecifier {

    @Override
    public boolean requiresUniqueLongestChoice() { return true; }

    private static final long serialVersionUID = 1L;

    public LazyUniqueLongestChoice() { super(); }
    public LazyUniqueLongestChoice(Name name) { super(name); }

    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        return LongestChoiceInterface.super.parse(parseContext, tokenKind, invertMatch);
    }

    @Override
    public HasChildrenParser createWith(Parsers children) {
        return new LazyUniqueLongestChoice(getName()) {
            private static final long serialVersionUID = 1L;

            @Override
            public Parsers getLazyParsers() { return children; }
        };
    }

    @Override
    public Optional<RecursiveMode> getNotAstNodeSpecifier() {
        return Optional.empty();
    }
}
