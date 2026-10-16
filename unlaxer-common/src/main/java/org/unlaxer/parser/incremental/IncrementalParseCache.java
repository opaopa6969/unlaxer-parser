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
 * Bounded caller-managed chunk memo. This class does not implement partial parsing,
 * invalidation ranges, CST splicing, or source-position rebasing. A caller may reuse a
 * chunk only when its grammar/state and coordinates are independently valid.
 * Tokens are borrowed mutable objects for compatibility; use SnapshotParseCache with
 * an appropriate copier for isolated document results. Delimiters respect quoted strings,
 * but do not understand language-specific comments or nesting.
 */
public class IncrementalParseCache {

    private final Map<String, CachedChunk> cache = new LinkedHashMap<>();
    private final int maxEntries;
    private final long maxSourceUtf16Units;
    private long sourceUnits;

    public IncrementalParseCache() { this(1024, 1_048_576); }
    public IncrementalParseCache(int maxEntries, long maxSourceUtf16Units) {
        if (maxEntries < 0 || maxSourceUtf16Units < 0) throw new IllegalArgumentException("negative cache limit");
        this.maxEntries = maxEntries;
        this.maxSourceUtf16Units = maxSourceUtf16Units;
    }
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
     * <p>String literals ({@code '...'} and {@code "..."}) are respected:
     * delimiters inside quotes are not treated as chunk boundaries.
     * Backslash escapes ({@code \'}, {@code \"}) inside literals are handled correctly.</p>
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
        char quoteChar = 0;

        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            current.append(ch);

            if (quoteChar != 0) {
                if (ch == '\\' && i + 1 < source.length()) {
                    current.append(source.charAt(++i));
                } else if (ch == quoteChar) {
                    quoteChar = 0;
                }
            } else if (ch == '\'' || ch == '"') {
                quoteChar = ch;
            } else if (isDelimiter(ch, delimiters)) {
                chunks.add(current.toString());
                current.setLength(0);
            }
        }

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
    public synchronized Token getCached(String chunkText) {
        CachedChunk cached = cache.get(chunkText);
        if (cached != null) {
            if (hits != Integer.MAX_VALUE) hits++;
            cache.remove(chunkText);
            cache.put(chunkText, cached);
            return cached.token();
        } else {
            if (misses != Integer.MAX_VALUE) misses++;
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
    public synchronized void put(String chunkText, Token token, int startLine) {
        String h = hash(chunkText);
        long units = chunkText.length();
        if (maxEntries == 0 || units > maxSourceUtf16Units) return;
        if (cache.remove(chunkText) != null) sourceUnits -= units;
        while (!cache.isEmpty() && (cache.size() >= maxEntries || sourceUnits > maxSourceUtf16Units - units)) {
            String first = cache.keySet().iterator().next();
            cache.remove(first);
            sourceUnits -= first.length();
        }
        cache.put(chunkText, new CachedChunk(h, chunkText, token, startLine));
        sourceUnits += units;
    }

    /**
     * Get cache statistics.
     *
     * @return current cache stats
     */
    public synchronized CacheStats stats() {
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
            long total = (long) hits + misses;
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
     * Return true if the given chunk is present in the cache (regardless of whether its token is null).
     *
     * @param chunkText the text of the chunk to check
     * @return true if the chunk has been cached
     */
    public synchronized boolean isCached(String chunkText) {
        java.util.Objects.requireNonNull(chunkText);
        return cache.containsKey(chunkText);
    }

    /**
     * Clear all cached entries and reset statistics.
     */
    public synchronized void clear() {
        cache.clear();
        sourceUnits = 0;
        hits = 0;
        misses = 0;
    }

    /**
     * Return the number of cached entries.
     */
    public synchronized int size() {
        return cache.size();
    }
}
