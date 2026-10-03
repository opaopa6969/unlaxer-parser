package org.unlaxer.dsl.runtime;

import java.util.Optional;
import org.unlaxer.CodePointLength;
import org.unlaxer.Parsed;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.DiagnosticsAgnostic;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.TerminalSymbol;
import org.unlaxer.parser.combinator.NoneChildParser;

/** Atomic lexical program, committing only its final source slice to the host transaction. */
public class LexicalTokenParser extends NoneChildParser implements TerminalSymbol, DiagnosticsAgnostic {
    private static final long serialVersionUID = 1L;
    private final LexicalExpression expression;
    private final String label;
    public LexicalTokenParser(String label, LexicalExpression expression) {
        this.label = label;
        this.expression = expression;
    }
    @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
        context.startParse(this, context, kind, invert);
        String source = context.getSource().sourceAsString();
        int cp = context.getPosition(kind).value();
        int start = source.offsetByCodePoints(0, cp);
        int end = expression.match(source, start);
        // Inverted use is a pure assertion; it never guesses a consumption length.
        boolean success = (end >= 0) != invert;
        if (!success) {
            context.endParse(this, Parsed.FAILED, context, kind, invert);
            return Parsed.FAILED;
        }
        var length = new CodePointLength(invert ? 0 : source.codePointCount(start, end));
        var token = new Token(kind, context.peek(context.getPosition(kind), length), this);
        context.getCurrent().addToken(token, kind);
        if (length.value() > 0) {
            if (kind.isConsumed()) context.consume(length); else context.matchOnly(length);
        }
        Parsed parsed = new Parsed(token);
        context.endParse(this, parsed, context, kind, invert);
        return parsed;
    }
    @Override public Optional<String> expectedDisplayText() { return Optional.of(label); }
    @Override public org.unlaxer.parser.Parser createParser() { return this; }
    @Override public org.unlaxer.parser.Parser getParser() { return this; }
}
