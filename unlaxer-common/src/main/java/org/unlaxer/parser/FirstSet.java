package org.unlaxer.parser;

import java.io.Serializable;

/**
 * The set of code points a {@link Parser} can start a successful match with, plus whether it can
 * succeed consuming nothing. It is a conservative over-approximation: {@link #mayStartWith(int)}
 * may answer {@code true} for a code point the parser would in fact reject, but it never answers
 * {@code false} for a code point the parser could accept.
 *
 * <p>ASCII (code points 0..127) is held exactly in two {@code long} words. Everything above that is
 * a single "may start with a non-ASCII code point" flag, which keeps the hot-path test to one
 * shift and one mask for the code points grammars actually discriminate on.
 *
 * <p>{@link #UNKNOWN} is the neutral element used for every construct whose start set cannot be
 * derived from the parser graph (lookahead, inverted matching, recovery, back references, …).
 * It answers {@code true} for every code point, so a candidate carrying it is never excluded.
 */
public final class FirstSet implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Never excludes anything. Used for every construct the analysis does not model. */
  public static final FirstSet UNKNOWN = new FirstSet(-1L, -1L, true, true, true);

  /** Matches nothing and cannot succeed on the empty input. Identity of {@link #union}. */
  public static final FirstSet EMPTY = new FirstSet(0L, 0L, false, false, false);

  /** Succeeds consuming nothing, so it must be tried at every position. */
  public static final FirstSet EPSILON = new FirstSet(0L, 0L, false, true, false);

  private final long asciiLow;
  private final long asciiHigh;
  private final boolean nonAscii;
  private final boolean nullable;
  private final boolean unknown;

  private FirstSet(long asciiLow, long asciiHigh, boolean nonAscii, boolean nullable,
      boolean unknown) {
    this.asciiLow = asciiLow;
    this.asciiHigh = asciiHigh;
    this.nonAscii = nonAscii;
    this.nullable = nullable;
    this.unknown = unknown;
  }

  /** A set holding exactly the given code points, none of them starting an empty match. */
  public static FirstSet ofCodePoints(int... codePoints) {
    long low = 0L;
    long high = 0L;
    boolean nonAscii = false;
    for (int codePoint : codePoints) {
      if (codePoint < 0) {
        continue;
      }
      if (codePoint < 64) {
        low |= 1L << codePoint;
      } else if (codePoint < 128) {
        high |= 1L << (codePoint - 64);
      } else {
        nonAscii = true;
      }
    }
    return new FirstSet(low, high, nonAscii, false, false);
  }

  /** The same set, additionally able to succeed without consuming anything. */
  public FirstSet asNullable() {
    return nullable ? this : new FirstSet(asciiLow, asciiHigh, nonAscii, true, unknown);
  }

  public FirstSet withNonAscii() {
    return nonAscii ? this : new FirstSet(asciiLow, asciiHigh, true, nullable, unknown);
  }

  public FirstSet union(FirstSet other) {
    if (unknown || other.unknown) {
      return UNKNOWN;
    }
    return new FirstSet(asciiLow | other.asciiLow, asciiHigh | other.asciiHigh,
        nonAscii || other.nonAscii, nullable || other.nullable, false);
  }

  /** Adds {@code other}'s start set but not its ability to match the empty input. */
  public FirstSet unionStarts(FirstSet other) {
    if (unknown || other.unknown) {
      return UNKNOWN;
    }
    return new FirstSet(asciiLow | other.asciiLow, asciiHigh | other.asciiHigh,
        nonAscii || other.nonAscii, nullable, false);
  }

  public boolean isUnknown() {
    return unknown;
  }

  public boolean isNullable() {
    return nullable;
  }

  /**
   * Whether a match starting at a position holding {@code codePoint} can succeed. A negative
   * {@code codePoint} means end of input, where only a parser that can match the empty input — or
   * one this analysis does not model — can succeed.
   */
  public boolean mayStartWith(int codePoint) {
    if (unknown || nullable) {
      return true;
    }
    if (codePoint < 0) {
      return false;
    }
    if (codePoint < 64) {
      return (asciiLow >>> codePoint & 1L) != 0L;
    }
    if (codePoint < 128) {
      return (asciiHigh >>> (codePoint - 64) & 1L) != 0L;
    }
    return nonAscii;
  }

  /**
   * Structural equality over every field. The fixed point in {@link FirstSets} must compare the
   * stored start set itself: {@link #mayStartWith(int)} answers {@code true} for everything once a
   * set is nullable, which would hide a start set that is still growing underneath it (a nullable
   * repetition still contributes its body's start set to the chain that contains it).
   */
  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (false == (other instanceof FirstSet)) {
      return false;
    }
    FirstSet that = (FirstSet) other;
    return asciiLow == that.asciiLow && asciiHigh == that.asciiHigh && nonAscii == that.nonAscii
        && nullable == that.nullable && unknown == that.unknown;
  }

  @Override
  public int hashCode() {
    return Long.hashCode(asciiLow) * 31 + Long.hashCode(asciiHigh) * 7
        + (nonAscii ? 1 : 0) + (nullable ? 2 : 0) + (unknown ? 4 : 0);
  }

  @Override
  public String toString() {
    if (unknown) {
      return "FirstSet[unknown]";
    }
    StringBuilder builder = new StringBuilder("FirstSet[");
    if (nullable) {
      builder.append("nullable ");
    }
    for (int codePoint = 0; codePoint < 128; codePoint++) {
      if (mayStartWithExactly(codePoint)) {
        builder.append(codePoint >= 33 && codePoint < 127 ? String.valueOf((char) codePoint)
            : "\\u" + String.format("%04x", codePoint));
      }
    }
    if (nonAscii) {
      builder.append("+nonAscii");
    }
    return builder.append(']').toString();
  }

  private boolean mayStartWithExactly(int codePoint) {
    return codePoint < 64 ? (asciiLow >>> codePoint & 1L) != 0L
        : (asciiHigh >>> (codePoint - 64) & 1L) != 0L;
  }
}
