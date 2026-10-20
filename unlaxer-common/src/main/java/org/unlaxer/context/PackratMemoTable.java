package org.unlaxer.context;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.unlaxer.CodePointLength;
import org.unlaxer.Parsed;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.TokenList;
import org.unlaxer.context.Transaction.AdditionalCommitAction;
import org.unlaxer.listener.TransactionListener;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.ChoiceCommitAction;
import org.unlaxer.parser.combinator.ChoiceInterface;

/**
 * Opt-in packrat memoization table (issue #40).
 *
 * <p>Keyed by (parser identity, start consumed position, start matched position, tokenKind,
 * invertMatch, parser-visible state version, matched-cursor reset policy). Caches the outcome of parsing a rule at a position so that the exponential
 * re-parsing of the same sub-tree under backtracking ambiguity collapses to a single attempt.
 *
 * <p>One deliberately narrow flavour, gated by immutable parse options:
 * <ul>
 *   <li><b>safe failure memo</b> — only exact generated classes carrying
 *       {@link SafeFailureMemoizable} may cache a failure. The generated marker reflects a
 *       transitive, fail-closed grammar analysis. Classes also directly carrying
 *       {@link SafeSuccessMemoizable} may cache safe successes.</li>
 * </ul>
 *
 * <p>The table lives on a single {@link ParseContext} (one parse session) and is dropped when
 * the context closes. Memoization is off unless explicitly enabled, so default parsing — and
 * every existing test — is byte-for-byte unaffected.
 */
public final class PackratMemoTable {

  /** Position component of the memo key (parser identity is the outer map key). */
  public record PositionKey(
      int consumed, int matched, TokenKind tokenKind, boolean invertMatch, long stateVersion,
      boolean resetMatchedWithConsumed) {
    /** Compatibility constructor for the historical default reset policy. */
    public PositionKey(int consumed, int matched, TokenKind tokenKind,
        boolean invertMatch, long stateVersion) {
      this(consumed, matched, tokenKind, invertMatch, stateVersion, true);
    }
  }

  /** A rule outcome, including local diagnostics even when an alternative succeeded. */
  public static final class Entry {
    private final ParseContext.FailureDiagnostic diagnostic;
    private final TokenList tokens;
    private final int endConsumed;
    private final int endMatched;
    private final Parser chosenChild;
    private final Map<ChoiceInterface, Parser> choices;
    /*
     * Eviction index (#276 round 4). Every stored entry is also threaded onto a doubly linked
     * chain of the entries created at the same start position, so dropping a whole position is
     * O(entries at that position) instead of a scan of the table. Carrying the index links on the
     * entry itself rather than in separate index nodes keeps the put path allocation-free.
     */
    private Parser indexedParser;
    private PositionKey indexedKey;
    private Entry nextAtPosition;
    private Entry previousAtPosition;
    private long failureCharge;
    private long successCharge;
    private final long successPayloadCharge;
    private final Token.MemoSnapshot snapshot;
    // A failure entry distinguishes an ordinary failure (null) from a committed or labeled one.
    private RaisedFailure raised;

    private Entry(ParseContext.FailureDiagnostic diagnostic) {
      this(diagnostic, null);
    }

    private Entry(ParseContext.FailureDiagnostic diagnostic, RaisedFailure raised) {
      this(diagnostic, null, 0, 0, null, Map.of(), null, 0);
      this.raised = raised;
    }

    private Entry(ParseContext.FailureDiagnostic diagnostic, TokenList tokens,
        int endConsumed, int endMatched, Parser chosenChild, Map<ChoiceInterface, Parser> choices, Token.MemoSnapshot snapshot, long payloadCharge) {
      this.diagnostic = diagnostic;
      this.tokens = tokens;
      this.endConsumed = endConsumed;
      this.endMatched = endMatched;
      this.chosenChild = chosenChild;
      this.choices = choices;
      this.snapshot = snapshot;
      this.successPayloadCharge = payloadCharge;
    }
  }

