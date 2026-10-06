package example.bindings;

import java.util.List;
import org.unlaxer.CodePointLength;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.TerminalSymbol;
import org.unlaxer.parser.elementary.AbstractTokenParser;

/** Ordered dictionary snapshot, selected by the host's region binding. */
public class DictionaryParser extends AbstractTokenParser implements TerminalSymbol {
    private static final long serialVersionUID = 1L;

    @Override public Token getToken(ParseContext context, TokenKind kind, boolean invert) {
        List<String> regions = context.bindingValues("region");
        String region = regions.isEmpty() ? "default" : regions.get(0);
        int cp = context.getPosition(kind).value();
        String source = context.sourceText();
        String remaining = source.substring(source.offsetByCodePoints(0, cp));
        for (String word : context.bindingValues("words." + region)) {
            // An empty dictionary entry never creates a zero-length token.
            if (!word.isEmpty() && remaining.startsWith(word)) {
                if (invert) break;
                return new Token(kind, context.peek(context.getPosition(kind),
                    new CodePointLength(word.codePointCount(0, word.length()))), this);
            }
        }
        return Token.empty(kind, context.getCursor(kind), this);
    }
}
