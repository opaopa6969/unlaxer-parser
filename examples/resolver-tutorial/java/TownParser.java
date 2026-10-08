package example.resolvers;

import org.unlaxer.CodePointLength;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.TerminalSymbol;
import org.unlaxer.parser.elementary.AbstractTokenParser;

public class TownParser extends AbstractTokenParser implements TerminalSymbol {
    private static final long serialVersionUID = 1L;
    public static final String BINDING = "address.towns";
    @Override public Token getToken(ParseContext context, TokenKind kind, boolean invert) {
        int cp = context.getPosition(kind).value();
        String source = context.sourceText();
        int start = source.offsetByCodePoints(0, cp);
        for (String word : context.bindingValues(BINDING)) {
            if (source.startsWith(word, start)) {
                if (invert) break;
                return new Token(kind, context.peek(context.getPosition(kind),
                    new CodePointLength(word.codePointCount(0, word.length()))), this);
            }
        }
        return Token.empty(kind, context.getCursor(kind), this);
    }
}
