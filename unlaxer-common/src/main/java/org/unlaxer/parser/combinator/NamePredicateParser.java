package org.unlaxer.parser.combinator;

import java.util.List;
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.parser.*;

/** Syntax followed by a pure, versioned name classification; generated capture sites supply names. */
public abstract class NamePredicateParser extends ConstructedSingleChildParser implements DiagnosticsAgnostic {
    private static final long serialVersionUID = 1L;
    private final String snapshot, version, expectedKind;
    protected NamePredicateParser(Parser child, String snapshot, String version, String expectedKind) {
        super(child);
        new NameSnapshot.Requirement(snapshot, version);
        if (!List.of("type", "value", "resolved").contains(expectedKind)) throw new IllegalArgumentException("invalid name predicate kind");
        this.snapshot = snapshot; this.version = version; this.expectedKind = expectedKind;
    }
    protected abstract List<Token> nameCaptureSites(Token root);
    @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
        context.startParse(this, context, kind, invert);
        context.begin(this);
        int entryStart = context.getConsumedPosition().value();
        Parsed parsed = getChild().parse(context, kind, invert);
        boolean accepted = parsed.isSucceeded();
        if (accepted) {
            var sites = nameCaptureSites(parsed.getConsumed());
            if (sites.size() != 1 || sites.get(0).source == null) {
                int at = entryStart;
                NameResolution.reject(context, "name_capture", at, at, "single nonempty name capture");
                accepted = false;
            } else {
                Token token = sites.get(0);
                String raw = token.source.sourceAsString();
                int leading = 0, trailing = raw.length();
                while (leading < trailing && raw.charAt(leading) <= 0x20) leading++;
                while (trailing > leading && raw.charAt(trailing - 1) <= 0x20) trailing--;
                String name = raw.substring(leading, trailing);
                int start = token.source.offsetFromRoot().value() + raw.codePointCount(0, leading);
                if (name.isEmpty()) {
                    NameResolution.reject(context, "name_capture", start, start, "single nonempty name capture");
                    accepted = false;
                } else accepted = NameResolution.matches(context, snapshot, version, expectedKind, name,
                    start, start + name.codePointCount(0, name.length()));
                if (!accepted && NameResolution.failure(context).isEmpty())
                    ErrorMessageParser.expected(expectedKind + " name in snapshot " + snapshot + "@" + version)
                        .parse(context, kind, invert);
            }
        }
        if (!accepted) {
            context.rollback(this);
            context.endParse(this, Parsed.FAILED, context, kind, invert);
            return Parsed.FAILED;
        }
        context.commit(this, kind);
        context.endParse(this, parsed, context, kind, invert);
        return parsed;
    }
}
