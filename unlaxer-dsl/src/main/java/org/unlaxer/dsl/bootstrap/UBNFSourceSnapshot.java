package org.unlaxer.dsl.bootstrap;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.unlaxer.Token;
import org.unlaxer.dsl.bootstrap.UBNFAST.AnnotatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.UBNFFile;

/**
 * Owned source and identity-indexed origins from one UBNF parse. Does not retain a
 * ParseContext, parser, or token tree. Equal record values at different occurrences
 * have distinct origins; records constructed by callers have no origin here.
 */
public final class UBNFSourceSnapshot {
    /** Half-open offsets in Unicode code points, not Java UTF-16 code units. */
    public record Span(int start, int end) {
        public Span {
            if (start < 0 || end < start) throw new IllegalArgumentException("invalid source span");
        }

        Span through(Span last) { return new Span(start, last.end); }
    }

    public enum Kind {
        /** Direct syntax from the input (excluding outer trivia). */
        SOURCE,
        /** A mapper-created wrapper, with the span of the syntax it wraps. */
        SYNTHETIC,
        /** A node reconstructed by the legacy typeof/capture repair. */
        REWRITTEN
    }

    public record Origin(Span span, Kind kind) {
        public Origin {
            Objects.requireNonNull(span);
            Objects.requireNonNull(kind);
        }
    }

    private final UBNFFile ast;
    private final String source;
    private final Map<Object, Origin> origins;
    private final Map<AnnotatedElement, Span> captures;

    UBNFSourceSnapshot(UBNFFile ast, Builder builder) {
        this.ast = ast;
        source = builder.source;
        origins = new IdentityHashMap<>(builder.origins);
        captures = new IdentityHashMap<>(builder.captures);
    }

    public UBNFFile ast() { return ast; }
    public String source() { return source; }
    public Optional<Origin> originOf(Object node) { return Optional.ofNullable(origins.get(node)); }
    public Optional<Span> spanOf(Object node) { return originOf(node).map(Origin::span); }
    /** Capture suffix including '@', keyed by its owning annotated element. */
    public Optional<Span> captureSpan(AnnotatedElement owner) {
        return Optional.ofNullable(captures.get(owner));
    }
    public Optional<String> sourceOf(Object node) { return spanOf(node).map(this::slice); }

    public String slice(Span span) {
        int start = source.offsetByCodePoints(0, span.start());
        int end = source.offsetByCodePoints(start, span.end() - span.start());
        return source.substring(start, end);
    }

    static final class Builder {
        final String source;
        final Map<Object, Origin> origins = new IdentityHashMap<>();
        final Map<AnnotatedElement, Span> captures = new IdentityHashMap<>();
        private final Map<Token, Optional<Span>> tokenSpans = new IdentityHashMap<>();

        Builder(String source) { this.source = source; }

        <T> T bind(T node, Token token) {
            tokenSpan(token).ifPresent(span -> origins.putIfAbsent(node, new Origin(span, Kind.SOURCE)));
            return node;
        }

        <T> T derive(T node, Object from, Kind kind) {
            Origin origin = origins.get(from);
            if (origin != null) origins.put(node, new Origin(origin.span(), kind));
            return node;
        }

        <T> T extend(T node, Object from, Token last) {
            Origin origin = origins.get(from);
            if (origin != null) tokenSpan(last).ifPresent(span ->
                origins.put(node, new Origin(origin.span().through(span), Kind.SOURCE)));
            return node;
        }

        // Use the actual CST, not another lexer or a search for equal source text.
        // Original children retain punctuation. Delimiter subtrees are trivia;
        // whitespace/comment-looking characters inside a quoted token are not.
        Optional<Span> tokenSpan(Token token) {
            Optional<Span> cached = tokenSpans.get(token);
            if (cached != null) return cached;
            Optional<Span> result = Optional.empty();
            if (!(token.parser instanceof UBNFParsers.UBNFSpaceDelimitor)) {
                if (token.getOriginalChildren().isEmpty()) {
                    if (token.source != null && token.source.codePointLength().value() > 0) {
                        int start = token.source.offsetFromRoot().value();
                        result = Optional.of(new Span(start, start + token.source.codePointLength().value()));
                    }
                } else {
                    for (Token child : token.getOriginalChildren()) {
                        Optional<Span> span = tokenSpan(child);
                        if (span.isPresent()) result = result.isEmpty() ? span
                            : Optional.of(result.get().through(span.get()));
                    }
                }
            }
            tokenSpans.put(token, result);
            return result;
        }
    }
}
