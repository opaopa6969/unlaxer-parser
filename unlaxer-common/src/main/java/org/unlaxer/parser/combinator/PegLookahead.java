package org.unlaxer.parser.combinator;

import java.util.Optional;
import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.TerminalSymbol;

/**
 * A PEG assertion: evaluate the child normally, then restore both cursors, tokens
 * and registered transactional state. Unlike {@link MatchOnly}, no match-only
 * token or advanced matched cursor is published. Successful assertions also
 * discard speculative syntax diagnostics. External effects are not reversible.
 */
public final class PegLookahead extends ConstructedSingleChildParser implements TerminalSymbol {
    private static final long serialVersionUID = 1L;
    private final boolean positive;

    public PegLookahead(Parser child) { this(child, true); }
    public PegLookahead(Parser child, boolean positive) {
        super(child);
        this.positive = positive;
    }
    public PegLookahead(Name name, Parser child, boolean positive) {
        super(name, child);
        this.positive = positive;
    }

    @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
        // Assertion polarity is explicit, independent of Classic leaf inversion.
        return context.parsePegAssertion(this, getChild(), positive);
    }

    @Override public Optional<String> expectedDisplayText() {
        return positive ? Optional.empty() : Optional.of("negative lookahead");
    }
}
