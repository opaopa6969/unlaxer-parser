package org.unlaxer.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ParseFailureDiagnostics {

  /**
   * Record of a parser trial during PEG ordered choice.
   * Captures which parser was tried, the position range, success/failure, and how much was consumed.
   */
  public static class TrialRecord {
    private final String parserName;
    private final int startPosition;
    private final int endPosition;
    private final boolean succeeded;
    private final int consumed;

    public TrialRecord(String parserName, int startPosition, int endPosition, boolean succeeded, int consumed) {
      this.parserName = parserName;
      this.startPosition = startPosition;
      this.endPosition = endPosition;
      this.succeeded = succeeded;
      this.consumed = consumed;
    }

    public String getParserName() { return parserName; }
    public int getStartPosition() { return startPosition; }
    public int getEndPosition() { return endPosition; }
    public boolean isSucceeded() { return succeeded; }
    public int getConsumed() { return consumed; }

    @Override
    public String toString() {
      return String.format("TrialRecord{parser=%s, start=%d, end=%d, succeeded=%s, consumed=%d}",
          parserName, startPosition, endPosition, succeeded, consumed);
    }
  }

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
  private final List<TrialRecord> trialHistory;
  private final Set<String> expectedTokens;
  private final String deepestMatchedRule;
  private final int deepestConsumedPosition;

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
    this(farthestOffset, farthestConsumedOffset, farthestMatchedOffset, line, column,
        maxReachedStackElements, expectedParsers, expectedHintCandidates, hasFailureCandidate,
        Collections.emptyList(), Collections.emptySet(), "", 0);
  }

  public ParseFailureDiagnostics(
      int farthestOffset,
      int farthestConsumedOffset,
      int farthestMatchedOffset,
      int line,
      int column,
      List<ParseStackElement> maxReachedStackElements,
      List<String> expectedParsers,
      List<ExpectedHintCandidate> expectedHintCandidates,
      boolean hasFailureCandidate,
      List<TrialRecord> trialHistory,
      Set<String> expectedTokens,
      String deepestMatchedRule,
      int deepestConsumedPosition) {
    this.farthestOffset = farthestOffset;
    this.farthestConsumedOffset = farthestConsumedOffset;
    this.farthestMatchedOffset = farthestMatchedOffset;
    this.line = line;
    this.column = column;
    this.maxReachedStackElements = Collections.unmodifiableList(new ArrayList<>(maxReachedStackElements));
    this.expectedParsers = Collections.unmodifiableList(new ArrayList<>(expectedParsers));
    this.expectedHintCandidates = Collections.unmodifiableList(new ArrayList<>(expectedHintCandidates));
    this.hasFailureCandidate = hasFailureCandidate;
    this.trialHistory = Collections.unmodifiableList(new ArrayList<>(trialHistory));
    this.expectedTokens = Collections.unmodifiableSet(new HashSet<>(expectedTokens));
    this.deepestMatchedRule = deepestMatchedRule;
    this.deepestConsumedPosition = deepestConsumedPosition;
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

  public List<TrialRecord> getTrialHistory() {
    return trialHistory;
  }

  public Set<String> getExpectedTokens() {
    return expectedTokens;
  }

  public String getDeepestMatchedRule() {
    return deepestMatchedRule;
  }

  public int getDeepestConsumedPosition() {
    return deepestConsumedPosition;
  }
}
