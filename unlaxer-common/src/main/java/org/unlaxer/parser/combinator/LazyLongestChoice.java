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

/** Lazy generated-rule form of {@link LongestChoiceInterface}. */
public abstract class LazyLongestChoice extends LazyCombinatorParser
        implements LongestChoiceInterface, LazyParserChildrenSpecifier {

    private static final long serialVersionUID = 1L;

    public LazyLongestChoice() { super(); }
    public LazyLongestChoice(Name name) { super(name); }

    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        return LongestChoiceInterface.super.parse(parseContext, tokenKind, invertMatch);
    }

    @Override
    public HasChildrenParser createWith(Parsers children) {
        return new LazyLongestChoice(getName()) {
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
