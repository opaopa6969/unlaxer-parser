package org.unlaxer.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.OptionalInt;
import org.unlaxer.source.DocumentSnapshot.Span;

/** A total, ordered map from an output snapshot to zero or more original snapshots. */
public final class SegmentSourceMap {
    public enum Kind { COPY, TRANSFORMED, GENERATED }
    public record Location(DocumentSnapshot snapshot, Span span) {
        public Location { Objects.requireNonNull(snapshot); snapshot.check(span); }
    }
    /** GENERATED origins are optional call-site anchors, never editable source. */
    public record Segment(Span output, Kind kind, Location origin) {
        public Segment { Objects.requireNonNull(output); Objects.requireNonNull(kind); }
    }
    public record Mapping(Location location, boolean exact) {}
    private final DocumentSnapshot output;
    private final List<Segment> segments;
    private final List<SegmentSourceMap> parents;

    public SegmentSourceMap(DocumentSnapshot output, List<Segment> segments) {
        this(output, segments, List.of());
    }
    private SegmentSourceMap(DocumentSnapshot output, List<Segment> segments, List<SegmentSourceMap> parents) {
        this.output = Objects.requireNonNull(output);
        this.segments = List.copyOf(segments);
        this.parents = List.copyOf(parents);
        int cursor = 0;
        for (Segment segment : segments) {
            output.check(segment.output);
            if (segment.output.start() != cursor || segment.output.length() == 0) {
                throw new IllegalArgumentException("segments must partition output");
            }
            if (segment.kind != Kind.GENERATED && segment.origin == null) {
                throw new IllegalArgumentException("missing origin");
            }
            if (segment.kind == Kind.COPY && false == output.slice(segment.output).equals(
                    segment.origin.snapshot.slice(segment.origin.span))) {
                throw new IllegalArgumentException("COPY must preserve text");
            }
            cursor = segment.output.end();
        }
        if (cursor != output.length()) { throw new IllegalArgumentException("incomplete map"); }
    }
    public DocumentSnapshot output() { return output; }
    /** Compose through an exact snapshot identity; unrelated maps are rejected. */
    public SegmentSourceMap through(SegmentSourceMap parent) {
        if (false == reachable(parent.output)) { throw new IllegalArgumentException("unrelated map"); }
        List<SegmentSourceMap> next = new ArrayList<>(parents);
        next.add(parent);
        return new SegmentSourceMap(output, segments, next);
    }
    private boolean reachable(DocumentSnapshot snapshot) {
        for (Mapping mapping : diagnostics(new Span(0, output.length()))) {
            if (mapping.location.snapshot.equals(snapshot)) { return true; }
        }
        return false;
    }
    public List<Mapping> diagnostics(Span span) {
        List<Mapping> mapped = direct(span, false);
        for (SegmentSourceMap parent : parents) {
            List<Mapping> next = new ArrayList<>();
            for (Mapping mapping : mapped) {
                if (mapping.location.snapshot.equals(parent.output)) {
                    for (Mapping origin : parent.diagnostics(mapping.location.span)) {
                        next.add(new Mapping(origin.location, mapping.exact && origin.exact));
                    }
                } else { next.add(mapping); }
            }
            mapped = next;
        }
        return List.copyOf(mapped);
    }
    public Location edit(Span span) {
        List<Mapping> mapped = direct(span, true);
        if (mapped.size() != 1 || false == mapped.get(0).exact) {
            throw new IllegalArgumentException("edit has no unique inverse");
        }
        Location location = mapped.get(0).location;
        for (SegmentSourceMap parent : parents) {
            if (location.snapshot.equals(parent.output)) { location = parent.edit(location.span); }
        }
        int aliases = 0;
        for (Mapping candidate : diagnostics(new Span(0, output.length()))) {
            if (false == candidate.location.snapshot.equals(location.snapshot)) { continue; }
            Span candidateSpan = candidate.location.span;
            boolean overlaps = location.span.length() == 0 ? candidateSpan.contains(location.span)
                    : candidateSpan.start() < location.span.end() && location.span.start() < candidateSpan.end();
            if (overlaps && ++aliases > 1) { throw new IllegalArgumentException("duplicated composed origin"); }
        }
        return location;
    }
    private record ExactLink(Span output, Location origin) {}
    private List<ExactLink> exactLinks() {
        List<ExactLink> links = new ArrayList<>();
        for (Segment segment : segments) {
            if (segment.kind == Kind.COPY) { links.add(new ExactLink(segment.output, segment.origin)); }
        }
        for (SegmentSourceMap parent : parents) {
            List<ExactLink> next = new ArrayList<>();
            List<ExactLink> parentLinks = parent.exactLinks();
            for (ExactLink link : links) {
                if (false == link.origin.snapshot.equals(parent.output)) { next.add(link); continue; }
                for (ExactLink parentLink : parentLinks) {
                    int start = Math.max(link.origin.span.start(), parentLink.output.start());
                    int end = Math.min(link.origin.span.end(), parentLink.output.end());
                    if (start >= end) { continue; }
                    int mapped = parentLink.origin.span.start() + start - parentLink.output.start();
                    next.add(new ExactLink(new Span(link.output.start() + start - link.origin.span.start(),
                            link.output.start() + end - link.origin.span.start()), new Location(parentLink.origin.snapshot,
                            new Span(mapped, mapped + end - start))));
                }
            }
            links = next;
        }
        return links;
    }
    /** Unique original cursor to virtual cursor. Inexact or ambiguous boundaries return empty. */
    public OptionalInt cursor(Location original) {
        if (original.span.length() != 0) { throw new IllegalArgumentException("cursor must be a point"); }
        List<ExactLink> links = exactLinks();
        Set<Integer> candidates = new HashSet<>();
        for (ExactLink link : links) {
            if (link.origin.snapshot.equals(original.snapshot) && link.origin.span.contains(original.span)) {
                candidates.add(link.output.start() + original.span.start() - link.origin.span.start());
            }
        }
        if (candidates.size() != 1) { return OptionalInt.empty(); }
        int point = candidates.iterator().next();
        for (ExactLink link : links) {
            if (link.output.contains(new Span(point, point))) {
                int mapped = link.origin.span.start() + point - link.output.start();
                if (false == new Location(link.origin.snapshot, new Span(mapped, mapped)).equals(original)) {
                    return OptionalInt.empty();
                }
            }
        }
        return OptionalInt.of(point);
    }
    private List<Mapping> direct(Span span, boolean editing) {
        output.check(span);
        List<Mapping> result = new ArrayList<>();
        for (Segment segment : segments) {
            boolean intersects = span.length() == 0
                    ? segment.output.contains(span)
                    : segment.output.start() < span.end() && span.start() < segment.output.end();
            if (false == intersects) { continue; }
            if (segment.origin == null) {
                if (editing) { throw new IllegalArgumentException("generated source"); }
                continue;
            }
            boolean exact = segment.kind == Kind.COPY;
            Span origin = segment.origin.span;
            if (exact) {
                int start = Math.max(span.start(), segment.output.start()) - segment.output.start() + origin.start();
                int end = Math.min(span.end(), segment.output.end()) - segment.output.start() + origin.start();
                origin = new Span(start, end);
            }
            if (editing && exact) {
                for (Segment other : segments) {
                    if (other == segment || other.origin == null || false == other.origin.snapshot.equals(segment.origin.snapshot)) { continue; }
                    Span otherSpan = other.origin.span;
                    boolean duplicates = origin.length() == 0 ? otherSpan.contains(origin)
                            : otherSpan.start() < origin.end() && origin.start() < otherSpan.end();
                    if (duplicates) { throw new IllegalArgumentException("duplicated origin"); }
                }
            }
            result.add(new Mapping(new Location(segment.origin.snapshot, origin), exact));
        }
        return result;
    }
}
