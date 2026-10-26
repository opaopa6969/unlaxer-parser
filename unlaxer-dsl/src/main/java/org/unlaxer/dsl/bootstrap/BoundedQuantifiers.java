package org.unlaxer.dsl.bootstrap;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;

/** Internal overflow transport without changing the published int-valued AST records. */
public final class BoundedQuantifiers {
    private BoundedQuantifiers() {}
    // Outside the grammar's nonnegative domain; never lowered to a runtime count.
    private static final int OVERFLOW = Integer.MIN_VALUE;
    private static final ReferenceQueue<BoundedRepeatElement> QUEUE = new ReferenceQueue<>();
    private static final Map<Key, UBNFSourceSnapshot.Span> ORIGINS = new HashMap<>();

    private static final class Key extends WeakReference<BoundedRepeatElement> {
        private final int hash;
        Key(BoundedRepeatElement value) {
            super(value, QUEUE);
            hash = System.identityHashCode(value);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Key key && get() != null && get() == key.get();
        }
    }

    static int bound(String digits) {
        // No BigInteger allocation or exception for arbitrarily long digit runs, including zeroes.
        int value = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(i) - '0';
            if (digit < 0 || digit > 9) throw new NumberFormatException("Invalid repeat bound: " + digits);
            if (value > (Integer.MAX_VALUE - digit) / 10) return OVERFLOW;
            value = value * 10 + digit;
        }
        return value;
    }

    public static boolean overflow(BoundedRepeatElement repeat) {
        return repeat.min() == OVERFLOW || repeat.max() == OVERFLOW;
    }

    private static void clean() {
        for (var key = QUEUE.poll(); key != null; key = QUEUE.poll()) ORIGINS.remove(key);
    }

    static synchronized void remember(BoundedRepeatElement repeat, UBNFSourceSnapshot.Span span) {
        clean();
        ORIGINS.put(new Key(repeat), span);
    }

    public static BoundedRepeatElement copy(BoundedRepeatElement target, BoundedRepeatElement source) {
        if (!overflow(source)) return target;
        synchronized (BoundedQuantifiers.class) {
            clean();
            var span = ORIGINS.get(new Key(source));
            Reference.reachabilityFence(source);
            if (span != null) ORIGINS.put(new Key(target), span);
        }
        return target;
    }

    public static synchronized String message(BoundedRepeatElement repeat) {
        clean();
        var span = ORIGINS.get(new Key(repeat));
        Reference.reachabilityFence(repeat);
        return "repetition bound exceeds 2147483647"
            + (span == null ? "" : " at CP " + span.start() + ".." + span.end());
    }

    /** Check all definitions, including unused rules and children of a zero-count repetition. */
    public static void requireNoOverflow(GrammarDecl grammar) {
        var pending = new ArrayDeque<UBNFAST>();
        for (int i = grammar.rules().size() - 1; i >= 0; i--) pending.push(grammar.rules().get(i).body());
        while (!pending.isEmpty()) {
            var node = pending.pop();
            if (node instanceof ChoiceBody choice) {
                for (int i = choice.alternatives().size() - 1; i >= 0; i--) pending.push(choice.alternatives().get(i));
            } else if (node instanceof SequenceBody sequence) {
                for (int i = sequence.elements().size() - 1; i >= 0; i--) pending.push(sequence.elements().get(i));
            } else if (node instanceof AnnotatedElement annotated) pending.push(annotated.element());
            else if (node instanceof BoundedRepeatElement repeat) {
                if (overflow(repeat)) throw new IllegalArgumentException("E-BOUNDED-OVERFLOW: " + message(repeat));
                pending.push(repeat.body());
            } else if (node instanceof TransparentElement group) pending.push(group.body());
            else if (node instanceof OptionalElement optional) pending.push(optional.body());
            else if (node instanceof RepeatElement repeat) pending.push(repeat.body());
            else if (node instanceof OneOrMoreElement repeat) pending.push(repeat.body());
            else if (node instanceof SeparatedElement separated) {
                pending.push(separated.separator());
                pending.push(separated.element());
            }
        }
    }
}
