package org.unlaxer.parser.elementary;

import java.util.Objects;
import java.util.Optional;

import org.unlaxer.CapturedValue;
import org.unlaxer.CodePointLength;
import org.unlaxer.Parsed;
import org.unlaxer.Source;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.TerminalSymbol;
import org.unlaxer.parser.combinator.NoneChildParser;

/** Match the latest capture's text at the current input cursor. */
public class ReplayParser extends NoneChildParser implements TerminalSymbol {
    private static final long serialVersionUID = 1L;
    private final String binding;

    public ReplayParser(String binding) { this.binding = Objects.requireNonNull(binding, "binding"); }
    public String binding() { return binding; }
    @Override public Parser getParser() { return this; }
    @Override public Parser createParser() { return this; }
    @Override public Optional<String> expectedDisplayText() { return Optional.of("SAME_AS(" + binding + ")"); }

    @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
        context.enterParserCall();
        try {
            context.startParse(this, context, kind, invert);
            Optional<CapturedValue> captured = context.captured(binding);
            int width = captured.map(value -> value.text().codePointCount(0, value.text().length())).orElse(0);
            Source actual = context.peek(kind, new CodePointLength(width));
            boolean success = captured.isPresent() && actual.codePointLength().value() == width
                    && (actual.sourceAsString().equals(captured.get().text()) != invert);
            Parsed result = Parsed.FAILED;
            if (success) {
                Token token = new Token(kind, actual, this);
                context.getCurrent().addToken(token, kind);
                if (kind.isConsumed()) context.consume(new CodePointLength(width));
                else context.matchOnly(new CodePointLength(width));
                result = new Parsed(token);
            } else if (kind.isConsumed()) {
                context.consume(new CodePointLength(0));
            }
            context.endParse(this, result, context, kind, invert);
            return result;
        } catch (RuntimeException | Error failure) {
            context.failParserCall(failure);
            throw failure;
        } finally {
            context.leaveParserCall();
        }
    }
}
