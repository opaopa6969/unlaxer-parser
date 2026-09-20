package org.unlaxer.context;

/**
 * Transactional state that installs rollback snapshots lazily, immediately
 * before its first mutation in each open transaction.
 *
 * <p>Implementations must call
 * {@link ParseContext#beforeTransactionalStateMutation(TransactionalState)}
 * before every mutation. Ordinary {@link TransactionalState} implementations
 * remain eager for compatibility.
 */
public interface MutationAwareTransactionalState extends TransactionalState {
}
