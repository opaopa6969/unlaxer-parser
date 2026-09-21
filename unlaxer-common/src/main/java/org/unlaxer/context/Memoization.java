package org.unlaxer.context;

/** Parse-local memoization policy. */
public enum Memoization {
  /** Preserve the historical behavior: do not cache parser results. */
  OFF,
  /**
   * Cache failures for generated rules proven free of context-dependent behavior; also replay
   * safe successes for exact classes directly implementing {@link SafeSuccessMemoizable}.
   */
  SAFE_FAILURES
}
