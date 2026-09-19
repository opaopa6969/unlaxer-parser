package org.unlaxer.context;

/**
 * Explicitly owned parser state that participates in context transactions.
 * Arbitrary objects in the context's maps are not copied or rolled back.
 */
@FunctionalInterface
public interface TransactionalState {
    /**
     * Capture the current state and return an action restoring that snapshot.
     * Neither this method nor the restore action may throw or change transaction
     * nesting. Snapshots must be independent of subsequent state mutations.
     * Restore order across owners is unspecified; owners must not depend on
     * another owner's restore action. A throwing checkpoint/restore violates
     * this contract, and its context must be discarded.
     */
    Runnable checkpoint();
}
