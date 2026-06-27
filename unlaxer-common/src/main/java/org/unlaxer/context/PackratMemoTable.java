package org.unlaxer.context;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.unlaxer.TokenKind;
import org.unlaxer.TokenList;
import org.unlaxer.listener.TransactionListener;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

/**
 * Opt-in packrat memoization table (issue #40).
 *
 * <p>Keyed by (parser identity, start consumed position, start matched position, tokenKind,
 * invertMatch). Caches the outcome of parsing a rule at a position so that the exponential
 * re-parsing of the same sub-tree under backtracking ambiguity collapses to a single attempt.
 *
 * <p>Two flavours, both gated by the caller:
 * <ul>
 *   <li><b>failure memo</b> — "rule R failed at position X". Always safe: a failed parse is
 *       rolled back (net-zero scope effect), and for the grammars this targets a rule's
 *       success/failure at a position is a pure function of the source. Returned immediately
 *       on a hit, skipping the whole sub-tree.</li>
 *   <li><b>success memo</b> — the rule's tokens plus end positions, used to replay a success
 *       without re-deriving the sub-tree. Only safe when the sub-tree mutates no persistent
 *       parse state (scope tree / declarations); {@link #isSuccessMemoizable(Parser)} enforces
 *       this by excluding any sub-tree that contains a {@link TransactionListener}.</li>
 * </ul>
 *
 * <p>The table lives on a single {@link ParseContext} (one parse session) and is dropped when
 * the context closes. Memoization is off unless explicitly enabled, so default parsing — and
 * every existing test — is byte-for-byte unaffected.
 */
public final class PackratMemoTable {

  /** Position component of the memo key (parser identity is the outer map key). */
  public record PositionKey(int consumed, int matched, TokenKind tokenKind, boolean invertMatch) {}

  /** A memoized outcome. {@link #FAILED} marks a known failure; success carries replay data. */
  public static final class Entry {

    public static final Entry FAILED = new Entry(true, null, 0, 0);

    private final boolean failed;
    private final TokenList tokens;
    private final int endConsumed;
    private final int endMatched;

    private Entry(boolean failed, TokenList tokens, int endConsumed, int endMatched) {
      this.failed = failed;
      this.tokens = tokens;
      this.endConsumed = endConsumed;
      this.endMatched = endMatched;
    }

    public static Entry success(TokenList tokens, int endConsumed, int endMatched) {
      return new Entry(false, tokens, endConsumed, endMatched);
    }

    public boolean isFailed() {
      return failed;
    }

    public TokenList tokens() {
      return tokens;
    }

    public int endConsumed() {
      return endConsumed;
    }

    public int endMatched() {
      return endMatched;
    }
  }

  /**
   * True when {@code parser} has already failed at the current position (a known dead end), so a
   * retry can short-circuit instead of re-deriving the whole sub-tree. No-op (returns false) when
   * memoization is disabled, so default parsing is unaffected.
   */
  public static boolean isMemoizedFailure(
      ParseContext parseContext, Parser parser, TokenKind tokenKind, boolean invertMatch) {
    if (false == parseContext.isMemoizeEnabled()) {
      return false;
    }
    Entry entry = parseContext.getPackratMemoTable()
        .get(parser, positionKeyOf(parseContext, tokenKind, invertMatch));
    return entry != null && entry.isFailed();
  }

  /**
   * Record that {@code parser} failed at the current position. A {@link TransactionListener}'s
   * outcome can depend on mutable scope state, so its failure is never memoized; every other
   * rule's failure at a fixed position is a pure function of the source. No-op when memoization
   * is disabled.
   */
  public static void memoizeFailure(
      ParseContext parseContext, Parser parser, TokenKind tokenKind, boolean invertMatch) {
    if (false == parseContext.isMemoizeEnabled()) {
      return;
    }
    if (parser instanceof TransactionListener) {
      return;
    }
    parseContext.getPackratMemoTable()
        .put(parser, positionKeyOf(parseContext, tokenKind, invertMatch), Entry.FAILED);
  }

  public static PositionKey positionKeyOf(
      ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
    return new PositionKey(
        parseContext.getConsumedPosition().value(),
        parseContext.getMatchedPosition().value(),
        tokenKind, invertMatch);
  }

  private final Map<Parser, Map<PositionKey, Entry>> entryByPositionByParser = new IdentityHashMap<>();

  private final Map<Parser, Boolean> successMemoizableByParser = new IdentityHashMap<>();

  public Entry get(Parser parser, PositionKey positionKey) {
    Map<PositionKey, Entry> entryByPosition = entryByPositionByParser.get(parser);
    if (entryByPosition == null) {
      return null;
    }
    return entryByPosition.get(positionKey);
  }

  public void put(Parser parser, PositionKey positionKey, Entry entry) {
    entryByPositionByParser
        .computeIfAbsent(parser, ignored -> new HashMap<>())
        .put(positionKey, entry);
  }

  /**
   * True when serving {@code parser} from a success memo (skipping its sub-tree) would not drop
   * any persistent parse-state mutation. A sub-tree containing a {@link TransactionListener}
   * (scope tree / declarations / back-reference resolution is emitted as such a listener) is
   * conservatively treated as not success-memoizable. Computed once per parser and cached;
   * cycle-safe for recursive grammars.
   */
  public boolean isSuccessMemoizable(Parser parser) {
    Boolean cached = successMemoizableByParser.get(parser);
    if (cached != null) {
      return cached;
    }
    // Pre-seed with true to break cycles: a back-edge to this parser must not, by itself,
    // make it unsafe — only a TransactionListener anywhere in the sub-tree does.
    successMemoizableByParser.put(parser, Boolean.TRUE);
    boolean memoizable = subTreeHasNoTransactionListener(parser, new IdentityHashMap<>());
    successMemoizableByParser.put(parser, memoizable);
    return memoizable;
  }

  private boolean subTreeHasNoTransactionListener(Parser parser, Map<Parser, Boolean> visited) {
    if (parser == null || visited.put(parser, Boolean.TRUE) != null) {
      return true;
    }
    if (parser instanceof TransactionListener) {
      return false;
    }
    Parsers children = parser.getChildren();
    if (children != null) {
      for (Parser child : children) {
        if (false == subTreeHasNoTransactionListener(child, visited)) {
          return false;
        }
      }
    }
    return true;
  }
}
