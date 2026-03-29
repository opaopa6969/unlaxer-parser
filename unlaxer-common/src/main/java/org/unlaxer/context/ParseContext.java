package org.unlaxer.context;

import java.io.Closeable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.unlaxer.CodePointIndex;
import org.unlaxer.CodePointLength;
import org.unlaxer.Name;
import org.unlaxer.ParserCursor;
import org.unlaxer.Source;
import org.unlaxer.Source.SourceKind;
import org.unlaxer.TransactionElement;
import org.unlaxer.listener.ParserListener;
import org.unlaxer.listener.ParserListenerContainer;
import org.unlaxer.listener.TransactionListener;
import org.unlaxer.parser.GlobalScopeTree;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.TerminalSymbol;
import org.unlaxer.parser.combinator.ChoiceInterface;
import org.unlaxer.parser.combinator.NonOrdered;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;

public class ParseContext implements 
	Closeable, Transaction,
	ParserListenerContainer,
	GlobalScopeTree , ParserContextScopeTree{

  // TODO store successfully token's <position,tokens> map
	boolean doMemoize;

	public final Source source;

	boolean createMetaToken = true;
	
	Map<Name, ParserListener> parserListenerByName = new LinkedHashMap<>();
	
	Map<Name, TransactionListener> listenerByName = new LinkedHashMap<>();

	final Deque<TransactionElement> tokenStack = new ArrayDeque<TransactionElement>();

	//FIXME change store to ScopeTree
	public Map<ChoiceInterface, Parser> chosenParserByChoice = new HashMap<>();
	
	//FIXME change store to ScopeTree
	public Map<NonOrdered, Parsers> orderedParsersByNonOrdered = new HashMap<>();
	
	Map<Parser, Map<Name, Object>> scopeTreeMapByParser = new HashMap<>();
	
	Map<Name, Object> globalScopeTreeMap = new HashMap<>();
	
	Collection<AdditionalCommitAction> actions;

  final Deque<ParseFrame> parseFrames = new ArrayDeque<ParseFrame>();
  int farthestConsumedOffset = 0;
  int farthestMatchedOffset = 0;
  int maxReachedOffset = 0;
  int farthestFailureOffset = -1;
  List<ParseFailureDiagnostics.ParseStackElement> maxReachedStackElements = Collections.emptyList();
  List<ParseFailureDiagnostics.ParseStackElement> farthestFailureStackElements = Collections.emptyList();
  List<String> expectedParsersAtFarthestFailure = new ArrayList<String>();
  List<ParseFailureDiagnostics.ExpectedHintCandidate> expectedHintCandidatesAtFarthestFailure =
      new ArrayList<ParseFailureDiagnostics.ExpectedHintCandidate>();

  // Trial recording for enriched parse failure diagnostics
  private List<ParseFailureDiagnostics.TrialRecord> trialHistory = new ArrayList<>();
  private boolean recordingTrials = false;
	
	public ParseContext(Source source, ParseContextEffector... parseContextEffectors) {
	  parseContextByThread.set(this);
	  if(source.sourceKind() != SourceKind.root) {
	    throw new IllegalArgumentException();
	  }
		this.source = source;
		actions = new ArrayList<>();
		tokenStack.add(new TransactionElement(new ParserCursor(source.positionResolver())));

		for (ParseContextEffector parseContextEffector : parseContextEffectors) {
			parseContextEffector.effect(this);
		}
		onOpen(this);
	}
	
	@Override
	public Deque<TransactionElement> getTokenStack(){
		return tokenStack;
	}

	@Override
	public void close() {
	  parseContextByThread.set(null);
		if (tokenStack.size() != 1) {
			throw new IllegalStateException("transaction nest is illegal. check source code.");
		}
		onClose(this);
	}

  // --- Trial recording API ---

  public void startTrialRecording() {
    recordingTrials = true;
    trialHistory.clear();
  }

  public void stopTrialRecording() {
    recordingTrials = false;
  }

  public List<ParseFailureDiagnostics.TrialRecord> getTrialHistory() {
    return Collections.unmodifiableList(trialHistory);
  }

  public boolean isRecordingTrials() {
    return recordingTrials;
  }

	@Override
	public Map<Name, TransactionListener> getTransactionListenerByName() {
		return listenerByName;
	}

	@Override
	public Map<Parser, Map<Name, Object>> getParserContextScopeTreeMap() {
		return scopeTreeMapByParser;
	}

	@Override
	public Map<Name, Object> getGlobalScopeTreeMap() {
		return globalScopeTreeMap;
	}

	@Override
	public Map<Name, ParserListener> getParserListenerByName() {
		return parserListenerByName;
	}

	@Override
	public ParseContext get() {
		return this;
	}

	@Override
	public boolean doCreateMetaToken() {
		return createMetaToken;
	}

	@Override
	public Map<ChoiceInterface, Parser> getChosenParserByChoice() {
		return chosenParserByChoice;
	}

	@Override
	public Map<NonOrdered, Parsers> getOrderedParsersByNonOrdered() {
		return orderedParsersByNonOrdered;
	}

	@Override
	public Source getSource() {
		return source;
	}

  @Override
  public Collection<AdditionalCommitAction> getActions() {
    return actions;
  }

  @Override
  public void addActions(List<AdditionalCommitAction> additionalCommitActions) {
    actions.addAll(additionalCommitActions);
  }
  
  public Source peekLast(CodePointIndex endIndexInclusive, CodePointLength length) {
    return getSource().peekLast(endIndexInclusive, length);
  }
  
  public Source peek(CodePointIndex startIndexInclusive, CodePointLength length) {
    return getSource().peek(startIndexInclusive, length);
  }
  
  public  Source peek(CodePointIndex startIndexInclusive, CodePointIndex endIndexExclusive) {
    return peek(startIndexInclusive, endIndexExclusive);
  }
  
  static ThreadLocal<ParseContext> parseContextByThread = new ThreadLocal<>();
  
  public static ParseContext getParseContextWithCurrentThread() {
    return parseContextByThread.get();
  }

  @Override
  public void consume(CodePointLength length) {
    Transaction.super.consume(length);
    trackCursorProgress();
  }

  @Override
  public void matchOnly(CodePointLength length) {
    Transaction.super.matchOnly(length);
    trackCursorProgress();
  }

  @Override
  public void startParse(Parser parser, ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
    ParseFrame frame = new ParseFrame(
        parser,
        Transaction.super.getConsumedPosition().value(),
        Transaction.super.getMatchedPosition().value());
    parseFrames.push(frame);
    trackCursorProgress();
    ParserListenerContainer.super.startParse(parser, parseContext, tokenKind, invertMatch);
  }

  @Override
  public void endParse(Parser parser, Parsed parsed, ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
    trackCursorProgress();
    ParseFrame frame = parseFrames.peekFirst();
    if (frame != null) {
      frame.updateMax(
          Transaction.super.getConsumedPosition().value(),
          Transaction.super.getMatchedPosition().value());
      if (parsed != null && parsed.isFailed()) {
        registerFailureCandidate(frame);
      }
      // Record trial if trial recording is active
      if (recordingTrials) {
        int endPos = Transaction.super.getConsumedPosition().value();
        boolean succeeded = parsed != null && parsed.isSucceeded();
        int consumed = endPos - frame.startOffset;
        trialHistory.add(new ParseFailureDiagnostics.TrialRecord(
            parser.getClass().getSimpleName(),
            frame.startOffset,
            endPos,
            succeeded,
            consumed));
      }
      parseFrames.pollFirst();
    }
    ParserListenerContainer.super.endParse(parser, parsed, parseContext, tokenKind, invertMatch);
  }

  public ParseFailureDiagnostics getParseFailureDiagnostics() {
    int effectiveOffset = Math.max(0, farthestFailureOffset >= 0 ? farthestFailureOffset : maxReachedOffset);
    CodePointIndex codePointIndex = new CodePointIndex(effectiveOffset);
    int line = source.lineNumberFrom(codePointIndex).value();
    int column = source.codePointIndexInLineFrom(codePointIndex).value();
    List<ParseFailureDiagnostics.ParseStackElement> stack =
        farthestFailureStackElements.isEmpty() ? maxReachedStackElements : farthestFailureStackElements;

    // Compute expected tokens from trial history and hint candidates
    Set<String> expectedTokens = computeExpectedTokens();

    // Find deepest matched rule and position
    String deepestRule = "";
    int deepestPos = 0;
    for (ParseFailureDiagnostics.TrialRecord trial : trialHistory) {
      if (trial.isSucceeded() && trial.getConsumed() > deepestPos) {
        deepestPos = trial.getConsumed();
        deepestRule = trial.getParserName();
      }
    }
    // Also check stack elements for deepest matched rule
    if (deepestRule.isEmpty() && !stack.isEmpty()) {
      ParseFailureDiagnostics.ParseStackElement deepest = stack.get(stack.size() - 1);
      deepestRule = deepest.getParserClassName();
      deepestPos = Math.max(deepest.getMaxConsumedOffset(), deepest.getMaxMatchedOffset());
    }

    return new ParseFailureDiagnostics(
        effectiveOffset,
        farthestConsumedOffset,
        farthestMatchedOffset,
        line,
        column,
        stack,
        expectedParsersAtFarthestFailure,
        expectedHintCandidatesAtFarthestFailure,
        farthestFailureOffset >= 0,
        trialHistory,
        expectedTokens,
        deepestRule,
        deepestPos);
  }

  /**
   * Compute expected tokens at the failure point by analyzing trial history
   * and expected hint candidates.
   */
  Set<String> computeExpectedTokens() {
    Set<String> tokens = new HashSet<>();

    // Add from expected hint candidates (these come from TerminalSymbol analysis)
    for (ParseFailureDiagnostics.ExpectedHintCandidate candidate : expectedHintCandidatesAtFarthestFailure) {
      String hint = candidate.getDisplayHint();
      if (hint != null && !hint.isBlank()) {
        tokens.add(hint);
      }
    }

    // Add from expectedParsersAtFarthestFailure
    for (String parser : expectedParsersAtFarthestFailure) {
      if (parser != null && !parser.isBlank()) {
        tokens.add(parser);
      }
    }

    // Analyze trial history: failed trials at the farthest failure offset
    // indicate what was expected there
    int failOffset = farthestFailureOffset >= 0 ? farthestFailureOffset : maxReachedOffset;
    for (ParseFailureDiagnostics.TrialRecord trial : trialHistory) {
      if (!trial.isSucceeded() && trial.getStartPosition() == failOffset && trial.getConsumed() == 0) {
        tokens.add(trial.getParserName());
      }
    }

    return tokens;
  }

  void trackCursorProgress() {
    int consumed = Transaction.super.getConsumedPosition().value();
    int matched = Transaction.super.getMatchedPosition().value();
    if (consumed > farthestConsumedOffset) {
      farthestConsumedOffset = consumed;
    }
    if (matched > farthestMatchedOffset) {
      farthestMatchedOffset = matched;
    }
    ParseFrame frame = parseFrames.peekFirst();
    if (frame != null) {
      frame.updateMax(consumed, matched);
    }
    int reached = Math.max(consumed, matched);
    if (reached > maxReachedOffset) {
      maxReachedOffset = reached;
      maxReachedStackElements = snapshotStackElements();
    } else if (reached == maxReachedOffset) {
      List<ParseFailureDiagnostics.ParseStackElement> snapshot = snapshotStackElements();
      if (snapshot.size() > maxReachedStackElements.size()) {
        maxReachedStackElements = snapshot;
      }
    }
  }

  void registerFailureCandidate(ParseFrame frame) {
    int candidateOffset = frame.maxOffset();
    List<ParseFailureDiagnostics.ExpectedHintCandidate> parserHints = expectedHintCandidatesFor(frame.parser);
    java.util.Optional<ParseFailureDiagnostics.ExpectedHintCandidate> terminalHint = deepestTerminalHintCandidate();
    if (candidateOffset > farthestFailureOffset) {
      farthestFailureOffset = candidateOffset;
      expectedParsersAtFarthestFailure.clear();
      expectedHintCandidatesAtFarthestFailure.clear();
      for (ParseFailureDiagnostics.ExpectedHintCandidate hint : parserHints) {
        addExpectedHintCandidate(hint);
      }
      terminalHint.ifPresent(this::addExpectedHintCandidate);
      farthestFailureStackElements = snapshotStackElements();
      return;
    }
    if (candidateOffset == farthestFailureOffset) {
      for (ParseFailureDiagnostics.ExpectedHintCandidate hint : parserHints) {
        addExpectedHintCandidate(hint);
      }
      terminalHint.ifPresent(this::addExpectedHintCandidate);
      List<ParseFailureDiagnostics.ParseStackElement> snapshot = snapshotStackElements();
      if (snapshot.size() > farthestFailureStackElements.size()) {
        farthestFailureStackElements = snapshot;
      }
    }
  }

  void addExpectedHintCandidate(ParseFailureDiagnostics.ExpectedHintCandidate candidate) {
    if (candidate == null) {
      return;
    }
    String hint = candidate.getDisplayHint();
    if (hint == null || hint.isBlank()) {
      return;
    }
    for (ParseFailureDiagnostics.ExpectedHintCandidate current : expectedHintCandidatesAtFarthestFailure) {
      if (current.getDisplayHint().equals(candidate.getDisplayHint())
          && current.getParserQualifiedClassName().equals(candidate.getParserQualifiedClassName())) {
        return;
      }
    }
    expectedHintCandidatesAtFarthestFailure.add(candidate);
    addExpectedHint(hint);
  }

  void addExpectedHint(String hint) {
    if (hint == null || hint.isBlank()) {
      return;
    }
    if (false == expectedParsersAtFarthestFailure.contains(hint)) {
      expectedParsersAtFarthestFailure.add(hint);
    }
  }

  List<ParseFailureDiagnostics.ExpectedHintCandidate> expectedHintCandidatesFor(Parser parser) {
    LinkedHashMap<String, ParseFailureDiagnostics.ExpectedHintCandidate> candidates =
        new LinkedHashMap<String, ParseFailureDiagnostics.ExpectedHintCandidate>();
    collectExpectedCandidatesIterative(parser, candidates, 2);
    if (candidates.isEmpty()) {
      ParseFailureDiagnostics.ExpectedHintCandidate fallback = newExpectedHintCandidate(
          parser,
          parser.getClass().getSimpleName(),
          0);
      candidates.put(expectedCandidateKey(fallback), fallback);
    }
    return new ArrayList<ParseFailureDiagnostics.ExpectedHintCandidate>(candidates.values());
  }

  void collectExpectedCandidatesIterative(
      Parser parser,
      LinkedHashMap<String, ParseFailureDiagnostics.ExpectedHintCandidate> candidates,
      int maxDepth) {
    Deque<ParserDepth> queue = new ArrayDeque<ParserDepth>();
    Map<Parser, Integer> minDepthByParser = new IdentityHashMap<Parser, Integer>();
    queue.addLast(new ParserDepth(parser, 0));
    while (false == queue.isEmpty()) {
      ParserDepth current = queue.pollFirst();
      Integer minDepth = minDepthByParser.get(current.parser);
      if (minDepth != null && minDepth.intValue() <= current.depth) {
        continue;
      }
      minDepthByParser.put(current.parser, Integer.valueOf(current.depth));
      if (current.parser instanceof TerminalSymbol) {
        for (String hint : ((TerminalSymbol) current.parser).expectedDisplayTexts()) {
          ParseFailureDiagnostics.ExpectedHintCandidate candidate =
              newExpectedHintCandidate(current.parser, hint, current.depth);
          if (isValidExpectedHintCandidate(candidate)) {
            candidates.put(expectedCandidateKey(candidate), candidate);
          }
        }
      }
      if (current.depth >= maxDepth) {
        continue;
      }
      if (shouldExpandForExpected(current.parser)) {
        for (Parser child : current.parser.getChildren()) {
          queue.addLast(new ParserDepth(child, current.depth + 1));
        }
      }
    }
  }

  boolean shouldExpandForExpected(Parser parser) {
    if (parser instanceof TerminalSymbol) {
      return false;
    }
    return false == parser.getChildren().isEmpty();
  }

  java.util.Optional<String> deepestTerminalHint() {
    java.util.Optional<ParseFailureDiagnostics.ExpectedHintCandidate> candidate = deepestTerminalHintCandidate();
    if (candidate.isPresent()) {
      return java.util.Optional.of(candidate.get().getDisplayHint());
    }
    return java.util.Optional.empty();
  }

  java.util.Optional<ParseFailureDiagnostics.ExpectedHintCandidate> deepestTerminalHintCandidate() {
    for (ParseFrame frame : parseFrames) {
      Parser parser = frame.parser;
      if (parser instanceof TerminalSymbol) {
        for (String expected : ((TerminalSymbol) parser).expectedDisplayTexts()) {
          if (expected != null && false == expected.isBlank()) {
            return java.util.Optional.of(newExpectedHintCandidate(parser, expected, 0));
          }
        }
      }
    }
    return java.util.Optional.empty();
  }

  ParseFailureDiagnostics.ExpectedHintCandidate newExpectedHintCandidate(
      Parser parser,
      String displayHint,
      int parserDepth) {
    return new ParseFailureDiagnostics.ExpectedHintCandidate(
        displayHint,
        parser.getClass().getSimpleName(),
        parser.getClass().getName(),
        parserDepth,
        parser instanceof TerminalSymbol);
  }

  boolean isValidExpectedHintCandidate(ParseFailureDiagnostics.ExpectedHintCandidate candidate) {
    if (candidate == null) {
      return false;
    }
    String hint = candidate.getDisplayHint();
    return hint != null && false == hint.isBlank();
  }

  String expectedCandidateKey(ParseFailureDiagnostics.ExpectedHintCandidate candidate) {
    return candidate.getDisplayHint().concat("|").concat(candidate.getParserQualifiedClassName());
  }

  void addIfPresent(LinkedHashSet<String> hints, String hint) {
    if (hint == null || hint.isBlank()) {
      return;
    }
    hints.add(hint);
  }

  List<ParseFailureDiagnostics.ParseStackElement> snapshotStackElements() {
    List<ParseFrame> frames = new ArrayList<ParseFrame>(parseFrames);
    Collections.reverse(frames);
    List<ParseFailureDiagnostics.ParseStackElement> elements =
        new ArrayList<ParseFailureDiagnostics.ParseStackElement>(frames.size());
    int depth = 0;
    for (ParseFrame frame : frames) {
      elements.add(new ParseFailureDiagnostics.ParseStackElement(
          frame.parser.getClass().getSimpleName(),
          depth++,
          frame.startOffset,
          frame.maxConsumedOffset,
          frame.maxMatchedOffset));
    }
    return elements;
  }

  static class ParseFrame {
    final Parser parser;
    final int startOffset;
    int maxConsumedOffset;
    int maxMatchedOffset;

    ParseFrame(Parser parser, int startConsumedOffset, int startMatchedOffset) {
      this.parser = parser;
      this.startOffset = Math.max(startConsumedOffset, startMatchedOffset);
      this.maxConsumedOffset = startConsumedOffset;
      this.maxMatchedOffset = startMatchedOffset;
    }

    void updateMax(int consumedOffset, int matchedOffset) {
      if (consumedOffset > maxConsumedOffset) {
        maxConsumedOffset = consumedOffset;
      }
      if (matchedOffset > maxMatchedOffset) {
        maxMatchedOffset = matchedOffset;
      }
    }

    int maxOffset() {
      return Math.max(maxConsumedOffset, maxMatchedOffset);
    }
  }

  static class ParserDepth {
    final Parser parser;
    final int depth;

    ParserDepth(Parser parser, int depth) {
      this.parser = parser;
      this.depth = depth;
    }
  }
}
