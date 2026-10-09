package example.shared;
import org.unlaxer.*;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.TerminalSymbol;
import org.unlaxer.parser.elementary.AbstractTokenParser;
public class StateToken extends AbstractTokenParser implements TerminalSymbol {
    public Token getToken(ParseContext context, TokenKind kind, boolean invert) {
        if (context.getGlobalScopeTreeMap().containsKey(Name.of("sentinel"))) throw new AssertionError("caller state leaked to child");
        context.getGlobalScopeTreeMap().put(Name.of("sentinel"), "child");
        context.put(this, Name.of("private"), "child");
        int start = context.getPosition(kind).value();
        String source = context.sourceText();
        if (start < source.codePointCount(0, source.length()) && source.codePointAt(source.offsetByCodePoints(0, start)) == 'a')
            return new Token(kind, context.peek(context.getPosition(kind), new CodePointLength(1)), this);
        return Token.empty(kind, context.getCursor(kind), this);
    }
}
