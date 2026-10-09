package org.unlaxer.source;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.SegmentSourceMap.Location;

/** Snapshot-bound region dispatch independent of AST projection and parser implementation. */
public final class LanguageRegions {
    public enum State { COMPLETE, PARTIAL, FAILED, UNAVAILABLE, UNSUPPORTED, TIMEOUT }
    public enum Operation { PARSE, VALIDATE, COMPLETION, HOVER, DEFINITION, RENAME, FORMAT, CODE_ACTION }
    public record Language(String id, String packageId, String version, String grammar, String entry) {
        public Language {
            for (String value : List.of(id, packageId, version, grammar, entry)) {
                if (value.isEmpty()) { throw new IllegalArgumentException("empty language identity"); }
            }
        }
    }
    /** full/body use the host snapshot; sourceMap.output() is the provider's virtual document. */
    public record Region(String id, String parent, Language language, Span full, Span body,
                         SegmentSourceMap sourceMap, State parseState) {
        public Region {
            Objects.requireNonNull(id); Objects.requireNonNull(language); Objects.requireNonNull(full);
            Objects.requireNonNull(body); Objects.requireNonNull(sourceMap); Objects.requireNonNull(parseState);
            if (id.isEmpty() || false == full.contains(body)) { throw new IllegalArgumentException("invalid region"); }
        }
    }
    public record Edit(Span span, String replacement) {
        public Edit { Objects.requireNonNull(span); Objects.requireNonNull(replacement); }
    }
    public record Response(DocumentSnapshot snapshot, State state, List<Span> diagnostics, List<Edit> edits) {
        public Response {
            Objects.requireNonNull(snapshot); Objects.requireNonNull(state);
            diagnostics = List.copyOf(diagnostics); edits = List.copyOf(edits);
            for (Span span : diagnostics) { snapshot.check(span); }
            for (Edit edit : edits) { snapshot.check(edit.span); }
            if (state != State.COMPLETE && state != State.PARTIAL && false == edits.isEmpty()) {
                throw new IllegalArgumentException("failed response cannot contain edits");
            }
        }
    }
    public interface Provider {
        Set<Operation> capabilities();
        Response invoke(Region region, Operation operation);
    }
    public record Dispatch(State state, List<SegmentSourceMap.Mapping> diagnostics, List<Edit> edits) {
        public Dispatch { diagnostics = List.copyOf(diagnostics); edits = List.copyOf(edits); }
    }
    private final DocumentSnapshot host;
    private final Map<String, Region> regions = new HashMap<>();
    private final Set<String> openEnds;
    static Set<String> partialOpenEnds(List<Region> input) {
        return input.stream().filter(region -> region.parseState == State.PARTIAL && region.full.end() == region.body.end())
            .map(Region::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    /** Compatibility path for callers without enclosure metadata. */
    public LanguageRegions(DocumentSnapshot host, List<Region> input) { this(host, input, partialOpenEnds(input)); }
    public LanguageRegions(DocumentSnapshot host, List<Region> input, Set<String> openEnds) {
        this.host = Objects.requireNonNull(host);
        this.openEnds = Set.copyOf(openEnds);
        for (Region region : input) {
            host.check(region.full);
            if (regions.put(region.id, region) != null) { throw new IllegalArgumentException("duplicate region"); }
            for (SegmentSourceMap.Mapping mapped : region.sourceMap.diagnostics(new Span(0, region.sourceMap.output().length()))) {
                if (false == mapped.location().snapshot().equals(host) || false == region.body.contains(mapped.location().span())) {
                    throw new IllegalArgumentException("origin outside region body");
                }
            }
        }
        for (String id : this.openEnds) {
            Region region = regions.get(id);
            if (region == null || region.full.end() != region.body.end()) throw new IllegalArgumentException("invalid open region boundary");
        }
        for (Region region : input) {
            Set<String> ancestors = new HashSet<>();
            Region current = region;
            while (current.parent != null) {
                if (false == ancestors.add(current.id)) { throw new IllegalArgumentException("cyclic region"); }
                Region parent = regions.get(current.parent);
                if (parent == null || false == parent.body.contains(current.full)) {
                    throw new IllegalArgumentException("invalid region parent");
                }
                current = parent;
            }
            for (Region other : input) {
                if (region == other || false == Objects.equals(region.parent, other.parent)) { continue; }
                if (region.full.start() < other.full.end() && other.full.start() < region.full.end()) {
                    throw new IllegalArgumentException("overlapping sibling regions");
                }
            }
        }
    }
    /** Stable scalar-order region inventory for document-wide validation. */
    public List<Region> regions() {
        return regions.values().stream().sorted((a,b) -> java.util.Arrays.compareUnsigned(
            a.id().getBytes(java.nio.charset.StandardCharsets.UTF_8), b.id().getBytes(java.nio.charset.StandardCharsets.UTF_8))).toList();
    }
    public DocumentSnapshot host() { return host; }
    /** Half-open ownership, plus the host EOF for a uniquely deepest explicitly open body. */
    public Region at(int point) {
        host.check(new Span(point, point));
        Region selected = null; int selectedDepth = -1; boolean ambiguous = false;
        for (Region region : regions.values()) {
            boolean owns = region.body.start() <= point && point < region.body.end()
                || openEnds.contains(region.id) && point == host.length() && point == region.body.end();
            if (!owns) { continue; }
            int candidateDepth = depth(region);
            if (candidateDepth > selectedDepth) { selected = region; selectedDepth = candidateDepth; ambiguous = false; }
            else if (candidateDepth == selectedDepth) { ambiguous = true; }
        }
        if (ambiguous) { throw new IllegalArgumentException("ambiguous cursor ownership"); }
        return selected;
    }
    private int depth(Region region) {
        int depth = 0;
        while (region.parent != null) { depth++; region = regions.get(region.parent); }
        return depth;
    }
    public Dispatch dispatch(String regionId, Operation operation, Map<Language, Provider> providers,
                             DocumentSnapshot currentHost) {
        if (false == host.equals(currentHost)) { throw new IllegalArgumentException("stale host snapshot"); }
        Region region = regions.get(regionId);
        if (region == null) { throw new IllegalArgumentException("unknown region"); }
        Provider provider = providers.get(region.language);
        if (provider == null) { return new Dispatch(State.UNAVAILABLE, List.of(), List.of()); }
        if (false == provider.capabilities().contains(operation)) {
            return new Dispatch(State.UNSUPPORTED, List.of(), List.of());
        }
        Response response = provider.invoke(region, operation);
        if (false == response.snapshot.equals(region.sourceMap.output())) { throw new IllegalArgumentException("stale provider snapshot"); }
        List<SegmentSourceMap.Mapping> diagnostics = new ArrayList<>();
        for (Span diagnostic : response.diagnostics) { diagnostics.addAll(region.sourceMap.diagnostics(diagnostic)); }
        List<Edit> edits = new ArrayList<>();
        for (Edit edit : response.edits) {
            Location origin = region.sourceMap.edit(edit.span);
            if (false == origin.snapshot().equals(host) || false == region.body.contains(origin.span())) {
                throw new IllegalArgumentException("edit outside body");
            }
            edits.add(new Edit(origin.span(), edit.replacement));
        }
        validateEdits(edits);
        return new Dispatch(response.state, diagnostics, edits);
    }
    /** Atomic edit application; all untouched bytes/code units remain exactly unchanged. */
    public DocumentSnapshot apply(DocumentSnapshot current, long nextVersion, List<Edit> edits) {
        if (false == host.equals(current) || nextVersion <= host.version()) {
            throw new IllegalArgumentException("stale or non-increasing version");
        }
        validateEdits(edits);
        List<Edit> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt((Edit edit) -> edit.span.start()).reversed());
        StringBuilder text = new StringBuilder(host.text());
        for (Edit edit : ordered) {
            host.check(edit.span);
            text.replace(host.utf16(edit.span.start()), host.utf16(edit.span.end()), edit.replacement);
        }
        return new DocumentSnapshot(host.uri(), nextVersion, text.toString());
    }
    public static void validateEdits(List<Edit> edits) {
        List<Edit> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(edit -> edit.span.start()));
        for (int index = 1; index < ordered.size(); index++) {
            Span previous = ordered.get(index - 1).span;
            Span next = ordered.get(index).span;
            if (next.start() < previous.end() || next.start() == previous.start()
                    || (next.start() == previous.end() && (next.length() == 0 || previous.length() == 0))) {
                throw new IllegalArgumentException("conflicting edits");
            }
        }
    }
}