  /**
   * The memoized outcome for {@code parser} at the current position, or null to proceed with a
   * normal parse (no entry, memoization disabled, or an exact class without the generated marker).
   * A non-null result must be returned through {@link #replay} by every parser entry point.
   */
  public static Entry lookup(
      ParseContext parseContext, Parser parser, TokenKind tokenKind, boolean invertMatch) {
    if (false == parseContext.isMemoizationSessionSafe()) {
      return null;
    }
    if (false == isExactSafeClass(parser)) return null;
    PackratMemoTable table = parseContext.getPackratMemoTable();
    PositionKey positionKey = positionKeyOf(parseContext, tokenKind, invertMatch);
    if (table.evictionEnabled && positionKey.consumed() < table.evictedBelow) {
      table.reportEvictionUnderrun(positionKey.consumed());
    }
    Entry entry = table.get(parser, positionKey);
    // A hit must have at least the resource budget available on the original
    // evaluation. Shallower retries keep their existing reuse and stack rebasing.
    if (entry != null && parseContext.activeParseDepth() > entry.diagnostic.memoParseDepth) {
      return null;
    }
    if (entry != null) {
      int lookback = table.highWater - positionKey.consumed();
      if (lookback > table.maxHitLookback) table.maxHitLookback = lookback;
      if (entry.tokens == null) {
        parseContext.replayMemoTransactionEvents(entry.diagnostic);
        table.failureHits++;
      } else {
        table.successHits++;
      }
      parseContext.replayFailureDiagnostic(entry.diagnostic);
    }
    return entry;
  }

  /**
   * Shared replay path for AbstractParser and the chain/choice combinators. Successes need no
   * stored state-hook replay: their dependency closure cannot mutate transactional state, and
   * the real begin/commit below checkpoints the owner once and records a balanced transaction
   * in enclosing memo frames. Replaying the old trace as well would duplicate those hooks.
   */
  public static Parsed replay(ParseContext context, Parser parser, TokenKind tokenKind,
      boolean invertMatch, Entry entry) {
    if (entry.tokens == null) {
      // Restores the failure kind and label exactly as the original evaluation raised them,
      // including "ordinary": a stale raised failure must not survive an ordinary memo hit.
      if (entry.raised != null) context.raise(entry.raised);
      else context.clearRaisedFailure();
      return Parsed.FAILED;
    }
    context.startParse(parser, context, tokenKind, invertMatch);
    if (parser instanceof org.unlaxer.parser.combinator.Occurs occurs
        && occurs.min() == 0 && occurs.max() == 0 && !occurs.isZeroMatchCheck())
      context.beginPreservingMatched(parser);
    else context.begin(parser);
    TokenList copies = java.util.Objects.requireNonNull(entry.snapshot.copy(), "immutable memo snapshot");
    context.getCurrent().getTokens().addAll(copies);
    int consumeDelta = entry.endConsumed - context.getConsumedPosition().value();
    // Even a zero-width consumed token can reset a previously advanced matched cursor.
    if (consumeDelta > 0 || context.getMatchedPosition().value() > entry.endMatched) {
      context.consume(new CodePointLength(consumeDelta));
    }
    int matchDelta = entry.endMatched - context.getMatchedPosition().value();
    if (matchDelta > 0) context.matchOnly(new CodePointLength(matchDelta));
    entry.choices.forEach(context::choose);
    Parsed parsed = new Parsed(context.commit(parser, tokenKind, choiceActions(entry.chosenChild)));
    context.endParse(parser, parsed, context, tokenKind, invertMatch);
    return parsed;
  }

  /** Capture the key before evaluation; failure-only rules do not need an extra start key. */
  public static PositionKey successKey(ParseContext context, Parser parser,
      TokenKind tokenKind, boolean invertMatch, ParseContext.FailureDiagnostic diagnostic) {
    return diagnostic != null && isExactSuccessSafeClass(parser)
        ? positionKeyOf(context, tokenKind, invertMatch) : null;
  }

