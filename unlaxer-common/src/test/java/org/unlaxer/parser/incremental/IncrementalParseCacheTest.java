package org.unlaxer.parser.incremental;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.unlaxer.Source;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.parser.posix.DotParser;

/**
 * Tests for {@link IncrementalParseCache}.
 *
 * Validates chunk splitting, cache hit/miss behavior, statistics tracking,
 * and the large-document scenario (470 match cases).
 */
public class IncrementalParseCacheTest {

    private IncrementalParseCache cache;

    @Before
    public void setUp() {
        cache = new IncrementalParseCache();
    }

    // --- splitIntoChunks tests ---

    @Test
    public void splitIntoChunks_withCommaDelimiter() {
        List<String> chunks = cache.splitIntoChunks(
            "$pref == \"Tokyo\", result1, $pref == \"Osaka\", result2", ",");

        assertEquals(4, chunks.size());
        assertEquals("$pref == \"Tokyo\",", chunks.get(0));
        assertEquals(" result1,", chunks.get(1));
        assertEquals(" $pref == \"Osaka\",", chunks.get(2));
        assertEquals(" result2", chunks.get(3));
    }

    @Test
    public void splitIntoChunks_withSemicolonDelimiter() {
        List<String> chunks = cache.splitIntoChunks(
            "var x = 1; var y = 2; var z = 3", ";");

        assertEquals(3, chunks.size());
        assertEquals("var x = 1;", chunks.get(0));
        assertEquals(" var y = 2;", chunks.get(1));
        assertEquals(" var z = 3", chunks.get(2));
    }

    @Test
    public void splitIntoChunks_emptySource() {
        List<String> chunks = cache.splitIntoChunks("", ",");
        assertTrue(chunks.isEmpty());
    }

    @Test
    public void splitIntoChunks_nullSource() {
        List<String> chunks = cache.splitIntoChunks(null, ",");
        assertTrue(chunks.isEmpty());
    }

    @Test
    public void splitIntoChunks_noDelimiters() {
        List<String> chunks = cache.splitIntoChunks("hello world");
        assertEquals(1, chunks.size());
        assertEquals("hello world", chunks.get(0));
    }

    @Test
    public void splitIntoChunks_sourceWithTrailingDelimiter() {
        List<String> chunks = cache.splitIntoChunks("a,b,c,", ",");
        assertEquals(3, chunks.size());
        assertEquals("a,", chunks.get(0));
        assertEquals("b,", chunks.get(1));
        assertEquals("c,", chunks.get(2));
    }

    // --- Cache hit/miss tests ---

    @Test
    public void getCached_returnsNullForUnknownChunk() {
        Token result = cache.getCached("unknown chunk text");
        assertNull(result);
    }

    @Test
    public void getCached_returnsCachedTokenForSameText() {
        Token token = createDummyToken();
        String chunkText = "$pref == \"Tokyo\"";

        cache.put(chunkText, token, 0);
        Token cached = cache.getCached(chunkText);

        assertNotNull(cached);
        assertSame(token, cached);
    }

    @Test
    public void getCached_returnsNullForModifiedText() {
        Token token = createDummyToken();
        cache.put("$pref == \"Tokyo\"", token, 0);

        Token cached = cache.getCached("$pref == \"Osaka\"");
        assertNull(cached);
    }

    @Test
    public void getCached_multipleChunksCachedIndependently() {
        Token token1 = createDummyToken();
        Token token2 = createDummyToken();

        cache.put("chunk1", token1, 0);
        cache.put("chunk2", token2, 5);

        assertSame(token1, cache.getCached("chunk1"));
        assertSame(token2, cache.getCached("chunk2"));
    }

    // --- Cache stats tests ---

    @Test
    public void stats_initiallyEmpty() {
        IncrementalParseCache.CacheStats stats = cache.stats();
        assertEquals(0, stats.entries());
        assertEquals(0, stats.hits());
        assertEquals(0, stats.misses());
        assertEquals(0.0, stats.hitRate(), 0.001);
    }

    @Test
    public void stats_countsHitsAndMisses() {
        Token token = createDummyToken();
        cache.put("chunk1", token, 0);

        cache.getCached("chunk1");  // hit
        cache.getCached("chunk1");  // hit
        cache.getCached("unknown"); // miss

        IncrementalParseCache.CacheStats stats = cache.stats();
        assertEquals(1, stats.entries());
        assertEquals(2, stats.hits());
        assertEquals(1, stats.misses());
        assertEquals(2.0 / 3.0, stats.hitRate(), 0.001);
    }

    // --- Clear tests ---

    @Test
    public void clear_removesAllEntries() {
        cache.put("chunk1", createDummyToken(), 0);
        cache.put("chunk2", createDummyToken(), 5);
        assertEquals(2, cache.size());

        cache.clear();

        assertEquals(0, cache.size());
        assertNull(cache.getCached("chunk1"));
        assertNull(cache.getCached("chunk2"));
    }

    @Test
    public void clear_resetsStatistics() {
        cache.put("chunk1", createDummyToken(), 0);
        cache.getCached("chunk1"); // hit
        cache.getCached("unknown"); // miss

        cache.clear();

        IncrementalParseCache.CacheStats stats = cache.stats();
        assertEquals(0, stats.hits());
        assertEquals(0, stats.misses());
    }

    // --- Large document simulation ---

    @Test
    public void largeDocument_470MatchCases() {
        // Simulate: match{ 47 prefectures x 10 categories = 470 cases }
        // Each case is separated by semicolon: "condition -> result;"
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 470; i++) {
            sb.append("$pref == \"pref_").append(i).append("\" -> result_").append(i);
            if (i < 469) {
                sb.append(";");
            }
        }
        String source = sb.toString();

        // Split and cache all chunks
        List<String> chunks = cache.splitIntoChunks(source, ";");
        assertEquals(470, chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            cache.put(chunks.get(i), createDummyToken(), i);
        }
        assertEquals(470, cache.size());

        // Simulate editing case 235: all other cases should be cache hits
        int changedIndex = 235;
        int cacheHits = 0;
        int cacheMisses = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String chunkText;
            if (i == changedIndex) {
                // Modified chunk
                chunkText = "$pref == \"pref_235_MODIFIED\" -> result_235_MODIFIED;";
            } else {
                chunkText = chunks.get(i);
            }
            Token cached = cache.getCached(chunkText);
            if (cached != null) {
                cacheHits++;
            } else {
                cacheMisses++;
            }
        }

        assertEquals(469, cacheHits);
        assertEquals(1, cacheMisses);

        IncrementalParseCache.CacheStats stats = cache.stats();
        assertTrue("Hit rate should be > 99%", stats.hitRate() > 0.99);
    }

    // --- Hash determinism ---

    @Test
    public void hash_sameInputProducesSameHash() {
        String hash1 = cache.hash("test input");
        String hash2 = cache.hash("test input");
        assertEquals(hash1, hash2);
    }

    @Test
    public void hash_differentInputProducesDifferentHash() {
        String hash1 = cache.hash("test input A");
        String hash2 = cache.hash("test input B");
        assertTrue("Different inputs should have different hashes",
            !hash1.equals(hash2));
    }

    // --- Helper ---

    private Token createDummyToken() {
        return new Token(TokenKind.matchOnly, Source.EMPTY, new DotParser());
    }
}
