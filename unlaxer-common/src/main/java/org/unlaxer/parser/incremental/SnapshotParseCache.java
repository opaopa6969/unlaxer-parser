package org.unlaxer.parser.incremental;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.unlaxer.source.DocumentSnapshot;

/** Bounded whole-snapshot result reuse, never partial parsing or offset rebasing.
 * Values must be immutable or copied deeply by the supplied copier. Revision strings must
 * change whenever grammar/options, profile, or externally observed parser state changes.
 * Null results and computations that throw are not cached. All operations are serialized.
 */
public final class SnapshotParseCache<T> {
    public record Revisions(String grammar, String profile, String state) {
        public Revisions { Objects.requireNonNull(grammar); Objects.requireNonNull(profile); Objects.requireNonNull(state); }
    }
    public record Stats(int entries, long sourceUtf16Units, long hits, long misses) {}
    private record Key(DocumentSnapshot snapshot, Revisions revisions) {}
    private final int maxEntries;
    private final long maxSourceUtf16Units;
    private final UnaryOperator<T> copier;
    private final LinkedHashMap<Key, T> entries = new LinkedHashMap<>();
    private long sourceUnits, hits, misses;

    public SnapshotParseCache(int maxEntries, long maxSourceUtf16Units, UnaryOperator<T> copier) {
        if (maxEntries < 0 || maxSourceUtf16Units < 0) throw new IllegalArgumentException("negative cache limit");
        this.maxEntries = maxEntries;
        this.maxSourceUtf16Units = maxSourceUtf16Units;
        this.copier = Objects.requireNonNull(copier);
    }
    public synchronized T get(DocumentSnapshot snapshot, Revisions revisions) {
        Key key = key(snapshot, revisions);
        T value = entries.get(key);
        if (value == null) { if (misses != Long.MAX_VALUE) misses++; return null; }
        T copy = Objects.requireNonNull(copier.apply(value));
        entries.remove(key);
        entries.put(key, value);
        if (hits != Long.MAX_VALUE) hits++;
        return copy;
    }
    public synchronized void put(DocumentSnapshot snapshot, Revisions revisions, T value) {
        Key key = key(snapshot, revisions);
        Objects.requireNonNull(value);
        long units = snapshot.text().length();
        if (maxEntries == 0 || units > maxSourceUtf16Units) return;
        T owned = Objects.requireNonNull(copier.apply(value)); // copy before mutating retained state
        if (entries.remove(key) != null) sourceUnits -= units;
        while (!entries.isEmpty() && (entries.size() >= maxEntries || sourceUnits > maxSourceUtf16Units - units)) {
            var first = entries.keySet().iterator().next();
            entries.remove(first);
            sourceUnits -= first.snapshot().text().length();
        }
        entries.put(key, owned);
        sourceUnits += units;
    }
    public synchronized T getOrCompute(DocumentSnapshot snapshot, Revisions revisions, Supplier<T> compute) {
        T cached = get(snapshot, revisions);
        if (cached != null) return cached;
        T value = Objects.requireNonNull(compute.get());
        put(snapshot, revisions, value);
        return value;
    }
    public synchronized void invalidate(String uri) {
        Objects.requireNonNull(uri);
        entries.keySet().removeIf(key -> {
            if (!key.snapshot().uri().equals(uri)) return false;
            sourceUnits -= key.snapshot().text().length();
            return true;
        });
    }
    /** Drop retained values without erasing observation counters. */
    public synchronized void invalidateAll() { entries.clear(); sourceUnits = 0; }
    public synchronized void clear() { invalidateAll(); hits = 0; misses = 0; }
    public synchronized Stats stats() { return new Stats(entries.size(), sourceUnits, hits, misses); }
    private Key key(DocumentSnapshot snapshot, Revisions revisions) {
        return new Key(Objects.requireNonNull(snapshot), Objects.requireNonNull(revisions));
    }
}
