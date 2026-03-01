package org.unlaxer.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ParseFailureDiagnostics {

  public static class ParseStackElement {
    private final String parserClassName;
    private final int depth;
    private final int startOffset;
    private final int maxConsumedOffset;
    private final int maxMatchedOffset;

    public ParseStackElement(
        String parserClassName,
        int depth,
        int startOffset,
        int maxConsumedOffset,
        int maxMatchedOffset) {
      this.parserClassName = parserClassName;
      this.depth = depth;
      this.startOffset = startOffset;
      this.maxConsumedOffset = maxConsumedOffset;
      this.maxMatchedOffset = maxMatchedOffset;
    }

    public String getParserClassName() {
      return parserClassName;
    }

    public int getDepth() {
      return depth;
    }

    public int getStartOffset() {
      return startOffset;
    }

    public int getMaxConsumedOffset() {
      return maxConsumedOffset;
    }

    public int getMaxMatchedOffset() {
      return maxMatchedOffset;
    }
  }

  public static class ExpectedHintCandidate {
    private final String displayHint;
    private final String parserClassName;
    private final String parserQualifiedClassName;
    private final int parserDepth;
    private final boolean terminal;

    public ExpectedHintCandidate(
        String displayHint,
        String parserClassName,
        String parserQualifiedClassName,
        int parserDepth,
        boolean terminal) {
      this.displayHint = displayHint;
      this.parserClassName = parserClassName;
      this.parserQualifiedClassName = parserQualifiedClassName;
      this.parserDepth = parserDepth;
      this.terminal = terminal;
    }

    public String getDisplayHint() {
      return displayHint;
    }

    public String getParserClassName() {
      return parserClassName;
    }

    public String getParserQualifiedClassName() {
      return parserQualifiedClassName;
    }

    public int getParserDepth() {
      return parserDepth;
    }

    public boolean isTerminal() {
      return terminal;
    }
  }

  private final int farthestOffset;
  private final int farthestConsumedOffset;
  private final int farthestMatchedOffset;
  private final int line;
  private final int column;
  private final List<ParseStackElement> maxReachedStackElements;
  private final List<String> expectedParsers;
  private final List<ExpectedHintCandidate> expectedHintCandidates;
  private final boolean hasFailureCandidate;

  public ParseFailureDiagnostics(
      int farthestOffset,
      int farthestConsumedOffset,
      int farthestMatchedOffset,
      int line,
      int column,
      List<ParseStackElement> maxReachedStackElements,
      List<String> expectedParsers,
      List<ExpectedHintCandidate> expectedHintCandidates,
      boolean hasFailureCandidate) {
    this.farthestOffset = farthestOffset;
    this.farthestConsumedOffset = farthestConsumedOffset;
    this.farthestMatchedOffset = farthestMatchedOffset;
    this.line = line;
    this.column = column;
    this.maxReachedStackElements = Collections.unmodifiableList(new ArrayList<>(maxReachedStackElements));
    this.expectedParsers = Collections.unmodifiableList(new ArrayList<>(expectedParsers));
    this.expectedHintCandidates = Collections.unmodifiableList(new ArrayList<>(expectedHintCandidates));
    this.hasFailureCandidate = hasFailureCandidate;
  }

  public int getFarthestOffset() {
    return farthestOffset;
  }

  public int getFarthestConsumedOffset() {
    return farthestConsumedOffset;
  }

  public int getFarthestMatchedOffset() {
    return farthestMatchedOffset;
  }

  public int getLine() {
    return line;
  }

  public int getColumn() {
    return column;
  }

  public List<ParseStackElement> getMaxReachedStackElements() {
    return maxReachedStackElements;
  }

  public List<String> getExpectedParsers() {
    return expectedParsers;
  }

  public List<ExpectedHintCandidate> getExpectedHintCandidates() {
    return expectedHintCandidates;
  }

  public boolean hasFailureCandidate() {
    return hasFailureCandidate;
  }
}
