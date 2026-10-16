package org.unlaxer.parser.elementary;

import java.util.Optional;
import org.unlaxer.CodePointLength;
import org.unlaxer.Name;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.TerminalSymbol;

/** One Unicode 17 extended grapheme cluster; token/cursor positions remain code points. */
public class GraphemeParser extends AbstractTokenParser implements TerminalSymbol {
    private static final long serialVersionUID = 1L;
    public GraphemeParser() { super(); }
    public GraphemeParser(Name name) { super(name); }
    @Override public Token getToken(ParseContext context, TokenKind tokenKind, boolean invertMatch) {
        int start = context.getPosition(tokenKind).value();
        int end = context.graphemeBoundaries().nextCodePoint(start);
        // Like ANY, this terminal has no complement at a complete cluster boundary.
        if (invertMatch || end < 0) { return Token.empty(tokenKind, context.getCursor(TokenKind.consumed), this); }
        return new Token(tokenKind, context.peek(tokenKind, new CodePointLength(end - start)), this);
    }
    @Override public Optional<String> expectedDisplayText() { return Optional.of("grapheme cluster"); }
}
