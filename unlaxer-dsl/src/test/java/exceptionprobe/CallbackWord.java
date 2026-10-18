package exceptionprobe;

import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.parser.elementary.WordParser;

/** Fixture-owned controls deliberately live outside transactional state. */
public abstract class CallbackWord extends WordParser {
    public static int failAt = -1;
    public static boolean abandoned;
    public static int[] registered;
    public static PortableTokenMetadata metadata;
    public static PortableTokenMetadata.NodeId oldId;
    public static PortableTokenMetadata.NodeId freshId;
    public static RuntimeException failure;

    protected CallbackWord(String word) {
        super(word);
    }

    @Override
    public Token getToken(ParseContext c, TokenKind kind, boolean inverse) {
        Token token = super.getToken(c, kind, inverse);
        if (token != null && c.position() == failAt) {
            if (abandoned) {
                c.startParse(this, c, kind, inverse);
                c.begin(this);
            }
            registered[0] = 99;
            metadata.putExtra(oldId, "label", "during");
            freshId = metadata.register(token);
            metadata.putRelated(oldId, "temporary", freshId);
            c.capture(
                    "word",
                    new CapturedValue(
                            token.source.sourceAsString(),
                            CapturedValue.Provenance.INPUT,
                            c.position(),
                            c.position() + 1));
            c.consume(new CodePointLength(1));
            throw failure;
        }
        return token;
    }
}
