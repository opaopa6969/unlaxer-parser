package org.unlaxer.dsl.bootstrap;

import org.unlaxer.CodePointLength;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.elementary.AbstractTokenParser;

/** Bootstrap leaf for a syntax-validated token body, including its mandatory semicolon. */
public class LexicalBodyParser extends AbstractTokenParser {
    private static final long serialVersionUID = 1L;
    @Override public Token getToken(ParseContext context, TokenKind kind, boolean invert) {
        String source = context.getSource().sourceAsString();
        int start = source.offsetByCodePoints(0, context.getPosition(kind).value());
        try {
            var result = LexicalSyntax.parseSyntax(source.substring(start));
            int length = source.codePointCount(start, start + result.end());
            if (!invert) return new Token(kind, context.peek(kind, new CodePointLength(length)), this);
        } catch (IllegalArgumentException invalid) { /* the outer parser reports the declaration failure */ }
        return Token.empty(kind, context.getCursor(kind), this);
    }
}
