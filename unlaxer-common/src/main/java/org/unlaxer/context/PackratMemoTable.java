package org.unlaxer.context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.unlaxer.TokenKind;
import org.unlaxer.parser.Parser;

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
 *       transitive, fail-closed grammar analysis. Successes are never cached.</li>
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

  /** A known failure together with the rule-local diagnostics produced by the original call. */
  public static final class Entry {
    private final ParseContext.FailureDiagnostic diagnostic;

    private Entry(ParseContext.FailureDiagnostic diagnostic) { this.diagnostic = diagnostic; }
  }

  /**
   * The memoized outcome for {@code parser} at the current position, or null to proceed with a
   * normal parse (no entry, memoization disabled, or an exact class without the generated marker).
   * A non-null result is a known failure whose diagnostic contribution has already been replayed.
   */
  public static Entry lookup(
      ParseContext parseContext, Parser parser, TokenKind tokenKind, boolean invertMatch) {
    if (false == parseContext.isMemoizationSessionSafe()) {
      return null;
    }
    if (false == isExactSafeClass(parser)) return null;
    Entry entry = parseContext.getPackratMemoTable()
        .get(parser, positionKeyOf(parseContext, tokenKind, invertMatch));
    if (entry != null) {
      parseContext.replayMemoTransactionEvents(entry.diagnostic);
      parseContext.replayFailureDiagnostic(entry.diagnostic);
      parseContext.getPackratMemoTable().failureHits++;
    }
    return entry;
  }

  /** Records a generated, statically proven safe failure and its diagnostic contribution. */
  public static void memoizeFailure(
      ParseContext parseContext, Parser parser, TokenKind tokenKind, boolean invertMatch,
      ParseContext.FailureDiagnostic diagnostic) {
    if (diagnostic == null) return;
    parseContext.discardMemoDiagnosticFrame(diagnostic);
    if (false == parseContext.isMemoizationSessionSafe() || false == isExactSafeClass(parser)) return;
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
        if (declared == SafeFailureMemoizable.class) return Boolean.TRUE;
      }
      return Boolean.FALSE;
    }
  };

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

  /**
   * One parser's entries, split by consumed-position block. A long input used to grow one
   * HashMap per parser to hundreds of thousands of entries, so every lookup, insert and rehash
   * missed the cache and parse time grew faster than the input. Blocks of 256 positions keep
   * each table small; the key, the entries and the hit semantics are unchanged (#252, the Java
   * counterpart of the Rust runtime's failure memo buckets in #245).
   */
  private static final class PositionBuckets {
    private static final int BLOCK_SHIFT = 8;

    private final ArrayList<HashMap<PositionKey, Entry>> blocks = new ArrayList<>();

    Entry get(PositionKey positionKey) {
      int block = positionKey.consumed() >>> BLOCK_SHIFT;
      if (block >= blocks.size()) {
        return null;
      }
      HashMap<PositionKey, Entry> entries = blocks.get(block);
      return entries == null ? null : entries.get(positionKey);
    }

    void put(PositionKey positionKey, Entry entry) {
      int block = positionKey.consumed() >>> BLOCK_SHIFT;
      while (blocks.size() <= block) {
        blocks.add(null);
      }
      HashMap<PositionKey, Entry> entries = blocks.get(block);
      if (entries == null) {
        entries = new HashMap<>();
        blocks.set(block, entries);
      }
      entries.put(positionKey, entry);
    }
  }

  private final Map<Parser, PositionBuckets> entryByPositionByParser = new IdentityHashMap<>();

  private int failureHits;

  public Entry get(Parser parser, PositionKey positionKey) {
    PositionBuckets buckets = entryByPositionByParser.get(parser);
    if (buckets == null) {
      return null;
    }
    return buckets.get(positionKey);
  }

  public void put(Parser parser, PositionKey positionKey, Entry entry) {
    entryByPositionByParser
        .computeIfAbsent(parser, ignored -> new PositionBuckets())
        .put(positionKey, entry);
  }

  public int failureHits() { return failureHits; }
}
