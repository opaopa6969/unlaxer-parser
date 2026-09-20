package org.unlaxer.context;

/** Parse-local memoization policy. */
public enum Memoization {
  /** Preserve the historical behavior: do not cache parser results. */
  OFF,
  /** Cache failures only for generated rules proven free of context-dependent behavior. */
  SAFE_FAILURES
}
