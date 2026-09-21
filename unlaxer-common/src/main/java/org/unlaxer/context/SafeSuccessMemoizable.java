package org.unlaxer.context;

/**
 * Stronger generated safety proof: the complete dependency closure is stateless on success,
 * has no transaction listeners, and produces ordinary copyable tokens. In addition to safe
 * failures, {@link Memoization#SAFE_FAILURES} may replay its successful token trees and cursors.
 * Only a class directly declaring this interface is eligible; subclasses must be proven again.
 */
public interface SafeSuccessMemoizable extends SafeFailureMemoizable {}
