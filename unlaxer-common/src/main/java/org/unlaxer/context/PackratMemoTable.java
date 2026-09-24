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
 * invertMatch, parser-visible state version). Caches the outcome of parsing a rule at a position so that the exponential
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
      int consumed, int matched, TokenKind tokenKind, boolean invertMatch, long stateVersion) {}

  /** A rule outcome, including local diagnostics even when an alternative succeeded. */
  public static final class Entry {
    private final ParseContext.FailureDiagnostic diagnostic;
    private final TokenList tokens;
    private final int endConsumed;
    private final int endMatched;
    private final Parser chosenChild;
    private final Map<ChoiceInterface, Parser> choices;
    /*
     * Eviction index (#276 round 4). Every stored entry is also threaded onto a singly linked
     * chain of the entries created at the same start position, so dropping a whole position is
     * O(entries at that position) instead of a scan of the table. Carrying the three links on the
     * entry itself rather than in separate index nodes keeps the put path allocation-free.
     */
    private Parser indexedParser;
    private PositionKey indexedKey;
    private Entry nextAtPosition;

    private Entry(ParseContext.FailureDiagnostic diagnostic) {
      this(diagnostic, null, 0, 0, null, Map.of());
    }

    private Entry(ParseContext.FailureDiagnostic diagnostic, TokenList tokens,
        int endConsumed, int endMatched, Parser chosenChild, Map<ChoiceInterface, Parser> choices) {
      this.diagnostic = diagnostic;
      this.tokens = tokens;
      this.endConsumed = endConsumed;
      this.endMatched = endMatched;
      this.chosenChild = chosenChild;
      this.choices = choices;
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
    if (entry.tokens == null) return Parsed.FAILED;
    context.startParse(parser, context, tokenKind, invertMatch);
    context.begin(parser);
    for (Token token : entry.tokens) context.getCurrent().getTokens().add(token.deepCopy());
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
   * Commit an ordinary success and retain its completed diagnostic frame. Snapshot tokens BEFORE
   * collection reparents them; copy deeply both here and on replay so later mutations of a
   * returned tree cannot modify the cache. Choices keep their historical child Parsed on a miss.
   */
  public static Parsed commitSuccess(ParseContext context, Parser parser, TokenKind tokenKind,
      boolean invertMatch, PositionKey startKey, ParseContext.FailureDiagnostic diagnostic,
      Parser chosenChild, Parsed originalParsed) {
    Entry entry = null;
    if (startKey != null && context.isMemoizationSessionSafe()) {
      TokenList snapshot = new TokenList();
      for (Token token : context.getCurrent().getTokens()) snapshot.add(token.deepCopy());
      entry = new Entry(diagnostic, snapshot, context.getConsumedPosition().value(),
          context.getMatchedPosition().value(), chosenChild,
          context.getCurrent().snapshotChosenParsers(context.getChosenParserByChoice()));
    }
    var committed = context.commit(parser, tokenKind, choiceActions(chosenChild));
    Parsed parsed = originalParsed == null ? new Parsed(committed) : originalParsed;
    context.endParse(parser, parsed, context, tokenKind, invertMatch);
    if (diagnostic != null) context.discardMemoDiagnosticFrame(diagnostic);
    if (entry != null && context.isMemoizationSessionSafe()) {
      ParseContext.sealMemoDiagnostic(diagnostic);
      context.getPackratMemoTable().put(parser, startKey, entry);
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
        parser, positionKeyOf(parseContext, tokenKind, invertMatch), new Entry(diagnostic));
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
        tokenKind, invertMatch, parseContext.getMemoizationStateVersion());
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
   * previous behaviour instead of silently losing hits. See ParseContext#observeMemoCursor.
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
      // The watermark already passed this rule's start, so nothing can probe it again.
      deadOnArrival++;
      return;
    }
    entryByPositionByParser
        .computeIfAbsent(parser, ignored -> new HashMap<>())
        .put(positionKey, entry);
    entryCount++;
    if (entryCount > maxEntryCount) maxEntryCount = entryCount;
    if (false == evictionEnabled) return;
    if (position >= entryChainByPosition.length) {
      int length = entryChainByPosition.length;
      while (position >= length) length *= 2;
      entryChainByPosition = java.util.Arrays.copyOf(entryChainByPosition, length);
    }
    entry.indexedParser = parser;
    entry.indexedKey = positionKey;
    entry.nextAtPosition = entryChainByPosition[position];
    entryChainByPosition[position] = entry;
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
        Map<PositionKey, Entry> byKey = entryByPositionByParser.get(entry.indexedParser);
        if (byKey != null && byKey.remove(entry.indexedKey, entry)) {
          entryCount--;
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
    int widened = Math.max(window * 2, lookback * 2);
    window = widened <= 0 ? Integer.MAX_VALUE : widened;
  }

  /** Test seam: turning eviction off reproduces the pre-#276 table exactly. */
  void setEvictionEnabled(boolean enabled) {
    evictionEnabled = enabled;
  }

  /** Test seam for exercising the guard without a multi-megabyte fixture. */
  void setWindow(int positions) {
    window = positions;
  }

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
