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

  // Opt-in packrat memoization (issue #40). Off by default — default parsing is unaffected.
	private ParseOptions options = ParseOptions.DEFAULT;

	PackratMemoTable packratMemoTable;
	private boolean memoizationPermanentlyDisabled;
	private long memoizationStateVersion;
	private long nextMemoizationStateVersion;

	public final Source source;

	boolean createMetaToken = true;
	
	Map<Name, ParserListener> parserListenerByName = new LinkedHashMap<>();
	
	Map<Name, TransactionListener> listenerByName = new LinkedHashMap<>();
	private final Set<TransactionListener> memoizationTransparentTransactionListeners =
		Collections.newSetFromMap(new IdentityHashMap<>());

	final Deque<TransactionElement> tokenStack = new ArrayDeque<TransactionElement>();

	//FIXME change store to ScopeTree
	public Map<ChoiceInterface, Parser> chosenParserByChoice = new HashMap<>();
	
	//FIXME change store to ScopeTree
	public Map<NonOrdered, Parsers> orderedParsersByNonOrdered = new HashMap<>();
	
	Map<Parser, Map<Name, Object>> scopeTreeMapByParser = new HashMap<>();
	
	Map<Name, Object> globalScopeTreeMap = new HashMap<>();

    private final List<TransactionalState> transactionalStates = new ArrayList<>();
    private final Set<TransactionElement> transactionalFrames =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<TransactionElement, Long> memoizationStateVersionByFrame =
        new IdentityHashMap<>();

    /**
     * Register an owner before its first mutation. Registration lasts for this
     * context's lifetime; registering the same instance again is harmless.
     * There is deliberately no unregister operation while snapshots may refer to it.
     * Late registration captures the current baseline in every open transaction,
     * so a parent rollback also undoes a committed child's first use of the state.
     * Owners must obey {@link TransactionalState#checkpoint()}'s no-throw contract.
     */
    public void registerTransactionalState(TransactionalState state) {
        java.util.Objects.requireNonNull(state, "state");
        if (transactionalStates.stream().anyMatch(existing -> existing == state)) return;
        transactionalStates.add(state);
        transactionalFrames.forEach(frame -> frame.checkpointState(state));
    }

    void checkpointTransactionalState(TransactionElement frame) {
        recordMemoTransactionBegin();
        transactionalFrames.add(frame);
        memoizationStateVersionByFrame.put(frame, memoizationStateVersion);
        transactionalStates.forEach(frame::checkpointState);
    }

    void finishTransactionalState(TransactionElement frame, boolean restore) {
        transactionalFrames.remove(frame);
        Long savedMemoizationStateVersion = memoizationStateVersionByFrame.remove(frame);
        if (savedMemoizationStateVersion == null) {
            throw new IllegalStateException("transaction state version snapshot is missing");
        }
        if (restore) {
            frame.restoreState();
            memoizationStateVersion = savedMemoizationStateVersion;
        }
        recordMemoTransactionFinish(restore
            ? MemoTransactionEvent.ROLLBACK : MemoTransactionEvent.COMMIT);
    }

    /** Marks a mutation of parser-visible state covered by the safe memoization contract. */
    public void markMemoizationStateChanged() {
        nextMemoizationStateVersion = Math.incrementExact(nextMemoizationStateVersion);
        memoizationStateVersion = nextMemoizationStateVersion;
    }

    public long getMemoizationStateVersion() {
        return memoizationStateVersion;
    }
	
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

  // Present only while an exact generated safe rule is being evaluated with memoization enabled.
  private final Deque<FailureDiagnostic> memoDiagnosticFrames = new ArrayDeque<>();

	public ParseContext(Source source, ParseContextEffector... parseContextEffectors) {
		this(source, ParseOptions.DEFAULT, parseContextEffectors);
	}

	private ParseContext(Source source, ParseOptions options,
			ParseContextEffector... parseContextEffectors) {
	  parseContextByThread.set(this);
	  if(source.sourceKind() != SourceKind.root) {
	    throw new IllegalArgumentException();
	  }
		this.options = java.util.Objects.requireNonNull(options, "options");
		if (options.memoization() == Memoization.SAFE_FAILURES) {
			this.packratMemoTable = new PackratMemoTable();
		}
		this.source = source;
		actions = new ArrayList<>();
		tokenStack.add(new TransactionElement(new ParserCursor(source.positionResolver())));

		for (ParseContextEffector parseContextEffector : parseContextEffectors) {
			parseContextEffector.effect(this);
		}
		onOpen(this);
	}

	/** Creates a parse session with options installed before listener {@code onOpen} callbacks. */
	public static ParseContext withOptions(Source source, ParseOptions options,
			ParseContextEffector... effectors) {
		return new ParseContext(source, options, effectors);
	}
	
	@Override
	public Deque<TransactionElement> getTokenStack(){
		return tokenStack;
	}

	/**
	 * Compatibility adapter for safe failure memoization. Prefer
	 * {@code ParseContext.withOptions(source, ParseOptions.withMemoization(Memoization.SAFE_FAILURES))}.
	 */
	@Deprecated
	public void enableMemoize() {
		this.options = ParseOptions.withMemoization(Memoization.SAFE_FAILURES);
		if (this.packratMemoTable == null) {
			this.packratMemoTable = new PackratMemoTable();
		}
	}

	public boolean isMemoizeEnabled() {
		return options.memoization() == Memoization.SAFE_FAILURES;
	}

	/** Memo hits must not skip callbacks, persistent actions, or trial recording. */
	public boolean isMemoizationSessionSafe() {
		if (memoizationPermanentlyDisabled || false == isMemoizeEnabled()) return false;
		if (false == parserListenerByName.isEmpty()
				|| listenerByName.values().stream().anyMatch(listener ->
					false == memoizationTransparentTransactionListeners.contains(listener))
				|| false == actions.isEmpty()
				|| recordingTrials) {
			disableMemoizationPermanently();
			return false;
		}
		return true;
	}

	private void disableMemoizationPermanently() {
		memoizationPermanentlyDisabled = true;
	}

	@Override
	public void addParserListener(Name name, ParserListener parserListener) {
		disableMemoizationPermanently();
		ParserListenerContainer.super.addParserListener(name, parserListener);
	}

	@Override
	public void addTransactionListener(Name name, TransactionListener listener) {
		disableMemoizationPermanently();
		Transaction.super.addTransactionListener(name, listener);
	}

	/**
	 * Registers an observational listener whose callbacks may be skipped for memoized parser work.
	 * The listener must not mutate parser-visible state, diagnostics, tokens, or application state.
	 */
	public void addMemoizationTransparentTransactionListener(
			Name name, TransactionListener listener) {
		java.util.Objects.requireNonNull(listener, "listener");
		memoizationTransparentTransactionListeners.add(listener);
		Transaction.super.addTransactionListener(name, listener);
	}

	public ParseOptions getOptions() { return options; }

	public PackratMemoTable getPackratMemoTable() {
		return packratMemoTable;
	}

	/** Deprecated compatibility effector; it enables only generated safe failures. */
	@Deprecated
	public static ParseContextEffector memoize() {
		return ParseContext::enableMemoize;
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
    disableMemoizationPermanently();
    recordingTrials = true;
    trialHistory.clear();
  }

  public void stopTrialRecording() {
    recordingTrials = false;
  }

  public List<ParseFailureDiagnostics.TrialRecord> getTrialHistory() {
    return Collections.unmodifiableList(trialHistory);
  }

  /** Rule-local diagnostics and direct transaction lifecycle replayed on a safe failure hit. */
  public static final class FailureDiagnostic {
    int stackBaseDepth;
    int transactionBaseDepth;
    int farthestConsumedOffset;
    int farthestMatchedOffset;
    int maxReachedOffset;
    int farthestFailureOffset = -1;
    List<ParseFailureDiagnostics.ParseStackElement> maxReachedStackElements = Collections.emptyList();
    List<ParseFailureDiagnostics.ParseStackElement> farthestFailureStackElements = Collections.emptyList();
    final List<String> expectedParsers = new ArrayList<>();
    final List<ParseFailureDiagnostics.ExpectedHintCandidate> expectedHints = new ArrayList<>();
    final List<ParseFailureDiagnostics.TrialRecord> trials = new ArrayList<>();
    final List<MemoTransactionEvent> transactionEvents = new ArrayList<>();
  }

  private enum MemoTransactionEvent { BEGIN, COMMIT, ROLLBACK }

  /** Snapshot used only to discard diagnostics from successful negative lookahead speculation. */
  public static final class DiagnosticSpeculation {
    private final FailureDiagnostic global;
    private final Map<FailureDiagnostic, FailureDiagnostic> active = new IdentityHashMap<>();

    private DiagnosticSpeculation(ParseContext context) {
      global = context.globalFailureDiagnostic();
      for (FailureDiagnostic frame : context.memoDiagnosticFrames) active.put(frame, copyOf(frame));
    }
  }

  public DiagnosticSpeculation beginDiagnosticSpeculation() {
    return new DiagnosticSpeculation(this);
  }

  public void discardDiagnosticSpeculation(DiagnosticSpeculation speculation) {
    restoreGlobalFailureDiagnostic(speculation.global);
    for (Map.Entry<FailureDiagnostic, FailureDiagnostic> entry : speculation.active.entrySet()) {
      restoreFailureDiagnostic(entry.getKey(), entry.getValue());
    }
  }

  private FailureDiagnostic globalFailureDiagnostic() {
    FailureDiagnostic result = new FailureDiagnostic();
    result.farthestConsumedOffset = farthestConsumedOffset;
    result.farthestMatchedOffset = farthestMatchedOffset;
    result.maxReachedOffset = maxReachedOffset;
    result.farthestFailureOffset = farthestFailureOffset;
    result.maxReachedStackElements = maxReachedStackElements;
    result.farthestFailureStackElements = farthestFailureStackElements;
    result.expectedParsers.addAll(expectedParsersAtFarthestFailure);
    result.expectedHints.addAll(expectedHintCandidatesAtFarthestFailure);
    result.trials.addAll(trialHistory);
    return result;
  }

  private static FailureDiagnostic copyOf(FailureDiagnostic source) {
    FailureDiagnostic result = new FailureDiagnostic();
    restoreFailureDiagnostic(result, source);
    return result;
  }

  private static void restoreFailureDiagnostic(FailureDiagnostic target, FailureDiagnostic source) {
    target.stackBaseDepth = source.stackBaseDepth;
    target.farthestConsumedOffset = source.farthestConsumedOffset;
    target.farthestMatchedOffset = source.farthestMatchedOffset;
    target.maxReachedOffset = source.maxReachedOffset;
    target.farthestFailureOffset = source.farthestFailureOffset;
    target.maxReachedStackElements = source.maxReachedStackElements;
    target.farthestFailureStackElements = source.farthestFailureStackElements;
    target.expectedParsers.clear();
    target.expectedParsers.addAll(source.expectedParsers);
    target.expectedHints.clear();
    target.expectedHints.addAll(source.expectedHints);
    target.trials.clear();
    target.trials.addAll(source.trials);
  }

  private void restoreGlobalFailureDiagnostic(FailureDiagnostic source) {
    farthestConsumedOffset = source.farthestConsumedOffset;
    farthestMatchedOffset = source.farthestMatchedOffset;
    maxReachedOffset = source.maxReachedOffset;
    farthestFailureOffset = source.farthestFailureOffset;
    maxReachedStackElements = source.maxReachedStackElements;
    farthestFailureStackElements = source.farthestFailureStackElements;
    expectedParsersAtFarthestFailure.clear();
    expectedParsersAtFarthestFailure.addAll(source.expectedParsers);
    expectedHintCandidatesAtFarthestFailure.clear();
    expectedHintCandidatesAtFarthestFailure.addAll(source.expectedHints);
    trialHistory.clear();
    trialHistory.addAll(source.trials);
  }

  FailureDiagnostic beginMemoDiagnosticFrame() {
    FailureDiagnostic frame = new FailureDiagnostic();
    frame.stackBaseDepth = parseFrames.size();
    frame.transactionBaseDepth = tokenStack.size();
    memoDiagnosticFrames.push(frame);
    return frame;
  }

  void discardMemoDiagnosticFrame(FailureDiagnostic frame) {
    if (memoDiagnosticFrames.pollFirst() != frame) {
      throw new IllegalStateException("memo diagnostic frame nesting is illegal");
    }
  }

  void replayFailureDiagnostic(FailureDiagnostic diagnostic) {
    FailureDiagnostic rebased = copyOf(diagnostic);
    rebased.maxReachedStackElements = rebaseMemoStack(diagnostic.maxReachedStackElements);
    rebased.farthestFailureStackElements = rebaseMemoStack(diagnostic.farthestFailureStackElements);
    mergeFailureDiagnosticIntoGlobal(rebased);
    for (FailureDiagnostic active : memoDiagnosticFrames) {
      FailureDiagnostic local = copyOf(rebased);
      local.maxReachedStackElements = localStackSnapshot(active, rebased.maxReachedStackElements);
      local.farthestFailureStackElements = localStackSnapshot(active, rebased.farthestFailureStackElements);
      mergeFailureDiagnostic(active, local);
    }
  }

  /** Replays only registered-state hooks; token/cursor transactions remain untouched on a hit. */
  void replayMemoTransactionEvents(FailureDiagnostic diagnostic) {
    Deque<List<Runnable>> restoresByTransaction = new ArrayDeque<>();
    for (MemoTransactionEvent event : diagnostic.transactionEvents) {
      switch (event) {
        case BEGIN -> {
          List<Runnable> restores = new ArrayList<>(transactionalStates.size());
          for (TransactionalState state : transactionalStates) restores.add(state.checkpoint());
          restoresByTransaction.push(restores);
        }
        case COMMIT -> {
          if (restoresByTransaction.pollFirst() == null) {
            throw new IllegalStateException("memo transaction trace is unbalanced");
          }
        }
        case ROLLBACK -> {
          List<Runnable> restores = restoresByTransaction.pollFirst();
          if (restores == null) throw new IllegalStateException("memo transaction trace is unbalanced");
          restores.forEach(Runnable::run);
        }
      }
    }
    if (false == restoresByTransaction.isEmpty()) {
      throw new IllegalStateException("memo transaction trace is unbalanced");
    }
  }

  private void recordMemoTransactionBegin() {
    for (FailureDiagnostic diagnostic : memoDiagnosticFrames) {
      if (tokenStack.size() == diagnostic.transactionBaseDepth + 1) {
        diagnostic.transactionEvents.add(MemoTransactionEvent.BEGIN);
      }
    }
  }

  private void recordMemoTransactionFinish(MemoTransactionEvent event) {
    for (FailureDiagnostic diagnostic : memoDiagnosticFrames) {
      if (tokenStack.size() == diagnostic.transactionBaseDepth) {
        diagnostic.transactionEvents.add(event);
      }
    }
  }

  private List<ParseFailureDiagnostics.ParseStackElement> rebaseMemoStack(
      List<ParseFailureDiagnostics.ParseStackElement> localSuffix) {
    if (localSuffix.isEmpty()) return Collections.emptyList();
    List<ParseFailureDiagnostics.ParseStackElement> result = snapshotStackElements();
    for (ParseFailureDiagnostics.ParseStackElement element : localSuffix) {
      result.add(new ParseFailureDiagnostics.ParseStackElement(
          element.getParserClassName(), result.size(), element.getStartOffset(),
          element.getMaxConsumedOffset(), element.getMaxMatchedOffset()));
    }
    return result;
  }

  private static List<ParseFailureDiagnostics.ParseStackElement> localStackSnapshot(
      FailureDiagnostic diagnostic,
      List<ParseFailureDiagnostics.ParseStackElement> absoluteSnapshot) {
    int base = Math.min(diagnostic.stackBaseDepth, absoluteSnapshot.size());
    return new ArrayList<>(absoluteSnapshot.subList(base, absoluteSnapshot.size()));
  }

  private void mergeFailureDiagnosticIntoGlobal(FailureDiagnostic diagnostic) {
    farthestConsumedOffset = Math.max(farthestConsumedOffset, diagnostic.farthestConsumedOffset);
    farthestMatchedOffset = Math.max(farthestMatchedOffset, diagnostic.farthestMatchedOffset);
    if (diagnostic.maxReachedOffset > maxReachedOffset
        || diagnostic.maxReachedOffset == maxReachedOffset
            && diagnostic.maxReachedStackElements.size() > maxReachedStackElements.size()) {
      maxReachedOffset = diagnostic.maxReachedOffset;
      maxReachedStackElements = diagnostic.maxReachedStackElements;
    }
    if (diagnostic.farthestFailureOffset > farthestFailureOffset) {
      farthestFailureOffset = diagnostic.farthestFailureOffset;
      farthestFailureStackElements = diagnostic.farthestFailureStackElements;
      expectedParsersAtFarthestFailure.clear();
      expectedHintCandidatesAtFarthestFailure.clear();
    }
    if (diagnostic.farthestFailureOffset == farthestFailureOffset) {
      if (diagnostic.farthestFailureStackElements.size() > farthestFailureStackElements.size()) {
        farthestFailureStackElements = diagnostic.farthestFailureStackElements;
      }
      for (String expected : diagnostic.expectedParsers) addExpectedHint(expected);
      for (ParseFailureDiagnostics.ExpectedHintCandidate hint : diagnostic.expectedHints) {
        addExpectedHintCandidate(hint);
      }
    }
    if (recordingTrials) trialHistory.addAll(diagnostic.trials);
  }

  private void mergeFailureDiagnostic(FailureDiagnostic target, FailureDiagnostic source) {
    target.farthestConsumedOffset = Math.max(target.farthestConsumedOffset, source.farthestConsumedOffset);
    target.farthestMatchedOffset = Math.max(target.farthestMatchedOffset, source.farthestMatchedOffset);
    if (source.maxReachedOffset > target.maxReachedOffset
        || source.maxReachedOffset == target.maxReachedOffset
            && source.maxReachedStackElements.size() > target.maxReachedStackElements.size()) {
      target.maxReachedOffset = source.maxReachedOffset;
      target.maxReachedStackElements = source.maxReachedStackElements;
    }
    if (source.farthestFailureOffset > target.farthestFailureOffset) {
      target.farthestFailureOffset = source.farthestFailureOffset;
      target.farthestFailureStackElements = source.farthestFailureStackElements;
      target.expectedParsers.clear();
      target.expectedHints.clear();
    }
    if (source.farthestFailureOffset == target.farthestFailureOffset) {
      if (source.farthestFailureStackElements.size() > target.farthestFailureStackElements.size()) {
        target.farthestFailureStackElements = source.farthestFailureStackElements;
      }
      for (String expected : source.expectedParsers) {
        if (!target.expectedParsers.contains(expected)) target.expectedParsers.add(expected);
      }
      for (ParseFailureDiagnostics.ExpectedHintCandidate hint : source.expectedHints) {
        addExpectedHintCandidate(target.expectedHints, hint);
      }
    }
    target.trials.addAll(source.trials);
  }

  private void addExpectedHintCandidate(
      List<ParseFailureDiagnostics.ExpectedHintCandidate> target,
      ParseFailureDiagnostics.ExpectedHintCandidate candidate) {
    for (ParseFailureDiagnostics.ExpectedHintCandidate current : target) {
      if (current.getDisplayHint().equals(candidate.getDisplayHint())
          && current.getParserQualifiedClassName().equals(candidate.getParserQualifiedClassName())) return;
    }
    target.add(candidate);
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
    if (false == additionalCommitActions.isEmpty()) disableMemoizationPermanently();
    actions.addAll(additionalCommitActions);
  }
  
  public Source peekLast(CodePointIndex endIndexInclusive, CodePointLength length) {
    return getSource().peekLast(endIndexInclusive, length);
  }
  
  public Source peek(CodePointIndex startIndexInclusive, CodePointLength length) {
    return getSource().peek(startIndexInclusive, length);
  }
  
  public  Source peek(CodePointIndex startIndexInclusive, CodePointIndex endIndexExclusive) {
    return getSource().peek(startIndexInclusive, endIndexExclusive);
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
        ParseFailureDiagnostics.TrialRecord trial = new ParseFailureDiagnostics.TrialRecord(
            parser.getClass().getSimpleName(),
            frame.startOffset,
            endPos,
            succeeded,
            consumed);
        trialHistory.add(trial);
        for (FailureDiagnostic diagnostic : memoDiagnosticFrames) diagnostic.trials.add(trial);
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
    List<ParseFailureDiagnostics.ParseStackElement> snapshot = null;
    for (FailureDiagnostic diagnostic : memoDiagnosticFrames) {
      diagnostic.farthestConsumedOffset = Math.max(diagnostic.farthestConsumedOffset, consumed);
      diagnostic.farthestMatchedOffset = Math.max(diagnostic.farthestMatchedOffset, matched);
      if (reached > diagnostic.maxReachedOffset) {
        if (snapshot == null) snapshot = snapshotStackElements();
        diagnostic.maxReachedOffset = reached;
        diagnostic.maxReachedStackElements = localStackSnapshot(diagnostic, snapshot);
      } else if (reached == diagnostic.maxReachedOffset) {
        if (snapshot == null) snapshot = snapshotStackElements();
        List<ParseFailureDiagnostics.ParseStackElement> local = localStackSnapshot(diagnostic, snapshot);
        if (local.size() > diagnostic.maxReachedStackElements.size()) {
          diagnostic.maxReachedStackElements = local;
        }
      }
    }
  }

  void registerFailureCandidate(ParseFrame frame) {
    int candidateOffset = frame.maxOffset();
    List<ParseFailureDiagnostics.ExpectedHintCandidate> parserHints = expectedHintCandidatesFor(frame.parser);
    java.util.Optional<ParseFailureDiagnostics.ExpectedHintCandidate> terminalHint = deepestTerminalHintCandidate();
    List<ParseFailureDiagnostics.ParseStackElement> snapshot = snapshotStackElements();
    if (candidateOffset > farthestFailureOffset) {
      farthestFailureOffset = candidateOffset;
      expectedParsersAtFarthestFailure.clear();
      expectedHintCandidatesAtFarthestFailure.clear();
      for (ParseFailureDiagnostics.ExpectedHintCandidate hint : parserHints) {
        addExpectedHintCandidate(hint);
      }
      terminalHint.ifPresent(this::addExpectedHintCandidate);
      farthestFailureStackElements = snapshot;
    } else if (candidateOffset == farthestFailureOffset) {
      for (ParseFailureDiagnostics.ExpectedHintCandidate hint : parserHints) {
        addExpectedHintCandidate(hint);
      }
      terminalHint.ifPresent(this::addExpectedHintCandidate);
      if (snapshot.size() > farthestFailureStackElements.size()) {
        farthestFailureStackElements = snapshot;
      }
    }
    for (FailureDiagnostic diagnostic : memoDiagnosticFrames) {
      List<ParseFailureDiagnostics.ParseStackElement> localSnapshot =
          localStackSnapshot(diagnostic, snapshot);
      if (candidateOffset > diagnostic.farthestFailureOffset) {
        diagnostic.farthestFailureOffset = candidateOffset;
        diagnostic.farthestFailureStackElements = localSnapshot;
        diagnostic.expectedParsers.clear();
        diagnostic.expectedHints.clear();
      }
      if (candidateOffset == diagnostic.farthestFailureOffset) {
        if (localSnapshot.size() > diagnostic.farthestFailureStackElements.size()) {
          diagnostic.farthestFailureStackElements = localSnapshot;
        }
        for (ParseFailureDiagnostics.ExpectedHintCandidate hint : parserHints) {
          addExpectedHintCandidate(diagnostic.expectedHints, hint);
          String display = hint.getDisplayHint();
          if (display != null && !display.isBlank()
              && !diagnostic.expectedParsers.contains(display)) diagnostic.expectedParsers.add(display);
        }
        terminalHint.ifPresent(hint -> {
          addExpectedHintCandidate(diagnostic.expectedHints, hint);
          String display = hint.getDisplayHint();
          if (display != null && !display.isBlank()
              && !diagnostic.expectedParsers.contains(display)) diagnostic.expectedParsers.add(display);
        });
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
