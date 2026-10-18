package org.unlaxer.parser.combinator;

import java.util.Objects;
import org.unlaxer.parser.Parser;

/** Explicit nested capture CST markers; legacy capture binding/flattening is unchanged. */
public final class CaptureBoundaryParser extends Chain {
    private static final long serialVersionUID = 1L;
    public enum Kind { VALUE, OPTIONAL, LIST }
    private final Kind kind;
    private final boolean inputOnly;
    private static final org.unlaxer.Name ORIGIN = org.unlaxer.Name.of("capture-boundary-origin");
    /** Input/projected extents are logical CP ranges; generated text has a zero-width anchor. */
    public record Origin(boolean generated, int start, int end) implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
    }
    public static java.util.Optional<Origin> originOf(org.unlaxer.Token token) {
        return token.getExtraObject(ORIGIN);
    }
    @Override public org.unlaxer.Parsed parse(org.unlaxer.context.ParseContext context, org.unlaxer.TokenKind kind, boolean invert) {
        int anchor = context.getPosition(kind).value();
        org.unlaxer.Parsed result = super.parse(context, kind, invert);
        if (result.isSucceeded()) {
            var token = result.getRootToken();
            var source = token.source;
            var input = context.getSource();
            boolean generated = source.codePointLength().value() > 0 && (source.sourceKind().isDetached() || source.root() != input.root());
            int start = generated ? anchor : source.offsetFromRoot().value() - input.offsetFromRoot().value();
            int end = generated ? anchor : Math.addExact(start,source.codePointLength().value());
            token.putExtraObject(ORIGIN,new Origin(generated,start,end));
        }
        return result;
    }

    private CaptureBoundaryParser(Kind kind, Parser child) {
        this(kind, child, false);
    }
    private CaptureBoundaryParser(Kind kind, Parser child, boolean inputOnly) {
        super(Objects.requireNonNull(child));
        this.kind = Objects.requireNonNull(kind);
        this.inputOnly = inputOnly;
    }
    /** Generated AST fields require original input before Chain filters or collects tokens. */
    public static CaptureBoundaryParser input(Kind kind, Parser child) {
        return new CaptureBoundaryParser(kind, child, true);
    }
    @Override public org.unlaxer.Parsed parseChainElement(Parser child,
            org.unlaxer.context.ParseContext context, org.unlaxer.TokenKind kind, boolean invert) {
        var parsed = child.parse(context, kind, invert);
        if (inputOnly && parsed.isSucceeded()
                && (parsed.getTokenOptional().isPresent() || !parsed.getOriginalTokens().isEmpty())
                && org.unlaxer.CapturedValue.provenanceOf(parsed.getRootToken().source, context.getSource())
                    != org.unlaxer.CapturedValue.Provenance.INPUT) {
            return org.unlaxer.parser.ErrorMessageParser.expected(
                "AST field capture requires original input source; use CAPTURE/SAME_AS for general replay")
                .parse(context, kind, invert);
        }
        return parsed;
    }
    /** Advanced grouping/separated boundary; child boundaries define the preserved items. */
    public static CaptureBoundaryParser wrap(Kind kind, Parser child) {
        return new CaptureBoundaryParser(kind, child);
    }
    public Kind boundaryKind() { return kind; }
    /** One complete value, including an empty successful value. */
    public static CaptureBoundaryParser value(Parser child) {
        return new CaptureBoundaryParser(Kind.VALUE, child);
    }
    private static Parser item(Parser child) {
        return child instanceof CaptureBoundaryParser ? child : value(child);
    }
    /** An absent optional differs from a present empty list or value. */
    public static CaptureBoundaryParser optional(Parser child) {
        return new CaptureBoundaryParser(Kind.OPTIONAL, new Optional(item(child)));
    }
    /** Existing Repeat semantics, including zero-bound no evaluation and nullable progress guards. */
    public static CaptureBoundaryParser list(Parser child, int min, int max) {
        return new CaptureBoundaryParser(Kind.LIST, new Repeat(item(child), min, max));
    }
    public static CaptureBoundaryParser list(Parser child) {
        return list(child, 0, Integer.MAX_VALUE);
    }
}
