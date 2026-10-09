package org.unlaxer.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageRegions.Language;
import org.unlaxer.source.LanguageRegions.Region;
import org.unlaxer.source.LanguageRegions.State;
import org.unlaxer.source.SegmentSourceMap.Kind;
import org.unlaxer.source.SegmentSourceMap.Location;
import org.unlaxer.source.SegmentSourceMap.Segment;

/** Bounded, recursive grammar calls over exact captured source slices. */
public final class EmbeddedLanguages {
    private EmbeddedLanguages() {}
    /** Ranges belong to the provider input, including delimiters only in full. */
    public record Child(Language language, Span full, Span body) {
        public Child {
            Objects.requireNonNull(language);
            if (false == full.contains(body)) { throw new IllegalArgumentException("body outside embedding"); }
        }
    }
    public record Parsed(DocumentSnapshot snapshot, State state, List<Child> children) {
        public Parsed {
            Objects.requireNonNull(snapshot); Objects.requireNonNull(state);
            children = List.copyOf(children);
            if (state != State.COMPLETE && state != State.PARTIAL && false == children.isEmpty()) {
                throw new IllegalArgumentException("failed parse cannot provide child regions");
            }
            for (int index = 0; index < children.size(); index++) {
                Child child = children.get(index);
                snapshot.check(child.full);
                for (int otherIndex = 0; otherIndex < index; otherIndex++) {
                    Span other = children.get(otherIndex).full;
                    if (child.full.start() < other.end() && other.start() < child.full.end()) {
                        throw new IllegalArgumentException("overlapping embedded siblings");
                    }
                }
            }
        }
    }
    public interface Grammar {
        String name();
        Parsed parse(String entry, DocumentSnapshot snapshot);
    }
    public record Result(DocumentSnapshot snapshot, List<Region> regions) {
        public Result { regions = List.copyOf(regions); new LanguageRegions(snapshot, regions); }
        public LanguageRegions tree() { return new LanguageRegions(snapshot, regions); }
        public String canonicalJson() {
            List<String> rows = new ArrayList<>();
            for (Region region : regions) {
                rows.add("{\"id\":" + quote(region.id()) + ",\"parent\":" + (region.parent() == null ? "null" : quote(region.parent()))
                    + ",\"language\":" + quote(region.language().id()) + ",\"grammar\":" + quote(region.language().grammar())
                    + ",\"entry\":" + quote(region.language().entry()) + ",\"state\":" + quote(region.parseState().name())
                    + ",\"full\":[" + region.full().start() + "," + region.full().end() + "],\"body\":[" + region.body().start() + "," + region.body().end() + "]}");
            }
            return "{\"uri\":" + quote(snapshot.uri()) + ",\"version\":" + quote(Long.toString(snapshot.version())) + ",\"regions\":[" + String.join(",", rows) + "]}";
        }
        private static String quote(String value) {
            StringBuilder out = new StringBuilder("\"");
            value.codePoints().forEach(c -> {
                if (c == '"' || c == '\\') { out.append('\\').appendCodePoint(c); }
                else if (c == '\n') { out.append("\\n"); }
                else if (c == '\r') { out.append("\\r"); }
                else if (c == '\t') { out.append("\\t"); }
                else if (c < 32) { out.append(String.format(java.util.Locale.ROOT, "\\u%04x", c)); }
                else { out.appendCodePoint(c); }
            });
            return out.append('"').toString();
        }
    }
    public static Result parse(DocumentSnapshot snapshot, Language language,
            Map<Language, Grammar> providers, int maximumDepth, int maximumRegions) {
        if (maximumDepth < 1 || maximumDepth > 64 || maximumRegions < 1 || maximumRegions > 10000) {
            throw new IllegalArgumentException("invalid embedding budget");
        }
        List<Region> regions = new ArrayList<>();
        visit(snapshot, snapshot, language, Map.copyOf(providers), "root", null,
            new Span(0, snapshot.length()), new Span(0, snapshot.length()), 1,
            maximumDepth, maximumRegions, regions);
        return new Result(snapshot, regions);
    }
    private static void visit(DocumentSnapshot host, DocumentSnapshot input, Language language,
            Map<Language, Grammar> providers, String id, String parent, Span full, Span body,
            int depth, int maximumDepth, int maximumRegions, List<Region> regions) {
        if (depth > maximumDepth || regions.size() >= maximumRegions) {
            throw new IllegalArgumentException("embedding budget exceeded");
        }
        Grammar grammar = providers.get(language);
        Parsed parsed;
        if (grammar == null) { parsed = new Parsed(input, State.UNAVAILABLE, List.of()); }
        else {
            if (false == grammar.name().equals(language.grammar())) { throw new IllegalArgumentException("grammar identity mismatch"); }
            parsed = grammar.parse(language.entry(), input);
            if (false == input.equals(parsed.snapshot)) { throw new IllegalArgumentException("stale grammar response"); }
        }
        SegmentSourceMap map = new SegmentSourceMap(input, List.of(new Segment(new Span(0, input.length()), Kind.COPY, new Location(host, body))));
        regions.add(new Region(id, parent, language, full, body, map, parsed.state));
        int index = 0;
        for (Child child : parsed.children) {
            String childId = id + "/" + index++;
            DocumentSnapshot childInput = new DocumentSnapshot(host.uri() + "#embedded/" + childId,
                host.version(), input.slice(child.body));
            visit(host, childInput, child.language, providers, childId, id,
                shift(child.full, body.start()), shift(child.body, body.start()), depth + 1,
                maximumDepth, maximumRegions, regions);
        }
    }
    private static Span shift(Span span, int offset) { return new Span(span.start() + offset, span.end() + offset); }
}
