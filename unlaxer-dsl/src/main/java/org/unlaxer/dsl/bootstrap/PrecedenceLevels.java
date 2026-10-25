package org.unlaxer.dsl.bootstrap;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.PrecedenceAnnotation;

/** Overflow transport preserving the published int-valued precedence AST. */
public final class PrecedenceLevels {
    public static final String CODE = "E-PRECEDENCE-OVERFLOW";
    private PrecedenceLevels() {}
    private static final ReferenceQueue<PrecedenceAnnotation> QUEUE = new ReferenceQueue<>();
    private static final Map<Key, UBNFSourceSnapshot.Span> ORIGINS = new HashMap<>();

    // Equal levels in distinct parses/annotations must retain their own source locations.
    private static final class Key extends WeakReference<PrecedenceAnnotation> {
        private final int hash;
        Key(PrecedenceAnnotation value) {
            super(value, QUEUE);
            hash = System.identityHashCode(value);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Key key && get() != null && get() == key.get();
        }
    }

    public static boolean overflow(PrecedenceAnnotation annotation) {
        return annotation.level() == Integer.MIN_VALUE;
    }

    private static void clean() {
        for (var key = QUEUE.poll(); key != null; key = QUEUE.poll()) ORIGINS.remove(key);
    }

    static synchronized void remember(PrecedenceAnnotation annotation, UBNFSourceSnapshot.Span span) {
        clean();
        ORIGINS.put(new Key(annotation), span);
    }

    public static synchronized String message(PrecedenceAnnotation annotation) {
        clean();
        var span = ORIGINS.get(new Key(annotation));
        Reference.reachabilityFence(annotation);
        return "precedence level exceeds 2147483647"
            + (span == null ? "" : " at CP " + span.start() + ".." + span.end());
    }

    /** Includes unused rules; invalid levels must never reach generated metadata. */
    public static void requireNoOverflow(GrammarDecl grammar) {
        for (var rule : grammar.rules()) {
            for (var annotation : rule.annotations()) {
                if (annotation instanceof PrecedenceAnnotation level && overflow(level)) {
                    throw new IllegalArgumentException(CODE + ": " + message(level));
                }
            }
        }
    }
}
