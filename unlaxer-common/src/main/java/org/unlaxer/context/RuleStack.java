package org.unlaxer.context;

import java.util.List;
import java.util.Objects;

/**
 * The grammar rules active at the final error, outermost first (#703).
 *
 * <p>Recorded only with {@link ParseOptions.Diagnostics#DETAILED}. For a committed or labeled
 * failure it is the stack where the raising operator ran; otherwise it is the stack of the farthest
 * failure, the deepest one (the earliest of those on ties) when several failures share that
 * position. Rules are the parsers marked {@link org.unlaxer.parser.GrammarRule}.
 * Shared with Rust {@code unlaxer_runtime::RuleStack}; see {@code docs/rule-stack-diagnostics.md}.
 *
 * @param offset  code-point offset of the failure the stack explains
 * @param frames  the innermost {@link #LIMIT} frames, outermost first
 * @param omitted number of outer frames dropped because the stack was deeper than the limit
 */
public record RuleStack(int offset, List<Frame> frames, int omitted) {

  /** Frames kept in a reported stack; deeper stacks keep their innermost frames. */
  public static final int LIMIT = 32;

  /**
   * One active rule.
   *
   * @param rule  the rule name
   * @param start code-point offset into the original input where the rule started
   */
  public record Frame(String rule, int start) {
    public Frame {
      Objects.requireNonNull(rule, "rule");
      if (start < 0) throw new IllegalArgumentException("start must not be negative");
    }
  }

  public RuleStack {
    frames = List.copyOf(frames);
    if (frames.isEmpty()) throw new IllegalArgumentException("a rule stack has at least one frame");
    if (frames.size() > LIMIT) throw new IllegalArgumentException("more than LIMIT frames");
    if (omitted < 0 || omitted > 0 && frames.size() != LIMIT) {
      throw new IllegalArgumentException("only a full stack omits outer frames");
    }
  }

  /** Bounds a full stack, outermost first, to its innermost {@link #LIMIT} frames; null if empty. */
  static RuleStack bounded(int offset, List<Frame> frames) {
    if (frames.isEmpty()) return null;
    int omitted = Math.max(0, frames.size() - LIMIT);
    return new RuleStack(offset, frames.subList(omitted, frames.size()), omitted);
  }

  /** Total nesting depth at the failure, including omitted frames. */
  public int depth() {
    return omitted + frames.size();
  }

  /** Code-point offset where a frame's span ends: the failure, never before the frame's start. */
  public int end(Frame frame) {
    return Math.max(offset, frame.start());
  }
}