  /**
   * Commit an ordinary success and retain its completed diagnostic frame. Capture a bounded
   * topology/value plan before collection reparents the tokens, then copy the CST only after
   * the final diagnostic fits the budget. Replay copies isolate later returned-tree mutations.
   * Choices keep their historical child Parsed on a miss.
   */
  public static Parsed commitSuccess(ParseContext context, Parser parser, TokenKind tokenKind,
      boolean invertMatch, PositionKey startKey, ParseContext.FailureDiagnostic diagnostic,
      Parser chosenChild, Parsed originalParsed) {
    Token.MemoSnapshot plan = null;
    Map<ChoiceInterface, Parser> choices = Map.of();
    long payloadCharge = 0;
    int endConsumed = context.getConsumedPosition().value();
    int endMatched = context.getMatchedPosition().value();
    if (startKey != null && context.isMemoizationSessionSafe()) {
      PackratMemoTable table = context.getPackratMemoTable();
      long room = table.successRoom(parser, startKey);
      int count = context.getCurrent().memoChoiceCount();
      long choiceCharge = count == 0 ? 0 : 64L + 64L * count;
      long diagnosticCharge = ParseContext.failureMemoCharge(diagnostic, room);
      plan = Token.prepareMemoSnapshot(context.getCurrent().getTokens(), room - diagnosticCharge - choiceCharge);
      if (plan == null) table.successBudgetMiss();
      else {
        payloadCharge = plan.retainedBytes() + choiceCharge;
        choices = context.getCurrent().snapshotChosenParsers(context.getChosenParserByChoice());
      }
    }
    var committed = context.commit(parser, tokenKind, choiceActions(chosenChild));
    Parsed parsed = originalParsed == null ? new Parsed(committed) : originalParsed;
    context.endParse(parser, parsed, context, tokenKind, invertMatch);
    if (diagnostic != null) context.discardMemoDiagnosticFrame(diagnostic);
    if (plan != null && context.isMemoizationSessionSafe()) {
      ParseContext.sealMemoDiagnostic(diagnostic);
      PackratMemoTable table = context.getPackratMemoTable();
      long room = table.successRoom(parser, startKey);
      // The final frame now includes commit/endParse diagnostics. Check it before CST copies.
      if (payloadCharge > room || ParseContext.failureMemoCharge(diagnostic, room - payloadCharge) > room - payloadCharge)
        table.successBudgetMiss();
      else {
        TokenList tokens = plan.freeze();
        table.put(parser, startKey, new Entry(diagnostic, tokens, endConsumed, endMatched,
            chosenChild, choices, plan, payloadCharge));
      }
    }
    return parsed;
  }

  private static final AdditionalCommitAction[] NO_ACTIONS = new AdditionalCommitAction[0];

  private static AdditionalCommitAction[] choiceActions(Parser chosenChild) {
    return chosenChild == null ? NO_ACTIONS
        : new AdditionalCommitAction[] {new ChoiceCommitAction(chosenChild)};
  }

  /** Records a generated, statically proven safe failure and its diagnostic contribution. */
  public static void memoizeFailure(
      ParseContext parseContext, Parser parser, TokenKind tokenKind, boolean invertMatch,
      ParseContext.FailureDiagnostic diagnostic) {
    if (diagnostic == null) return;
    parseContext.discardMemoDiagnosticFrame(diagnostic);
    if (false == parseContext.isMemoizationSessionSafe() || false == isExactSafeClass(parser)) return;
    ParseContext.sealMemoDiagnostic(diagnostic);
    parseContext.getPackratMemoTable().put(
        parser, positionKeyOf(parseContext, tokenKind, invertMatch),
        new Entry(diagnostic, parseContext.raisedFailure()));
  }

  /** Starts a local diagnostic frame only for an eligible exact safe rule. */
  public static ParseContext.FailureDiagnostic beginFailure(
      ParseContext parseContext, Parser parser) {
    if (false == parseContext.isMemoizationSessionSafe() || false == isExactSafeClass(parser)) return null;
    return parseContext.beginMemoDiagnosticFrame();
  }

  /** Discards the local diagnostic frame after a successful rule evaluation. */
  public static void memoizeSuccess(
      ParseContext parseContext, ParseContext.FailureDiagnostic diagnostic) {
    if (diagnostic != null) parseContext.discardMemoDiagnosticFrame(diagnostic);
  }

  /*
   * Class.getInterfaces() copies its array on every call and this check runs three times per
   * memoizable rule invocation, so the exact-class answer is cached per Class.
   */
  private static final ClassValue<Boolean> EXACT_SAFE_CLASS = new ClassValue<>() {
    @Override
    protected Boolean computeValue(Class<?> type) {
      for (Class<?> declared : type.getInterfaces()) {
        if (declared == SafeFailureMemoizable.class || declared == SafeSuccessMemoizable.class) {
          return Boolean.TRUE;
        }
      }
      return Boolean.FALSE;
    }
  };

