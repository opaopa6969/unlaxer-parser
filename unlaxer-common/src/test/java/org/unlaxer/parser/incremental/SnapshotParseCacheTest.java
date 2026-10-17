package org.unlaxer.parser.incremental;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.Test;
import org.unlaxer.source.DocumentSnapshot;

public class SnapshotParseCacheTest {
    private static final SnapshotParseCache.Revisions REV = new SnapshotParseCache.Revisions("g", "p", "s");
    private static DocumentSnapshot snapshot(String uri, long version, String source) { return new DocumentSnapshot(uri, version, source); }
    @Test public void sharedQuoteAwareChunkOracle() throws Exception {
        var cache = new IncrementalParseCache();
        for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("../spec-corpus/incremental-cache/chunks.tsv"))) {
            if (line.startsWith("#")) continue;
            String[] f = line.split("\\t", -1);
            String source = f[2].equals("~") ? "" : f[2].replace("\\r", "\r").replace("\\n", "\n");
            String[] delimiters = f[1].equals("~") ? new String[0] : f[1].codePoints().mapToObj(cp -> new String(Character.toChars(cp))).toArray(String[]::new);
            var expected = f[3].equals("~") ? List.<String>of() : java.util.Arrays.stream(f[3].split("\\|", -1)).map(value -> value.replace("\\r", "\r").replace("\\n", "\n")).toList();
            assertEquals(f[0], expected, cache.splitIntoChunks(source, delimiters));
        }
    }
    @Test public void ownershipFailureAndLruBounds() {
        var cache = new SnapshotParseCache<List<String>>(2, 4, ArrayList::new);
        var first = snapshot("a", 1, "😀");
        var value = new ArrayList<>(List.of("old"));
        cache.put(first, REV, value); value.clear();
        var hit = cache.get(first, REV); hit.clear();
        assertEquals(List.of("old"), cache.get(first, REV));
        cache.put(snapshot("b", 1, "ab"), REV, List.of("b"));
        assertEquals(4, cache.stats().sourceUtf16Units());
        cache.get(first, REV);
        cache.put(snapshot("c", 1, "x"), REV, List.of("c"));
        assertNull(cache.get(snapshot("b", 1, "ab"), REV));
        assertThrows(IllegalStateException.class, () -> cache.getOrCompute(snapshot("d", 1, "y"), REV, () -> { throw new IllegalStateException(); }));
        assertEquals(2, cache.stats().entries());
        assertEquals(List.of("old"), cache.get(first, REV));
        cache.put(snapshot("large", 1, "12345"), REV, List.of("large"));
        assertEquals(2, cache.stats().entries());
        cache.invalidate("a"); assertEquals(1, cache.stats().entries());
        cache.invalidateAll(); assertEquals(0, cache.stats().entries()); assertTrue(cache.stats().hits() > 0);
        cache.clear(); assertEquals(0, cache.stats().hits()); assertEquals(0, cache.stats().misses());
    }
    @Test public void everyIdentityComponentInvalidatesAndParallelSnapshotsRemainIndependent() throws Exception {
        var cache = new SnapshotParseCache<String>(8, 100, value -> value);
        var original = snapshot("a", 1, "x"); cache.put(original, REV, "original");
        for (var changed : List.of(snapshot("b",1,"x"), snapshot("a",2,"x"), snapshot("a",1,"y"))) assertNull(cache.get(changed, REV));
        for (var rev : List.of(new SnapshotParseCache.Revisions("g2","p","s"), new SnapshotParseCache.Revisions("g","p2","s"), new SnapshotParseCache.Revisions("g","p","s2"))) assertNull(cache.get(original, rev));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var old = pool.submit(() -> cache.getOrCompute(original, REV, () -> { throw new AssertionError("miss"); }));
            var newer = pool.submit(() -> cache.getOrCompute(snapshot("a", 2, "y"), REV, () -> "new"));
            assertEquals("original", old.get()); assertEquals("new", newer.get());
        } finally { pool.shutdownNow(); }
        assertEquals("original", cache.get(original, REV));
    }
    @Test public void copierFailureDoesNotEvictAndDisabledCacheDoesNotRetain() {
        var cache = new SnapshotParseCache<String>(1, 10, value -> { if (value.equals("bad")) throw new IllegalArgumentException(); return value; });
        var snapshot = snapshot("a",1,"x"); cache.put(snapshot,REV,"good");
        assertThrows(IllegalArgumentException.class, () -> cache.put(snapshot("b",1,"y"), REV, "bad"));
        assertEquals("good", cache.get(snapshot,REV));
        var disabled = new SnapshotParseCache<String>(0,0,value -> value);
        assertEquals("ok", disabled.getOrCompute(snapshot,REV,() -> "ok")); assertEquals(0,disabled.stats().entries());
        assertThrows(IllegalArgumentException.class, () -> new SnapshotParseCache<String>(-1,0,value -> value));
    }
    @Test public void invalidCoordinateOrSourceDoesNotReplaceLastSnapshot() {
        var cache = new SnapshotParseCache<String>(4,100,value -> value);
        var old = snapshot("a",1,"😀\r\nx"); cache.put(old,REV,"last");
        assertThrows(IllegalArgumentException.class, () -> old.fromUtf16(1));
        assertThrows(IllegalArgumentException.class, () -> old.fromLsp(new DocumentSnapshot.Position(0,3)));
        assertThrows(IllegalArgumentException.class, () -> snapshot("a",2,"\ud800"));
        assertEquals("last",cache.get(old,REV));assertEquals(1,cache.stats().entries());
    }
    @Test public void legacyChunksAreBoundedAndUseExactTextDespiteUtf8ReplacementCollision() {
        var cache = new IncrementalParseCache(2,4);
        cache.put("a",null,0); cache.put("😀",null,0); cache.getCached("a"); cache.put("b",null,0);
        assertTrue(cache.isCached("a")); assertFalse(cache.isCached("😀")); assertTrue(cache.isCached("b"));
        cache.put("oversize",null,0); assertEquals(2,cache.size());
        cache.clear(); cache.put("\ud800",null,0);
        assertFalse(cache.isCached("\ud801"));
    }
}
