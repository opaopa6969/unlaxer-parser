package org.unlaxer.context;

import java.util.Objects;

/**
 * A non-ordinary parse failure raised by {@code Commit} or {@code Label}.
 *
 * <p>An ordinary failure lets choice, optional and repetition backtrack. A raised failure is
 * carried on the {@link ParseContext} while the failure propagates, so those combinators stop
 * instead. A {@link Kind#LABEL labeled} failure is caught only by a {@code CatchLabels} that
 * names its label; a {@link Kind#COMMIT committed} failure is caught only by a recovery boundary
 * or reported by the outermost parse. Neither depends on memoization or diagnostic policy.
 *
 * @param kind    committed error or labeled failure
 * @param label   the label of a labeled failure, {@code null} for a committed error
 * @param message the reported message; a labeled failure reports its label
 * @param offset  code-point offset of the active cursor where the raising operator started
 */
public record RaisedFailure(Kind kind, String label, String message, int offset) {

  /** The two non-ordinary failure kinds. */
  public enum Kind {
    /** A committed (cut) error: no choice, optional, repetition or assertion catches it. */
    COMMIT,
    /** A labeled failure: only a {@code CatchLabels} naming the label catches it. */
    LABEL
  }

  /** Default message of a committed error declared without one. */
  public static final String DEFAULT_COMMIT_MESSAGE = "unexpected input";

  public RaisedFailure {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(message, "message");
    if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
    if (kind == Kind.LABEL && (label == null || label.isEmpty())) {
      throw new IllegalArgumentException("labeled failure requires a nonempty label");
    }
    if (kind == Kind.COMMIT && label != null) {
      throw new IllegalArgumentException("committed error has no label");
    }
  }

  public static RaisedFailure commit(String message, int offset) {
    return new RaisedFailure(Kind.COMMIT, null, message, offset);
  }

  public static RaisedFailure label(String label, int offset) {
    return new RaisedFailure(Kind.LABEL, label, label, offset);
  }

  public boolean isCommit() {
    return kind == Kind.COMMIT;
  }

  public boolean isLabel() {
    return kind == Kind.LABEL;
  }
}
