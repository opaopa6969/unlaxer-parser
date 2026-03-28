package org.unlaxer.parser.incremental;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.unlaxer.Token;

/**
 * Caches parse results by chunk hash for incremental re-parsing.
 *
 * When a document changes, only the modified chunks need re-parsing.
 * Other chunks reuse their cached parse results.
 *
 * <p>Designed for LSP-only optimization. Production uses compiled bytecode
 * and is not affected by this cache.</p>
 *
 * <p>Chunk boundaries are defined by delimiters:
 * <ul>
 *   <li>comma ({@code ,}) for match cases</li>
 *   <li>semicolon ({@code ;}) for var declarations</li>
 * </ul>
 * </p>
 */
public class IncrementalParseCache {

    private final Map<String, CachedChunk> cache = new LinkedHashMap<>();
    private int hits;
    private int misses;

    /**
     * A cached chunk holding the hash, original text, parsed token, and start line.
     */
    public static final class CachedChunk {
        private final String hash;
        private final String text;
        private final Token token;
        private final int startLine;

        public CachedChunk(String hash, String text, Token token, int startLine) {
            this.hash = hash;
            this.text = text;
            this.token = token;
            this.startLine = startLine;
        }

        public String hash() { return hash; }
        public String text() { return text; }
        public Token token() { return token; }
        public int startLine() { return startLine; }
    }

    /**
     * Split source into chunks at the given delimiters.
     * The delimiter is kept at the end of each chunk (except possibly the last chunk).
     *
     * <p>This is a simple text-level split. For production use with nested expressions
     * or string literals, a token-level split should be used instead (see DGE gap G4, G5).</p>
     *
     * @param source the source text to split
     * @param delimiters the delimiter characters to split on (e.g. ",", ";")
     * @return list of chunks, each ending with the delimiter except possibly the last
     */
    public List<String> splitIntoChunks(String source, String... delimiters) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        if (delimiters == null || delimiters.length == 0) {
            return List.of(source);
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            current.append(ch);

            if (isDelimiter(ch, delimiters)) {
                chunks.add(current.toString());
                current.setLength(0);
            }
        }

        // Add remaining text as final chunk (if any)
        if (current.length() > 0) {
            chunks.add(current.toString());
        }

        return chunks;
    }

    private boolean isDelimiter(char ch, String[] delimiters) {
        for (String delim : delimiters) {
            if (delim.length() == 1 && delim.charAt(0) == ch) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get cached token for a chunk, or null if not cached / changed.
     * Increments hit or miss counters accordingly.
     *
     * @param chunkText the text of the chunk to look up
     * @return the cached Token, or null if not found
     */
    public Token getCached(String chunkText) {
        String h = hash(chunkText);
        CachedChunk cached = cache.get(h);
        if (cached != null) {
            hits++;
            return cached.token();
        } else {
            misses++;
            return null;
        }
    }

    /**
     * Store a parsed result for a chunk.
     *
     * @param chunkText the text of the chunk
     * @param token the parsed Token subtree
     * @param startLine the starting line number of this chunk in the document
     */
    public void put(String chunkText, Token token, int startLine) {
        String h = hash(chunkText);
        cache.put(h, new CachedChunk(h, chunkText, token, startLine));
    }

    /**
     * Get cache statistics.
     *
     * @return current cache stats
     */
    public CacheStats stats() {
        return new CacheStats(cache.size(), hits, misses);
    }

    /**
     * Cache statistics: entries count, hits, and misses.
     */
    public static final class CacheStats {
        private final int entries;
        private final int hits;
        private final int misses;

        public CacheStats(int entries, int hits, int misses) {
            this.entries = entries;
            this.hits = hits;
            this.misses = misses;
        }

        public int entries() { return entries; }
        public int hits() { return hits; }
        public int misses() { return misses; }

        public double hitRate() {
            int total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }
    }

    /**
     * Compute a SHA-256 hex hash of the given text.
     */
    String hash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Clear all cached entries and reset statistics.
     */
    public void clear() {
        cache.clear();
        hits = 0;
        misses = 0;
    }

    /**
     * Return the number of cached entries.
     */
    public int size() {
        return cache.size();
    }
}
