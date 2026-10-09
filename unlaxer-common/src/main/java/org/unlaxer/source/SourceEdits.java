package org.unlaxer.source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageRegions.Edit;

/** Original-source pieces and checked edit plans; no pretty-print round trip is involved. */
public final class SourceEdits {
    public enum Kind { TOKEN, WHITESPACE, COMMENT, UNPARSED }
    public enum Operation { RENAME, FORMAT, CODE_ACTION }
    /** A single-host query must never silently return only part of a workspace rename. */
    public static Plan singleDocument(List<Plan> plans) {
        if(plans.size()!=1)throw new IllegalArgumentException("single-document edit plan required");
        return Objects.requireNonNull(plans.get(0));
    }
    /** Explicit language policies produce checked single-document plans, never implicit workspace edits. */
    public static final class QueryProvider implements LanguageQueries.Provider {
        private final SourceEdits source;
        private final LanguageRegions.Language language;
        private final LanguageQueries.Project project;
        private final Map<Operation, Function<LanguageQueries.Request, Plan>> policies;
        public QueryProvider(SourceEdits source, LanguageRegions.Language language, LanguageQueries.Project project,
                Map<Operation, Function<LanguageQueries.Request, Plan>> policies) {
            this.source=Objects.requireNonNull(source);this.language=Objects.requireNonNull(language);
            this.project=Objects.requireNonNull(project);this.policies=Map.copyOf(policies);
        }
        @Override public Set<LanguageRegions.Operation> capabilities() {
            return policies.keySet().stream().map(op->LanguageRegions.Operation.valueOf(op.name())).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        @Override public LanguageQueries.Response query(LanguageQueries.Request request) {
            var snapshot=source.snapshot();
            if(!project.equals(request.project())||!language.equals(request.region().language())||!snapshot.equals(request.region().sourceMap().output()))
                throw new IllegalArgumentException("stale edit provider binding");
            if(!capabilities().contains(request.operation()))return response(LanguageRegions.State.UNSUPPORTED,List.of());
            if(request.region().parseState()!=LanguageRegions.State.COMPLETE&&request.region().parseState()!=LanguageRegions.State.PARTIAL)
                return response(LanguageRegions.State.FAILED,List.of());
            Operation operation=Operation.valueOf(request.operation().name());
            Plan plan=Objects.requireNonNull(policies.get(operation).apply(request));
            if(!plan.snapshot().equals(snapshot)||plan.operation()!=operation)throw new IllegalArgumentException("stale or mismatched edit plan");
            source.plan(operation,plan.allowed(),plan.edits());
            var edits=plan.edits().stream().map(edit->new LanguageQueries.TextEdit(new SegmentSourceMap.Location(snapshot,edit.span()),edit.replacement())).toList();
            return response(request.region().parseState(),List.of(new LanguageQueries.Item(operation.name(),"source-preserving",List.of(),edits)));
        }
        private LanguageQueries.Response response(LanguageRegions.State state,List<LanguageQueries.Item> items) {
            return new LanguageQueries.Response(source.snapshot(),project.id(),project.version(),state,items);
        }
    }
    /** Empty owner means document-owned trivia. Token and unparsed pieces have no owner. */
    public record Piece(String id, Span span, Kind kind, String owner) {
        public Piece {
            Objects.requireNonNull(id); Objects.requireNonNull(span); Objects.requireNonNull(kind); Objects.requireNonNull(owner);
            if (id.isEmpty() || span.length() == 0) { throw new IllegalArgumentException("invalid piece"); }
        }
    }
    public static final class Plan {
        private final DocumentSnapshot snapshot;
        private final Operation operation;
        private final Span allowed;
        private final List<Edit> edits;
        private Plan(DocumentSnapshot snapshot, Operation operation, Span allowed, List<Edit> edits) {
            Objects.requireNonNull(snapshot); Objects.requireNonNull(operation); snapshot.check(allowed);
            this.snapshot = snapshot; this.operation = operation; this.allowed = allowed;
            this.edits = List.copyOf(edits);
            LanguageRegions.validateEdits(edits);
            for (Edit edit : edits) {
                if (false == allowed.contains(edit.span())) { throw new IllegalArgumentException("edit outside allowed range"); }
                new DocumentSnapshot(snapshot.uri(), snapshot.version(), edit.replacement());
            }
        }
        public DocumentSnapshot snapshot() { return snapshot; }
        public Operation operation() { return operation; }
        public Span allowed() { return allowed; }
        public List<Edit> edits() { return edits; }
        public DocumentSnapshot apply(DocumentSnapshot current, long version) {
            return new LanguageRegions(snapshot, List.of()).apply(current, version, edits);
        }
    }
    private final DocumentSnapshot snapshot;
    private final List<Piece> pieces;
    private final Map<String, Piece> byId = new HashMap<>();
    public SourceEdits(DocumentSnapshot snapshot, List<Piece> pieces) {
        this.snapshot = Objects.requireNonNull(snapshot);
        this.pieces = List.copyOf(pieces);
        int cursor = 0;
        for (Piece piece : pieces) {
            snapshot.check(piece.span);
            if (piece.span.start() != cursor || byId.put(piece.id, piece) != null) {
                throw new IllegalArgumentException("pieces must uniquely partition source");
            }
            if (piece.kind == Kind.WHITESPACE && false == whitespace(snapshot.slice(piece.span))) {
                throw new IllegalArgumentException("non-whitespace trivia");
            }
            if ((piece.kind == Kind.TOKEN || piece.kind == Kind.UNPARSED) && false == piece.owner.isEmpty()) {
                throw new IllegalArgumentException("only trivia can have an owner");
            }
            cursor = piece.span.end();
        }
        if (cursor != snapshot.length()) { throw new IllegalArgumentException("incomplete piece inventory"); }
        for (Piece piece : pieces) {
            if (piece.owner.isEmpty()) { continue; }
            Piece owner = byId.get(piece.owner);
            if (owner == null || owner.kind != Kind.TOKEN) { throw new IllegalArgumentException("invalid trivia owner"); }
            Span gap = owner.span.end() <= piece.span.start() ? new Span(owner.span.end(), piece.span.start())
                    : new Span(piece.span.end(), owner.span.start());
            for (Piece between : pieces) {
                if (gap.contains(between.span) && (between.kind == Kind.TOKEN || between.kind == Kind.UNPARSED)) {
                    throw new IllegalArgumentException("trivia crosses another token");
                }
            }
        }
    }
    public DocumentSnapshot snapshot() { return snapshot; }
    public List<Piece> pieces() { return pieces; }
    public Piece token(String id) {
        Piece piece = byId.get(id);
        if (piece == null || piece.kind != Kind.TOKEN) { throw new IllegalArgumentException("not a token"); }
        return piece;
    }
    public String roundTrip() {
        StringBuilder result = new StringBuilder();
        for (Piece piece : pieces) { result.append(snapshot.slice(piece.span)); }
        return result.toString();
    }
    public Plan plan(Operation operation, Span allowed, List<Edit> edits) {
        for (Edit edit : edits) {
            snapshot.check(edit.span());
            if (operation == Operation.RENAME) {
                if (pieces.stream().noneMatch(piece -> piece.kind == Kind.TOKEN && piece.span.equals(edit.span()))) {
                    throw new IllegalArgumentException("rename must replace a complete token");
                }
            } else if (operation == Operation.FORMAT) {
                if (false == whitespace(edit.replacement())) { throw new IllegalArgumentException("format replacement is not whitespace"); }
                boolean covered = false;
                for (Piece piece : pieces) {
                    if (piece.kind == Kind.WHITESPACE && piece.span.contains(edit.span())) { covered = true; }
                }
                if (false == covered) { throw new IllegalArgumentException("format may only edit existing whitespace"); }
            }
        }
        return new Plan(snapshot, operation, allowed, edits);
    }
    public Plan map(Plan child, SegmentSourceMap map, Span allowed) {
        if (false == child.snapshot().equals(map.output())) { throw new IllegalArgumentException("stale source map"); }
        List<Edit> edits = new ArrayList<>();
        for (Edit edit : child.edits()) {
            SegmentSourceMap.Location origin = map.edit(edit.span());
            if (false == snapshot.equals(origin.snapshot())) { throw new IllegalArgumentException("different host snapshot"); }
            edits.add(new Edit(origin.span(), edit.replacement()));
        }
        return plan(child.operation(), allowed, edits);
    }
    /** Build all new snapshots before returning; no original document is mutated. */
    public static Map<String, DocumentSnapshot> applyAll(List<Plan> plans, Map<String, DocumentSnapshot> current,
                                                        Map<String, Long> versions) {
        Map<String, DocumentSnapshot> result = new HashMap<>();
        for (Plan plan : plans) {
            String uri = plan.snapshot().uri();
            if (result.containsKey(uri) || false == versions.containsKey(uri) || false == current.containsKey(uri)) {
                throw new IllegalArgumentException("duplicate or missing document");
            }
            result.put(uri, plan.apply(current.get(uri), versions.get(uri)));
        }
        return Map.copyOf(result);
    }
    private static boolean whitespace(String text) {
        return text.codePoints().allMatch(point -> point == ' ' || point == '\t' || point == '\r' || point == '\n');
    }
}
