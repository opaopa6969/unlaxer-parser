package org.unlaxer.dsl.semantic;

import java.util.*;
import org.unlaxer.dsl.semantic.SemanticModel.Span;

/** Immutable editor contract; partial syntax never masquerades as a complete typed AST. */
public final class EditorParseResult<T> {
    public enum Status { COMPLETE, PARTIAL, FAILED }
    public enum NodeKind { MISSING, ERROR }
    public record Node(NodeKind kind, Span span, List<String> candidateRules, String regionId) {
        public Node { candidateRules = List.copyOf(candidateRules); }
    }
    /** Zero-based argument index; the argument slot is owned by the retained semantic model, including zero-width slots. */
    public record CallSite(String callId, int argumentIndex, String regionId) {}
    public record Utf16Span(int start, int end) {}
    public static final class EditorException extends IllegalArgumentException {
        private final String code;
        private final Span span;
        private EditorException(String code, Span span) {
            super(code + " at " + span.start() + ".." + span.end()); this.code = code; this.span = span;
        }
        public String code() { return code; }
        public Span span() { return span; }
    }
    private static EditorException error(String code, Span span) { return new EditorException(code, span); }
    private final String uri;
    private final long version;
    private final String source;
    private final Status status;
    private final T ast;
    private final List<Node> nodes;
    private final SemanticModel semantics;
    private final List<CallSite> calls;
    private final int length;

    public EditorParseResult(String uri, long version, String source, Status status, T ast,
            List<Node> nodes, SemanticModel semantics, List<CallSite> calls) {
        this.uri = Objects.requireNonNull(uri); this.version = version;
        this.source = Objects.requireNonNull(source); this.status = Objects.requireNonNull(status);
        this.ast = ast; this.nodes = List.copyOf(nodes); this.semantics = semantics; this.calls = List.copyOf(calls);
        length = source.codePointCount(0, source.length());
        Span document = new Span(0, length);
        if (uri.isEmpty() || version < 0) throw error("EDITOR_INVALID_DOCUMENT", document);
        if (source.codePoints().anyMatch(cp -> cp >= 0xd800 && cp <= 0xdfff)) throw error("EDITOR_INVALID_SOURCE", document);
        if ((status == Status.COMPLETE) != (ast != null) || status == Status.COMPLETE && !nodes.isEmpty()
                || status == Status.PARTIAL && nodes.isEmpty()) throw error("EDITOR_INVALID_STATUS", document);
        for (Node node : nodes) {
            checkSpan(node.span()); checkRegion(node.regionId(), node.span());
            if (node.kind() == null || (node.kind() == NodeKind.MISSING) != (node.span().start() == node.span().end()))
                throw error("EDITOR_INVALID_NODE", node.span());
            Set<String> seen = new HashSet<>();
            for (String candidate : node.candidateRules()) {
                if (candidate.isEmpty() || !seen.add(candidate)) throw error("EDITOR_INVALID_CANDIDATE", node.span());
            }
        }
        if (semantics != null && (!uri.equals(semantics.uri()) || version != semantics.version()
                || !source.equals(semantics.source()))) throw error("EDITOR_SNAPSHOT_MISMATCH", document);
        Set<String> seen = new HashSet<>();
        for (CallSite site : calls) {
            SemanticModel.Call call = semantics == null ? null : semantics.calls().get(site.callId());
            if (call == null || site.argumentIndex() < 0 || site.argumentIndex() >= call.arguments().size())
                throw error("EDITOR_INVALID_CALL_SITE", document);
            Span slot = call.arguments().get(site.argumentIndex()).span();
            checkRegion(site.regionId(), slot);
            if (!seen.add(site.callId() + ":" + site.argumentIndex())) throw error("EDITOR_DUPLICATE_CALL_SITE", slot);
        }
    }
    private void checkSpan(Span span) {
        if (span.end() > length) throw error("EDITOR_SPAN_OUTSIDE_DOCUMENT", span);
    }
    private static void checkRegion(String region, Span span) {
        if (region != null && region.isEmpty()) throw error("EDITOR_INVALID_REGION", span);
    }
    public String uri() { return uri; }
    public long version() { return version; }
    public String source() { return source; }
    public Status status() { return status; }
    public Optional<T> strictAst() { return Optional.ofNullable(ast); }
    public List<Node> nodes() { return nodes; }
    public Optional<SemanticModel> semantics() { return Optional.ofNullable(semantics); }
    public List<CallSite> calls() { return calls; }
    public Utf16Span utf16Span(Span span) {
        checkSpan(span);
        return new Utf16Span(source.offsetByCodePoints(0, span.start()), source.offsetByCodePoints(0, span.end()));
    }
    /** Innermost argument slot, with an optional exact region filter. Slot endpoints are inclusive. */
    public Optional<CallSite> callAt(int cursor, String regionId) {
        if (cursor < 0 || cursor > length) throw error("EDITOR_INVALID_CURSOR", new Span(Math.max(0, cursor), Math.max(0, cursor)));
        return calls.stream().filter(site -> regionId == null || regionId.equals(site.regionId()))
            .filter(site -> slot(site).start() <= cursor && cursor <= slot(site).end())
            .min(Comparator.comparingInt((CallSite site) -> slot(site).end() - slot(site).start())
                .thenComparing(CallSite::callId, EditorParseResult::compareText).thenComparingInt(CallSite::argumentIndex));
    }
    private static int compareText(String left, String right) {
        var a = left.codePoints().iterator(); var b = right.codePoints().iterator();
        while (a.hasNext() && b.hasNext()) {
            int comparison = Integer.compare(a.nextInt(), b.nextInt());
            if (comparison != 0) return comparison;
        }
        return Boolean.compare(a.hasNext(), b.hasNext());
    }
    private Span slot(CallSite site) { return semantics.calls().get(site.callId()).arguments().get(site.argumentIndex()).span(); }
    public List<String> expectedTypesAt(int cursor, String regionId) {
        return callAt(cursor, regionId).map(site -> semantics.expectedTypes(site.callId(), site.argumentIndex())).orElseGet(List::of);
    }
    public List<SemanticModel.Completion> completeAt(int cursor, String regionId, long snapshotVersion, String prefix) {
        if (snapshotVersion != version) throw error("EDITOR_STALE_SNAPSHOT", new Span(0, length));
        return callAt(cursor, regionId).map(site -> semantics.completeArgument(site.callId(), site.argumentIndex(), cursor,
            snapshotVersion, prefix)).orElseGet(List::of);
    }
}