  private static final ClassValue<Boolean> EXACT_SUCCESS_SAFE_CLASS = new ClassValue<>() {
    @Override
    protected Boolean computeValue(Class<?> type) {
      if (TransactionListener.class.isAssignableFrom(type)) return Boolean.FALSE;
      for (Class<?> declared : type.getInterfaces()) {
        if (declared == SafeSuccessMemoizable.class) return Boolean.TRUE;
      }
      return Boolean.FALSE;
    }
  };

  static boolean isExactSuccessSafeClass(Parser parser) {
    return EXACT_SUCCESS_SAFE_CLASS.get(parser.getClass());
  }

  static boolean isExactSafeClass(Parser parser) {
    return EXACT_SAFE_CLASS.get(parser.getClass());
  }

  public static PositionKey positionKeyOf(
      ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
    return new PositionKey(
        parseContext.getConsumedPosition().value(),
        parseContext.getMatchedPosition().value(),
        tokenKind, invertMatch, parseContext.getMemoizationStateVersion(),
        parseContext.getCurrent().isResetMatchedWithConsumed());
  }

  private final Map<Parser, Map<PositionKey, Entry>> entryByPositionByParser = new IdentityHashMap<>();

  private int failureHits;
  private int successHits;

  /*
   * Bounding the memo live set (#276 round 4).
   *
   * A memo entry keyed at a start position the parse can no longer re-enter is dead but
   * reachable, and at complex-x64 roughly 100k of them survive to the end of the parse and make
   * every GC cycle proportional to the input. The table therefore drops entries whose start
   * position falls more than {@link #window} code points behind the furthest position the cursor
   * has reached.
   *
   * Dropping is transparent by construction: lookup is a pure cache probe and a miss re-parses
   * the rule, replaying nothing. Only the hit rate is at stake, and that is guarded at runtime —
   * a probe below the watermark counts an under-run and widens the window to at least twice the
   * observed look-back, so a grammar that backtracks further than the window degrades to the
   * unbounded retention when necessary. Already evicted entries must be re-parsed;
   * this is not a guarantee of unchanged hit counts or linear time for every grammar.
   * See ParseContext#observeMemoCursor.
   */
  static final int DEFAULT_WINDOW = Integer.getInteger("unlaxer.memo.window", 1024);
  private static final int EVICT_STEP = 256;

  boolean evictionEnabled =
      false == "false".equals(System.getProperty("unlaxer.memo.evictBelowFrontier"));
  private Entry[] entryChainByPosition = new Entry[1024];
  private int window = DEFAULT_WINDOW;
  int highWater;
  int evictedBelow;
  private int entryCount;
  private int maxEntryCount;
  private int evictedCount;
  private int deadOnArrival;
  private int evictionUnderruns;
  private int maxHitLookback;

  private static final int DEFAULT_FAILURE_ENTRIES = 65_536;
  private static final long DEFAULT_FAILURE_BYTES = 32L * 1024 * 1024;
  private static final long DEFAULT_FAILURE_ENTRY_BYTES = 1024L * 1024;
  private static final int CONFIGURED_FAILURE_ENTRIES = (int) configuredFailureBudget(
      System.getProperty("unlaxer.memo.failure.maxEntries"), DEFAULT_FAILURE_ENTRIES, 1_048_576);
  private static final long CONFIGURED_FAILURE_BYTES = configuredFailureBudget(
      System.getProperty("unlaxer.memo.failure.maxBytes"), DEFAULT_FAILURE_BYTES, 1024L * 1024 * 1024);
  private static final long CONFIGURED_FAILURE_ENTRY_BYTES = configuredFailureBudget(
      System.getProperty("unlaxer.memo.failure.maxEntryBytes"), DEFAULT_FAILURE_ENTRY_BYTES, 64L * 1024 * 1024);
  private int maxFailureEntries = CONFIGURED_FAILURE_ENTRIES;
  private long maxFailureBytes = CONFIGURED_FAILURE_BYTES;
  private long maxFailureEntryBytes = CONFIGURED_FAILURE_ENTRY_BYTES;
  private int failureEntryCount;
  private long failureRetainedBytes;
  private long failureBudgetMisses;

