package org.unlaxer;

import java.util.Optional;

/**
 * Backward-compatibility wrapper retained for legacy projects.
 * Prefer using {@link Source} and {@link CursorRange} directly.
 */
@Deprecated
public class RangedString extends StringSource {

  public final Range range;
  public final Optional<String> token;

  public RangedString(int startIndexInclusive, String token) {
    super(StringSource.createRootSource(token == null ? "" : token),
        token == null ? "" : token,
        new CodePointOffset(startIndexInclusive));
    int length = token == null ? 0 : token.codePointCount(0, token.length());
    this.range = new Range(startIndexInclusive, startIndexInclusive + length);
    this.token = token == null || token.isEmpty() ? Optional.empty() : Optional.of(token);
  }

  public RangedString(CursorRange cursorRange, Optional<String> token) {
    this(
        cursorRange == null ? 0 : cursorRange.startIndexInclusive.position().value(),
        token.orElse("")
    );
  }
}
