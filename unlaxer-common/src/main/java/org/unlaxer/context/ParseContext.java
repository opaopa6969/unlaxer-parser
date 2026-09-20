package org.unlaxer.context;

import java.io.Closeable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
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

	/** Records choice metadata in the current transaction so an enclosing rollback removes it. */
	public void choose(ChoiceInterface choice, Parser parser) {
		getCurrent().recordChosenParser(choice, chosenParserByChoice.get(choice));
		chosenParserByChoice.put(choice, parser);
	}

	/** Records interleave ordering in the current transaction so an enclosing rollback removes it. */
	public void order(NonOrdered nonOrdered, Parsers parsers) {
		getCurrent().recordOrderedParsers(nonOrdered, orderedParsersByNonOrdered.get(nonOrdered));
		orderedParsersByNonOrdered.put(nonOrdered, parsers);
	}
	
	Map<Parser, Map<Name, Object>> scopeTreeMapByParser = new HashMap<>();
	
	Map<Name, Object> globalScopeTreeMap = new HashMap<>();

    private final List<TransactionalState> transactionalStates = new ArrayList<>();
    private final Set<TransactionElement> transactionalFrames =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean transactionMetricsEnabled;
    private long transactionsOpened;
    private long transactionsCommitted;
    private long transactionsRolledBack;
    private long nonemptyPayloadSnapshots;
    private long emptyPayloadCheckpoints;
    private long copyOnWriteDeepCopies;

    /**
     * Register an owner before its first mutation. Registration lasts for this
     * context's lifetime; registering the same instance again is harmless.
     * There is deliberately no unregister operation while snapshots may refer to it.
     * Late registration of an ordinary owner captures the current baseline in every
     * open transaction, so a parent rollback also undoes a committed child's first
     * use of the state. Mutation-aware owners defer that work until
     * {@link #beforeTransactionalStateMutation(TransactionalState)}.
     * Owners must obey {@link TransactionalState#checkpoint()}'s no-throw contract.
     */
    public void registerTransactionalState(TransactionalState state) {
        java.util.Objects.requireNonNull(state, "state");
        if (transactionalStates.stream().anyMatch(existing -> existing == state)) return;
        transactionalStates.add(state);
        if (false == state instanceof MutationAwareTransactionalState) {
            transactionalFrames.forEach(frame -> checkpointState(frame, state));
        }
    }

    /**
     * Installs this owner's rollback snapshot in every open transaction, once per
     * frame. Mutation-aware owners must call this immediately before each mutation.
     */
    public void beforeTransactionalStateMutation(TransactionalState state) {
        java.util.Objects.requireNonNull(state, "state");
        if (false == state instanceof MutationAwareTransactionalState) {
            throw new IllegalArgumentException("state is not mutation-aware");
        }
        if (transactionalStates.stream().noneMatch(existing -> existing == state)) {
            throw new IllegalStateException("transactional state is not registered");
        }
        transactionalFrames.forEach(frame -> checkpointState(frame, state));
    }

    void checkpointTransactionalState(TransactionElement frame) {
        recordMemoTransactionBegin();
        transactionalFrames.add(frame);
        frame.saveMemoizationStateVersion(memoizationStateVersion);
        transactionalStates.stream()
            .filter(state -> false == state instanceof MutationAwareTransactionalState)
            .forEach(state -> checkpointState(frame, state));
        if (transactionMetricsEnabled) transactionsOpened++;
    }

    void finishTransactionalState(TransactionElement frame, boolean restore) {
        transactionalFrames.remove(frame);
        long savedMemoizationStateVersion = frame.takeMemoizationStateVersion();
        if (restore) {
            frame.restoreState();
            memoizationStateVersion = savedMemoizationStateVersion;
        }
        if (transactionMetricsEnabled) {
            if (restore) transactionsRolledBack++;
            else transactionsCommitted++;
            if (frame.hasStateCheckpoints()) nonemptyPayloadSnapshots++;
            else emptyPayloadCheckpoints++;
        }
        recordMemoTransactionFinish(restore
            ? MemoTransactionEvent.ROLLBACK : MemoTransactionEvent.COMMIT);
    }

    private void checkpointState(TransactionElement frame, TransactionalState state) {
        if (frame.checkpointStateIfAbsent(state) && transactionMetricsEnabled) {
            copyOnWriteDeepCopies++;
        }
    }

    /**
     * Enables and resets low-overhead transaction counters. Call before parsing.
     * A nonempty/empty payload is counted when a transaction finishes, according
     * to whether its frame acquired any registered-state rollback snapshot.
     * Deep copies count actual owner {@link TransactionalState#checkpoint()}
     * invocations, including eager compatibility owners.
     */
    public void enableTransactionMetrics() {
        if (tokenStack.size() != 1) {
            throw new IllegalStateException("transaction metrics must be enabled outside a transaction");
        }
        transactionMetricsEnabled = true;
        transactionsOpened = 0;
        transactionsCommitted = 0;
        transactionsRolledBack = 0;
        nonemptyPayloadSnapshots = 0;
        emptyPayloadCheckpoints = 0;
        copyOnWriteDeepCopies = 0;
    }

    /** Returns a stable snapshot without disabling collection. */
    public TransactionMetrics snapshotTransactionMetrics() {
        return new TransactionMetrics(transactionsOpened, transactionsCommitted,
            transactionsRolledBack, nonemptyPayloadSnapshots, emptyPayloadCheckpoints,
            copyOnWriteDeepCopies);
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
  /*
   * Where expected hints come from at the farthest failure, in first-seen order. Hints are
   * expanded from these sources only when diagnostics are requested; see ExpectedSources.
   */
  private final ExpectedSources expectedAtFarthestFailure = new ExpectedSources();
  /*
   * Hint candidates depend only on the parser graph and each TerminalSymbol's display texts,
   * which are fixed for the life of a parse, so they are computed once per parser instance.
   */
  private final Map<Parser, List<ParseFailureDiagnostics.ExpectedHintCandidate>> hintCandidatesByParser =
      new IdentityHashMap<>();
  private final Map<Parser, java.util.Optional<ParseFailureDiagnostics.ExpectedHintCandidate>> terminalHintByParser =
      new IdentityHashMap<>();
  /* Open TerminalSymbol frames, innermost first, so the deepest terminal hint needs no stack scan. */
  private final Deque<ParseFrame> terminalFrames = new ArrayDeque<>();

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

  /**
   * Rule-local diagnostics and direct transaction lifecycle replayed on a safe failure hit.
   *
   * <p>Only the innermost open frame is updated while parsing; when a frame is popped it is
   * merged into its parent (see {@link #discardMemoDiagnosticFrame}). The stack snapshots are
   * therefore kept absolute (root frame first) and the rule-local suffix is taken from
   * {@link #stackBaseDepth} when the frame is memoized or replayed. Because the merge is a
   * max-union whose order matches the contiguous interval a child occupies in its parent, the
   * result equals recording every event into every open frame.
   */
  public static final class FailureDiagnostic {
    int stackBaseDepth;
    int transactionBaseDepth;
    int farthestConsumedOffset;
    int farthestMatchedOffset;
    int maxReachedOffset;
    int farthestFailureOffset = -1;
    List<ParseFailureDiagnostics.ParseStackElement> maxReachedStackElements = Collections.emptyList();
    List<ParseFailureDiagnostics.ParseStackElement> farthestFailureStackElements = Collections.emptyList();
    final ExpectedSources expected = new ExpectedSources();
    final List<ParseFailureDiagnostics.TrialRecord> trials = new ArrayList<>();
    final List<MemoTransactionEvent> transactionEvents = new ArrayList<>();
  }

  /**
   * The parsers whose failure, and the innermost TerminalSymbols under which they failed, would
   * have contributed expected hints at a failure frontier. Kept in first-seen order without
   * duplicates. Expanding a source is deterministic (candidate lists are cached per parser), so
   * materializing hints from the sources on demand yields exactly the hint order that appending
   * every hint at failure time used to produce, at the cost of one identity-set insertion per
   * failure instead of one hash insertion per hint per open frame.
   */
  static final class ExpectedSources {
    private final List<Parser> parsers = new ArrayList<>();
    private final List<Boolean> terminal = new ArrayList<>();
    private final Set<Parser> failedSeen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Parser> terminalSeen = Collections.newSetFromMap(new IdentityHashMap<>());

    int size() {
      return parsers.size();
    }

    Parser parserAt(int index) {
      return parsers.get(index);
    }

    boolean isTerminalAt(int index) {
      return terminal.get(index);
    }

    void clear() {
      parsers.clear();
      terminal.clear();
      failedSeen.clear();
      terminalSeen.clear();
    }

    /** Records that {@code parser} failed at the frontier; its hint candidates are expanded later. */
    void addFailed(Parser parser) {
      if (failedSeen.add(parser)) {
        parsers.add(parser);
        terminal.add(Boolean.FALSE);
      }
    }

    /** Records the innermost open TerminalSymbol at a frontier failure. */
    void addTerminal(Parser parser) {
      if (terminalSeen.add(parser)) {
        parsers.add(parser);
        terminal.add(Boolean.TRUE);
      }
    }

    void addAll(ExpectedSources other) {
      for (int i = 0, n = other.parsers.size(); i < n; i++) {
        if (other.terminal.get(i)) addTerminal(other.parsers.get(i));
        else addFailed(other.parsers.get(i));
      }
    }

    void copyFrom(ExpectedSources other) {
      clear();
      addAll(other);
    }
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
    result.expected.copyFrom(expectedAtFarthestFailure);
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
    target.expected.copyFrom(source.expected);
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
    expectedAtFarthestFailure.copyFrom(source.expected);
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
    FailureDiagnostic parent = memoDiagnosticFrames.peekFirst();
    if (parent != null) mergeFrame(parent, frame);
  }

  /** Folds a popped child frame into its parent: max offsets, deeper stack on ties, sources, trials. */
  private static void mergeFrame(FailureDiagnostic parent, FailureDiagnostic child) {
    parent.farthestConsumedOffset = Math.max(parent.farthestConsumedOffset, child.farthestConsumedOffset);
    parent.farthestMatchedOffset = Math.max(parent.farthestMatchedOffset, child.farthestMatchedOffset);
    if (child.maxReachedOffset > parent.maxReachedOffset
        || child.maxReachedOffset == parent.maxReachedOffset
            && child.maxReachedStackElements.size() > parent.maxReachedStackElements.size()) {
      parent.maxReachedOffset = child.maxReachedOffset;
      parent.maxReachedStackElements = child.maxReachedStackElements;
    }
    if (child.farthestFailureOffset > parent.farthestFailureOffset) {
      parent.farthestFailureOffset = child.farthestFailureOffset;
      parent.farthestFailureStackElements = child.farthestFailureStackElements;
      parent.expected.clear();
      parent.expected.addAll(child.expected);
    } else if (child.farthestFailureOffset >= 0
        && child.farthestFailureOffset == parent.farthestFailureOffset) {
      if (child.farthestFailureStackElements.size() > parent.farthestFailureStackElements.size()) {
        parent.farthestFailureStackElements = child.farthestFailureStackElements;
      }
      parent.expected.addAll(child.expected);
    }
    parent.trials.addAll(child.trials);
  }

  /**
   * Merges a memoized rule failure into the global diagnostic and every open memo frame, as if
   * the rule had just failed here. Rebased stacks have a known size (current depth plus the
   * memoized local suffix), so they are only built when a merge below keeps them, and the
   * memoized expected lists are read in place instead of being copied per frame.
   */
  void replayFailureDiagnostic(FailureDiagnostic diagnostic) {
    int depth = parseFrames.size();
    int rebasedMaxSize = rebasedStackSize(diagnostic, diagnostic.maxReachedStackElements, depth);
    int rebasedFarthestSize = rebasedStackSize(diagnostic, diagnostic.farthestFailureStackElements, depth);
    List<ParseFailureDiagnostics.ParseStackElement> rebasedMax = null;
    List<ParseFailureDiagnostics.ParseStackElement> rebasedFarthest = null;

    farthestConsumedOffset = Math.max(farthestConsumedOffset, diagnostic.farthestConsumedOffset);
    farthestMatchedOffset = Math.max(farthestMatchedOffset, diagnostic.farthestMatchedOffset);
    if (diagnostic.maxReachedOffset > maxReachedOffset
        || diagnostic.maxReachedOffset == maxReachedOffset
            && rebasedMaxSize > maxReachedStackElements.size()) {
      maxReachedOffset = diagnostic.maxReachedOffset;
      if (rebasedMax == null) rebasedMax = rebaseMemoStack(diagnostic, diagnostic.maxReachedStackElements);
      maxReachedStackElements = rebasedMax;
    }
    if (diagnostic.farthestFailureOffset > farthestFailureOffset) {
      farthestFailureOffset = diagnostic.farthestFailureOffset;
      if (rebasedFarthest == null) rebasedFarthest = rebaseMemoStack(diagnostic, diagnostic.farthestFailureStackElements);
      farthestFailureStackElements = rebasedFarthest;
      expectedAtFarthestFailure.clear();
    }
    if (diagnostic.farthestFailureOffset == farthestFailureOffset) {
      if (rebasedFarthestSize > farthestFailureStackElements.size()) {
        if (rebasedFarthest == null) rebasedFarthest = rebaseMemoStack(diagnostic, diagnostic.farthestFailureStackElements);
        farthestFailureStackElements = rebasedFarthest;
      }
      expectedAtFarthestFailure.addAll(diagnostic.expected);
    }
    if (recordingTrials) trialHistory.addAll(diagnostic.trials);

    FailureDiagnostic active = memoDiagnosticFrames.peekFirst();
    if (active == null) return;
    active.farthestConsumedOffset = Math.max(active.farthestConsumedOffset, diagnostic.farthestConsumedOffset);
    active.farthestMatchedOffset = Math.max(active.farthestMatchedOffset, diagnostic.farthestMatchedOffset);
    if (diagnostic.maxReachedOffset > active.maxReachedOffset
        || diagnostic.maxReachedOffset == active.maxReachedOffset
            && rebasedMaxSize > active.maxReachedStackElements.size()) {
      active.maxReachedOffset = diagnostic.maxReachedOffset;
      if (rebasedMax == null) rebasedMax = rebaseMemoStack(diagnostic, diagnostic.maxReachedStackElements);
      active.maxReachedStackElements = rebasedMax;
    }
    if (diagnostic.farthestFailureOffset > active.farthestFailureOffset) {
      active.farthestFailureOffset = diagnostic.farthestFailureOffset;
      if (rebasedFarthest == null) rebasedFarthest = rebaseMemoStack(diagnostic, diagnostic.farthestFailureStackElements);
      active.farthestFailureStackElements = rebasedFarthest;
      active.expected.clear();
    }
    if (diagnostic.farthestFailureOffset == active.farthestFailureOffset) {
      if (rebasedFarthestSize > active.farthestFailureStackElements.size()) {
        if (rebasedFarthest == null) rebasedFarthest = rebaseMemoStack(diagnostic, diagnostic.farthestFailureStackElements);
        active.farthestFailureStackElements = rebasedFarthest;
      }
      active.expected.addAll(diagnostic.expected);
    }
    active.trials.addAll(diagnostic.trials);
  }

  /** Size {@link #rebaseMemoStack} would produce for a memoized absolute stack at {@code depth} open frames. */
  private static int rebasedStackSize(
      FailureDiagnostic memoized, List<ParseFailureDiagnostics.ParseStackElement> absolute, int depth) {
    int suffix = localSize(memoized, absolute.size());
    return suffix == 0 ? 0 : depth + suffix;
  }

  /** Number of elements above the frame's base in an absolute stack of {@code absoluteSize}. */
  private static int localSize(FailureDiagnostic diagnostic, int absoluteSize) {
    return absoluteSize - Math.min(diagnostic.stackBaseDepth, absoluteSize);
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
    if (memoDiagnosticFrames.isEmpty()) return;
    for (FailureDiagnostic diagnostic : memoDiagnosticFrames) {
      if (tokenStack.size() == diagnostic.transactionBaseDepth + 1) {
        diagnostic.transactionEvents.add(MemoTransactionEvent.BEGIN);
      }
    }
  }

  private void recordMemoTransactionFinish(MemoTransactionEvent event) {
    if (memoDiagnosticFrames.isEmpty()) return;
    for (FailureDiagnostic diagnostic : memoDiagnosticFrames) {
      if (tokenStack.size() == diagnostic.transactionBaseDepth) {
        diagnostic.transactionEvents.add(event);
      }
    }
  }

  /**
   * Places a memoized frame's rule-local stack suffix on top of the current parse stack, as if
   * the memoized rule had just failed here.
   */
  private List<ParseFailureDiagnostics.ParseStackElement> rebaseMemoStack(
      FailureDiagnostic memoized, List<ParseFailureDiagnostics.ParseStackElement> absolute) {
    List<ParseFailureDiagnostics.ParseStackElement> localSuffix = localStackSuffix(memoized, absolute);
    if (localSuffix.isEmpty()) return Collections.emptyList();
    List<ParseFailureDiagnostics.ParseStackElement> result = snapshotStackElements();
    for (ParseFailureDiagnostics.ParseStackElement element : localSuffix) {
      result.add(new ParseFailureDiagnostics.ParseStackElement(
          element.getParserClassName(), result.size(), element.getStartOffset(),
          element.getMaxConsumedOffset(), element.getMaxMatchedOffset()));
    }
    return result;
  }

  /** The part of an absolute stack that belongs to the frame's own rule (a view, not a copy). */
  private static List<ParseFailureDiagnostics.ParseStackElement> localStackSuffix(
      FailureDiagnostic diagnostic,
      List<ParseFailureDiagnostics.ParseStackElement> absoluteSnapshot) {
    int base = Math.min(diagnostic.stackBaseDepth, absoluteSnapshot.size());
    return absoluteSnapshot.subList(base, absoluteSnapshot.size());
  }

  /**
   * Expands hint sources into the ordered, de-duplicated lists reported by
   * {@link ParseFailureDiagnostics}: candidates in source order, then each new display hint.
   */
  private void materializeExpected(
      ExpectedSources sources,
      List<String> parsersOut,
      List<ParseFailureDiagnostics.ExpectedHintCandidate> hintsOut) {
    Set<String> hintKeys = new HashSet<>();
    Set<String> parserKeys = new HashSet<>();
    for (int i = 0, n = sources.size(); i < n; i++) {
      Parser parser = sources.parserAt(i);
      if (sources.isTerminalAt(i)) {
        java.util.Optional<ParseFailureDiagnostics.ExpectedHintCandidate> hint = terminalHintFor(parser);
        if (hint.isPresent()) addMaterialized(hint.get(), hintKeys, parserKeys, parsersOut, hintsOut);
      } else {
        for (ParseFailureDiagnostics.ExpectedHintCandidate hint : expectedHintCandidatesFor(parser)) {
          addMaterialized(hint, hintKeys, parserKeys, parsersOut, hintsOut);
        }
      }
    }
  }

  /** Display names a memo frame would report at its farthest failure (materialized on demand). */
  List<String> expectedParsersOf(FailureDiagnostic diagnostic) {
    List<String> parsers = new ArrayList<>();
    materializeExpected(diagnostic.expected, parsers, new ArrayList<>());
    return parsers;
  }

  private static void addMaterialized(
      ParseFailureDiagnostics.ExpectedHintCandidate candidate,
      Set<String> hintKeys,
      Set<String> parserKeys,
      List<String> parsersOut,
      List<ParseFailureDiagnostics.ExpectedHintCandidate> hintsOut) {
    String hint = candidate.getDisplayHint();
    if (hint == null || hint.isBlank()) {
      return;
    }
    if (false == hintKeys.add(candidate.dedupeKey())) {
      return;
    }
    hintsOut.add(candidate);
    if (parserKeys.add(hint)) {
      parsersOut.add(hint);
    }
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
    if (parser instanceof TerminalSymbol) terminalFrames.push(frame);
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
        FailureDiagnostic innermost = memoDiagnosticFrames.peekFirst();
        if (innermost != null) innermost.trials.add(trial);
      }
      parseFrames.pollFirst();
      if (frame.parser instanceof TerminalSymbol) terminalFrames.pollFirst();
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

    List<String> expectedParsers = new ArrayList<>();
    List<ParseFailureDiagnostics.ExpectedHintCandidate> expectedHints = new ArrayList<>();
    materializeExpected(expectedAtFarthestFailure, expectedParsers, expectedHints);

    // Compute expected tokens from trial history and hint candidates
    Set<String> expectedTokens = computeExpectedTokens(expectedParsers, expectedHints);

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
        expectedParsers,
        expectedHints,
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
    List<String> expectedParsers = new ArrayList<>();
    List<ParseFailureDiagnostics.ExpectedHintCandidate> expectedHints = new ArrayList<>();
    materializeExpected(expectedAtFarthestFailure, expectedParsers, expectedHints);
    return computeExpectedTokens(expectedParsers, expectedHints);
  }

  private Set<String> computeExpectedTokens(
      List<String> expectedParsers, List<ParseFailureDiagnostics.ExpectedHintCandidate> expectedHints) {
    Set<String> tokens = new HashSet<>();

    // Add from expected hint candidates (these come from TerminalSymbol analysis)
    for (ParseFailureDiagnostics.ExpectedHintCandidate candidate : expectedHints) {
      String hint = candidate.getDisplayHint();
      if (hint != null && !hint.isBlank()) {
        tokens.add(hint);
      }
    }

    // Add from the expected parser display names
    for (String parser : expectedParsers) {
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

  /*
   * Progress tracking runs on every startParse/endParse/consume. A parse stack snapshot has
   * exactly one element per open parse frame, so depth comparisons happen before a snapshot
   * is built, and one snapshot is shared by the global and memo-frame views. Iterating the
   * memo diagnostic frames allocates an iterator, so the loop is skipped while none are open.
   */
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
    int depth = parseFrames.size();
    List<ParseFailureDiagnostics.ParseStackElement> snapshot = null;
    if (reached > maxReachedOffset) {
      maxReachedOffset = reached;
      snapshot = snapshotStackElements();
      maxReachedStackElements = snapshot;
    } else if (reached == maxReachedOffset && depth > maxReachedStackElements.size()) {
      snapshot = snapshotStackElements();
      maxReachedStackElements = snapshot;
    }
    FailureDiagnostic diagnostic = memoDiagnosticFrames.peekFirst();
    if (diagnostic == null) {
      return;
    }
    diagnostic.farthestConsumedOffset = Math.max(diagnostic.farthestConsumedOffset, consumed);
    diagnostic.farthestMatchedOffset = Math.max(diagnostic.farthestMatchedOffset, matched);
    if (reached > diagnostic.maxReachedOffset) {
      if (snapshot == null) snapshot = snapshotStackElements();
      diagnostic.maxReachedOffset = reached;
      diagnostic.maxReachedStackElements = snapshot;
    } else if (reached == diagnostic.maxReachedOffset
        && depth > diagnostic.maxReachedStackElements.size()) {
      if (snapshot == null) snapshot = snapshotStackElements();
      diagnostic.maxReachedStackElements = snapshot;
    }
  }

  /**
   * Records a failed parse frame as an expected-token candidate. Hint collection and stack
   * snapshots are only built when the candidate can reach the global or a memo frame's
   * farthest failure; a failure behind every frontier changes nothing and allocates nothing.
   */
  void registerFailureCandidate(ParseFrame frame) {
    int candidateOffset = frame.maxOffset();
    FailureDiagnostic diagnostic = memoDiagnosticFrames.peekFirst();
    if (candidateOffset < farthestFailureOffset
        && (diagnostic == null || candidateOffset < diagnostic.farthestFailureOffset)) {
      return;
    }
    Parser terminalParser = deepestTerminalParser();
    int depth = parseFrames.size();
    List<ParseFailureDiagnostics.ParseStackElement> snapshot = null;
    if (candidateOffset > farthestFailureOffset) {
      farthestFailureOffset = candidateOffset;
      expectedAtFarthestFailure.clear();
      recordSources(expectedAtFarthestFailure, frame.parser, terminalParser);
      snapshot = snapshotStackElements();
      farthestFailureStackElements = snapshot;
    } else if (candidateOffset == farthestFailureOffset) {
      recordSources(expectedAtFarthestFailure, frame.parser, terminalParser);
      if (depth > farthestFailureStackElements.size()) {
        snapshot = snapshotStackElements();
        farthestFailureStackElements = snapshot;
      }
    }
    if (diagnostic == null) {
      return;
    }
    if (candidateOffset > diagnostic.farthestFailureOffset) {
      if (snapshot == null) snapshot = snapshotStackElements();
      diagnostic.farthestFailureOffset = candidateOffset;
      diagnostic.farthestFailureStackElements = snapshot;
      diagnostic.expected.clear();
    }
    if (candidateOffset == diagnostic.farthestFailureOffset) {
      if (depth > diagnostic.farthestFailureStackElements.size()) {
        if (snapshot == null) snapshot = snapshotStackElements();
        diagnostic.farthestFailureStackElements = snapshot;
      }
      recordSources(diagnostic.expected, frame.parser, terminalParser);
    }
  }

  /** The failed parser's candidates come first, then the innermost terminal's hint, as before. */
  private static void recordSources(ExpectedSources sources, Parser failed, Parser terminalParser) {
    sources.addFailed(failed);
    if (terminalParser != null) sources.addTerminal(terminalParser);
  }

  List<ParseFailureDiagnostics.ExpectedHintCandidate> expectedHintCandidatesFor(Parser parser) {
    List<ParseFailureDiagnostics.ExpectedHintCandidate> cached = hintCandidatesByParser.get(parser);
    if (cached != null) {
      return cached;
    }
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
    List<ParseFailureDiagnostics.ExpectedHintCandidate> result = Collections.unmodifiableList(
        new ArrayList<ParseFailureDiagnostics.ExpectedHintCandidate>(candidates.values()));
    hintCandidatesByParser.put(parser, result);
    return result;
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
    Parser parser = deepestTerminalParser();
    return parser == null ? java.util.Optional.empty() : terminalHintFor(parser);
  }

  /** The innermost open TerminalSymbol that has a non-blank display text, or null. */
  private Parser deepestTerminalParser() {
    if (terminalFrames.isEmpty()) {
      return null;
    }
    for (ParseFrame frame : terminalFrames) {
      if (terminalHintFor(frame.parser).isPresent()) {
        return frame.parser;
      }
    }
    return null;
  }

  /** The first non-blank display text of a TerminalSymbol as a depth-0 candidate, computed once. */
  private java.util.Optional<ParseFailureDiagnostics.ExpectedHintCandidate> terminalHintFor(Parser parser) {
    java.util.Optional<ParseFailureDiagnostics.ExpectedHintCandidate> cached = terminalHintByParser.get(parser);
    if (cached != null) {
      return cached;
    }
    java.util.Optional<ParseFailureDiagnostics.ExpectedHintCandidate> result = java.util.Optional.empty();
    for (String expected : ((TerminalSymbol) parser).expectedDisplayTexts()) {
      if (expected != null && false == expected.isBlank()) {
        result = java.util.Optional.of(newExpectedHintCandidate(parser, expected, 0));
        break;
      }
    }
    terminalHintByParser.put(parser, result);
    return result;
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
    return candidate.dedupeKey();
  }

  void addIfPresent(LinkedHashSet<String> hints, String hint) {
    if (hint == null || hint.isBlank()) {
      return;
    }
    hints.add(hint);
  }

  List<ParseFailureDiagnostics.ParseStackElement> snapshotStackElements() {
    List<ParseFailureDiagnostics.ParseStackElement> elements =
        new ArrayList<ParseFailureDiagnostics.ParseStackElement>(parseFrames.size());
    int depth = 0;
    // parseFrames pushes to the front, so the descending iterator yields the root frame first.
    for (Iterator<ParseFrame> frames = parseFrames.descendingIterator(); frames.hasNext();) {
      ParseFrame frame = frames.next();
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