  private static final int CONFIGURED_SUCCESS_ENTRIES = (int) configuredFailureBudget(
      System.getProperty("unlaxer.memo.success.maxEntries"), 65_536, 1_048_576);
  private static final long CONFIGURED_SUCCESS_BYTES = configuredFailureBudget(
      System.getProperty("unlaxer.memo.success.maxBytes"), 16L * 1024 * 1024, 1024L * 1024 * 1024);
  private static final long CONFIGURED_SUCCESS_ENTRY_BYTES = configuredFailureBudget(
      System.getProperty("unlaxer.memo.success.maxEntryBytes"), 2L * 1024 * 1024, 64L * 1024 * 1024);
  private int maxSuccessEntries = CONFIGURED_SUCCESS_ENTRIES;
  private long maxSuccessBytes = CONFIGURED_SUCCESS_BYTES;
  private long maxSuccessEntryBytes = CONFIGURED_SUCCESS_ENTRY_BYTES;
  private int successEntryCount;
  private long successRetainedBytes;
  private long successBudgetMisses;

  void setSuccessBudget(int entries, long bytes, long entryBytes) {
    if (successEntryCount != 0 || entries < 0 || entries > 1_048_576 || bytes < 0
        || bytes > 1024L * 1024 * 1024 || entryBytes < 0 || entryBytes > 64L * 1024 * 1024)
      throw new IllegalArgumentException("invalid success memo budget or nonempty cache");
    maxSuccessEntries = entries; maxSuccessBytes = bytes; maxSuccessEntryBytes = entryBytes;
  }

  /** Capacity available before allocating a successful CST copy. */
  private long successRoom(Parser parser, PositionKey key) {
    Entry old = get(parser, key);
    int count = successEntryCount - (old != null && old.tokens != null ? 1 : 0);
    if (evictionEnabled && key.consumed() < evictedBelow) { deadOnArrival++; return 0; }
    if (count >= maxSuccessEntries) return 0;
    long retained = successRetainedBytes - (old == null ? 0 : old.successCharge);
    if (evictionEnabled && key.consumed() >= entryChainByPosition.length) {
      long next = entryChainByPosition.length;
      while (key.consumed() >= next && next < Integer.MAX_VALUE - 8L)
        next = Math.min(Integer.MAX_VALUE - 8L, next * 2);
      if (key.consumed() >= next || 8L * next > maxSuccessBytes) return 0;
    }
    return Math.min(maxSuccessEntryBytes, maxSuccessBytes - retained);
  }

  static long configuredFailureBudget(String value, long fallback, long ceiling) {
    if (value == null || value.isEmpty()) return fallback;
    for (int i = 0; i < value.length(); i++)
      if (value.charAt(i) < '0' || value.charAt(i) > '9') return fallback;
    try {
      long parsed = Long.parseLong(value);
      return parsed >= 0 && parsed <= ceiling ? parsed : fallback;
    } catch (NumberFormatException invalid) { return fallback; }
  }

  /** Test seam; production uses finite process-level system-property defaults. */
  void setFailureBudget(int entries, long bytes, long entryBytes) {
    if (failureEntryCount != 0 || entries < 0 || entries > 1_048_576 || bytes < 0
        || bytes > 1024L * 1024 * 1024 || entryBytes < 0 || entryBytes > 64L * 1024 * 1024)
      throw new IllegalArgumentException("invalid failure memo budget or nonempty cache");
    maxFailureEntries = entries;
    maxFailureBytes = bytes;
    maxFailureEntryBytes = entryBytes;
  }


  public Entry get(Parser parser, PositionKey positionKey) {
    Map<PositionKey, Entry> entryByPosition = entryByPositionByParser.get(parser);
    if (entryByPosition == null) {
      return null;
    }
    return entryByPosition.get(positionKey);
  }

