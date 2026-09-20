package org.unlaxer.context;

/** Immutable opt-in transaction counters for one parse context. */
public record TransactionMetrics(
        long opened,
        long committed,
        long rolledBack,
        long nonemptyPayloadSnapshots,
        long emptyPayloadCheckpoints,
        long copyOnWriteDeepCopies) {
}
