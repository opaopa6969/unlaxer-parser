package org.unlaxer.context;

import java.util.Objects;

/** Immutable options for one parse session. The default keeps memoization disabled. */
public final class ParseOptions {
  public static final ParseOptions DEFAULT = new ParseOptions(Memoization.OFF);

  private final Memoization memoization;

  public ParseOptions(Memoization memoization) {
    this.memoization = Objects.requireNonNull(memoization, "memoization");
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

  public ParseOptions withMemoizationPolicy(Memoization policy) {
    return policy == memoization ? this : new ParseOptions(policy);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ParseOptions that && memoization == that.memoization;
  }

  @Override
  public int hashCode() {
    return memoization.hashCode();
  }

  @Override
  public String toString() {
    return "ParseOptions[memoization=" + memoization + "]";
  }
}