  public void put(Parser parser, PositionKey positionKey, Entry entry) {
    int position = positionKey.consumed();
    if (evictionEnabled && position < evictedBelow) {
      deadOnArrival++;
      return;
    }
    Map<PositionKey, Entry> byKey = entryByPositionByParser.get(parser);
    Entry old = byKey == null ? null : byKey.get(positionKey);
    if (old == entry) return;
    boolean failure = entry.tokens == null;
    long charge = 0;
    int indexLength = entryChainByPosition.length;
    if (evictionEnabled && position >= indexLength) {
      long next = indexLength;
      while (position >= next && next < Integer.MAX_VALUE - 8L)
        next = Math.min(Integer.MAX_VALUE - 8L, next * 2);
      if (position >= next) { if (failure) budgetMiss(); else successBudgetMiss(); return; }
      indexLength = (int) next;
    }
    if (failure) {
      charge = ParseContext.failureMemoCharge(entry.diagnostic, maxFailureEntryBytes);
      int count = failureEntryCount - (old != null && old.tokens == null ? 1 : 0);
      long retained = failureRetainedBytes - (old == null ? 0 : old.failureCharge);
      if (charge > maxFailureEntryBytes || charge > maxFailureBytes - retained
          || count >= maxFailureEntries
          // The shared position index also has a finite failure-driven growth ceiling.
          || indexLength > entryChainByPosition.length && 8L * indexLength > maxFailureBytes) {
        budgetMiss();
        return;
      }
    }
    if (!failure) {
      if (entry.snapshot == null || entry.successPayloadCharge > maxSuccessEntryBytes) {
        successBudgetMiss(); return;
      }
      long diagnosticLimit = maxSuccessEntryBytes - entry.successPayloadCharge;
      long diagnosticCharge = ParseContext.failureMemoCharge(entry.diagnostic, diagnosticLimit);
      charge = entry.successPayloadCharge + diagnosticCharge;
      int count = successEntryCount - (old != null && old.tokens != null ? 1 : 0);
      long retained = successRetainedBytes - (old == null ? 0 : old.successCharge);
      if (charge > maxSuccessEntryBytes || charge > maxSuccessBytes - retained
          || count >= maxSuccessEntries
          || indexLength > entryChainByPosition.length && 8L * indexLength > maxSuccessBytes) {
        successBudgetMiss(); return;
      }
    }
    // The public put surface may receive an already indexed outcome. Share its
    // immutable payload, never its intrusive index links, with another key.
    if (entry.indexedKey != null) {
      RaisedFailure raised = entry.raised;
      entry = new Entry(entry.diagnostic, entry.tokens, entry.endConsumed, entry.endMatched,
          entry.chosenChild, entry.choices, entry.snapshot, entry.successPayloadCharge);
      entry.raised = raised;
    }
    if (indexLength > entryChainByPosition.length)
      entryChainByPosition = java.util.Arrays.copyOf(entryChainByPosition, indexLength);
    if (old != null) {
      unlink(old);
      if (old.tokens == null) {
        failureEntryCount--;
        failureRetainedBytes -= old.failureCharge;
      } else {
        successEntryCount--; successRetainedBytes -= old.successCharge;
      }
    } else {
      entryCount++;
      if (entryCount > maxEntryCount) maxEntryCount = entryCount;
    }
    if (byKey == null) {
      byKey = new HashMap<>();
      entryByPositionByParser.put(parser, byKey);
    }
    byKey.put(positionKey, entry);
    entry.failureCharge = failure ? charge : 0;
    entry.successCharge = failure ? 0 : charge;
    if (failure) {
      failureEntryCount++;
      failureRetainedBytes += charge;
    } else {
      successEntryCount++; successRetainedBytes += charge;
    }
    if (!evictionEnabled) return;
    entry.indexedParser = parser;
    entry.indexedKey = positionKey;
    entry.nextAtPosition = entryChainByPosition[position];
    if (entry.nextAtPosition != null) entry.nextAtPosition.previousAtPosition = entry;
    entryChainByPosition[position] = entry;
  }

  private void successBudgetMiss() {
    if (successBudgetMisses != Long.MAX_VALUE) successBudgetMisses++;
  }

  private void budgetMiss() {
    if (failureBudgetMisses != Long.MAX_VALUE) failureBudgetMisses++;
  }

  /** Replacement must not leave the old payload reachable through the eviction index. */
  private void unlink(Entry entry) {
    if (entry.indexedKey == null) return;
    Entry previous = entry.previousAtPosition, next = entry.nextAtPosition;
    if (previous == null) entryChainByPosition[entry.indexedKey.consumed()] = next;
    else previous.nextAtPosition = next;
    if (next != null) next.previousAtPosition = previous;
    entry.previousAtPosition = null;
    entry.nextAtPosition = null;
    entry.indexedParser = null;
    entry.indexedKey = null;
  }

