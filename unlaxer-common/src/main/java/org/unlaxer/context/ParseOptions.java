package org.unlaxer.context;

import java.util.Objects;

/** Immutable options for one parse session. Defaults to automatic diagnostics and no memoization. */
public final class ParseOptions {
  /** Syntax-failure recording policy, independent of memoization and semantic state. */
  public enum Diagnostics {
    /**
     * Uses deferred diagnostics at retrying entry points for a declared safe parser tree.
     * Direct ParseContext operations resolve this to DETAILED because they never retry.
     */
    AUTO,
    /** Records speculative failures even when the whole parse succeeds. */
    DETAILED,
    /**
     * Skips syntax diagnostics on the first pass. Generated full-input entry points retry
     * failures with DETAILED in a fresh context; direct ParseContext operations never retry.
     * Only use with parsers that do not depend on diagnostics and can safely run twice.
     * Custom callbacks and external effects may run again on failure.
     */
    DETAILED_ON_FAILURE
  }

  public static final ParseOptions DEFAULT = new ParseOptions(Memoization.OFF);

  private final Memoization memoization;
  private final Diagnostics diagnostics;

  public ParseOptions(Memoization memoization) {
    this(memoization, Diagnostics.AUTO);
  }

  private ParseOptions(Memoization memoization, Diagnostics diagnostics) {
    this.memoization = Objects.requireNonNull(memoization, "memoization");
    this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
  }

  public static ParseOptions defaults() {
    return DEFAULT;
  }

  public static ParseOptions withMemoization(Memoization memoization) {
    return new ParseOptions(memoization);
  }

  public Memoization memoization() {
    return memoization;
  }

  public Diagnostics diagnostics() {
    return diagnostics;
  }

  public ParseOptions withMemoizationPolicy(Memoization policy) {
    return policy == memoization ? this : new ParseOptions(policy, diagnostics);
  }

  /** Selects syntax diagnostics without changing the memoization policy. */
  public ParseOptions withDiagnostics(Diagnostics policy) {
    return policy == diagnostics ? this : new ParseOptions(memoization, policy);
  }

  /**
   * Resolves AUTO once the entry point knows whether it can safely retry in a fresh context.
   * Pass false for low-level operations without retry. Explicit policies remain unchanged.
   */
  public ParseOptions resolveDiagnostics(boolean deferredDiagnosticsSafe) {
    return diagnostics == Diagnostics.AUTO
        ? withDiagnostics(deferredDiagnosticsSafe ? Diagnostics.DETAILED_ON_FAILURE : Diagnostics.DETAILED)
        : this;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ParseOptions that
        && memoization == that.memoization && diagnostics == that.diagnostics;
  }

  @Override
  public int hashCode() {
    return 31 * memoization.hashCode() + diagnostics.hashCode();
  }

  @Override
  public String toString() {
    return "ParseOptions[memoization=" + memoization + ", diagnostics=" + diagnostics + "]";
  }
}
