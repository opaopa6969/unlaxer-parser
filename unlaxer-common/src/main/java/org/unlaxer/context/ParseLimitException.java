package org.unlaxer.context;

/**
 * A resource limit aborted this parse session, rather than a syntax mismatch.
 * Close the context to restore its open transactions; use a fresh context for another parse.
 */
public final class ParseLimitException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    public static final String CODE = "ULX-PARSE-DEPTH-LIMIT";
    private final int limit;
    private final int offset;

    ParseLimitException(int limit, int offset) {
        super(CODE + ": active parser frames exceed " + limit + " at code-point offset " + offset);
        this.limit = limit;
        this.offset = offset;
    }

    public String code() { return CODE; }
    public int limit() { return limit; }
    public int offset() { return offset; }
}