  /**
   * Advances the retained window. Called once per transaction begin with that transaction's start
   * cursor; the scan visits each position once and unlinks each entry once over the whole parse,
   * so eviction stays O(input + entries) rather than a table scan per commit.
   */
  void observeCursor(int cursor) {
    if (cursor > highWater) highWater = cursor;
    if (false == evictionEnabled) return;
    int target = highWater - window;
    if (target - evictedBelow >= EVICT_STEP) evictBelow(target);
  }

  private void evictBelow(int watermark) {
    int limit = Math.min(watermark, entryChainByPosition.length);
    for (int position = evictedBelow; position < limit; position++) {
      Entry entry = entryChainByPosition[position];
      entryChainByPosition[position] = null;
      while (entry != null) {
        Entry next = entry.nextAtPosition;
        entry.nextAtPosition = null;
        entry.previousAtPosition = null;
        Map<PositionKey, Entry> byKey = entryByPositionByParser.get(entry.indexedParser);
        if (byKey != null && byKey.remove(entry.indexedKey, entry)) {
          entryCount--;
          if (entry.tokens == null) {
            failureEntryCount--;
            failureRetainedBytes -= entry.failureCharge;
          } else {
            successEntryCount--; successRetainedBytes -= entry.successCharge;
          }
          evictedCount++;
          if (byKey.isEmpty()) entryByPositionByParser.remove(entry.indexedParser);
        }
        entry.indexedParser = null;
        entry.indexedKey = null;
        entry = next;
      }
    }
    evictedBelow = watermark;
  }

  /**
   * A probe arrived below the watermark, so the window was narrower than this grammar's
   * look-back. Widen it past twice the observed distance; the window only grows, so the parse
   * pays at most a geometric series of re-parses before it stops evicting what it still needs.
   */
  private void reportEvictionUnderrun(int position) {
    evictionUnderruns++;
    int lookback = highWater - position;
    window = (int) Math.min(Integer.MAX_VALUE, 2L * Math.max(window, lookback));
    // Lost entries cannot be recovered, but their positions must accept new
    // entries again. Otherwise every retry below the old watermark keeps missing.
    evictedBelow = Math.max(0, highWater - window);
  }

  /** Test seam: turning eviction off reproduces the pre-#276 table exactly. */
  void setEvictionEnabled(boolean enabled) {
    evictionEnabled = enabled;
  }

  /** Test seam for exercising the guard without a multi-megabyte fixture. */
  void setWindow(int positions) {
    window = positions;
  }

  /** Failure payload charge retained by this cache, separate from successes and process RSS. */
  public long failureRetainedBytes() { return failureRetainedBytes; }
  public int failureEntryCount() { return failureEntryCount; }
  public long failureBudgetMisses() { return failureBudgetMisses; }

  /** Successful snapshot charge; external shared sources/host object graphs are excluded. */
  public long successRetainedBytes() { return successRetainedBytes; }
  public int successEntryCount() { return successEntryCount; }
  public long successBudgetMisses() { return successBudgetMisses; }

  public int failureHits() { return failureHits; }
  public int successHits() { return successHits; }
  /** Entries currently held; with eviction on this plateaus instead of growing with the input. */
  public int entryCount() { return entryCount; }
  /** High-water mark of {@link #entryCount()} — the live memo set this parse ever held. */
  public int maxEntryCount() { return maxEntryCount; }
  /** Entries dropped because the watermark passed their start position. */
  public int evictedCount() { return evictedCount; }
  /** Rules memoized at a start position the watermark had already passed; never stored. */
  public int deadOnArrival() { return deadOnArrival; }
  /** Probes below the watermark. Zero means no hit was lost; each one widens the window. */
  public int evictionUnderruns() { return evictionUnderruns; }
  /** Furthest a hit ever reached back from the cursor high-water mark: the look-back to cover. */
  public int maxHitLookback() { return maxHitLookback; }
  public int watermark() { return evictedBelow; }
  /** Furthest consumed position any transaction has started at during this parse. */
  public int cursorHighWater() { return highWater; }
  public int window() { return window; }
}
