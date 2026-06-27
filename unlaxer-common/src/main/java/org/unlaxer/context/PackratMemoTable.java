package org.unlaxer.context;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.unlaxer.CodePointLength;
import org.unlaxer.Parsed;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.TokenList;
import org.unlaxer.TransactionElement;
import org.unlaxer.context.Transaction.AdditionalCommitAction;
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

    public static final Entry FAILED = new Entry(true, null, 0, 0, null);

    private final boolean failed;
    private final TokenList tokens;
    private final int endConsumed;
    private final int endMatched;
    private final Parser chosenChild;

    private Entry(boolean failed, TokenList tokens, int endConsumed, int endMatched, Parser chosenChild) {
      this.failed = failed;
      this.tokens = tokens;
      this.endConsumed = endConsumed;
      this.endMatched = endMatched;
      this.chosenChild = chosenChild;
    }

    /**
     * @param chosenChild for a choice rule, the alternative that matched (needed to replay
     *     {@code ChoiceCommitAction}); null for chains and plain rules.
     */
    public static Entry success(TokenList tokens, int endConsumed, int endMatched, Parser chosenChild) {
      return new Entry(false, tokens, endConsumed, endMatched, chosenChild);
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

    public Parser chosenChild() {
      return chosenChild;
    }
  }

  /**
   * The memoized outcome for {@code parser} at the current position, or null to proceed with a
   * normal parse (no entry, or memoization disabled). A non-null result is either a known failure
   * ({@link Entry#isFailed()}) or a success to replay via {@link #replaySuccess}.
   */
  public static Entry lookup(
      ParseContext parseContext, Parser parser, TokenKind tokenKind, boolean invertMatch) {
    if (false == parseContext.isMemoizeEnabled()) {
      return null;
    }
    return parseContext.getPackratMemoTable()
        .get(parser, positionKeyOf(parseContext, tokenKind, invertMatch));
  }

  /**
   * Record that {@code parser} succeeded at {@code startKey} consuming up to (endConsumed,
   * endMatched), capturing a snapshot of its tokens for replay. Only stored for success-memoizable
   * parsers (see {@link #isSuccessMemoizable}); no-op otherwise or when memoization is disabled.
   * {@code rawTokens} is the rule's transaction-element token list captured BEFORE commit.
   */
  public static void memoizeSuccess(ParseContext parseContext, Parser parser, PositionKey startKey,
      TokenList rawTokens, int endConsumed, int endMatched, Parser chosenChild) {
    if (false == parseContext.isMemoizeEnabled()) {
      return;
    }
    PackratMemoTable table = parseContext.getPackratMemoTable();
    if (false == table.isSuccessMemoizable(parser)) {
      return;
    }
    TokenList snapshot = new TokenList();
    snapshot.addAll(rawTokens);
    table.put(parser, startKey, Entry.success(snapshot, endConsumed, endMatched, chosenChild));
  }

  /**
   * Replay a memoized success without re-deriving the sub-tree: open a transaction for the rule,
   * splice in DEEP COPIES of the cached tokens (their mutable parent pointers must not be shared —
   * see {@link Token#deepCopy()}), advance the cursor to the cached end, then commit so the normal
   * merge/collect/cursor-propagation and any commit actions run exactly as on a fresh parse.
   */
  public static Parsed replaySuccess(ParseContext parseContext, Parser parser, TokenKind tokenKind,
      boolean invertMatch, Entry entry, AdditionalCommitAction... commitActions) {
    parseContext.startParse(parser, parseContext, tokenKind, invertMatch);
    parseContext.begin(parser);
    TransactionElement current = parseContext.getCurrent();
    for (Token token : entry.tokens()) {
      current.getTokens().add(token.deepCopy());
    }
    int consumeDelta = entry.endConsumed() - current.getPosition(TokenKind.consumed).value();
    if (consumeDelta > 0) {
      parseContext.consume(new CodePointLength(consumeDelta));
    }
    int matchDelta = entry.endMatched() - current.getPosition(TokenKind.matchOnly).value();
    if (matchDelta > 0) {
      parseContext.matchOnly(new CodePointLength(matchDelta));
    }
    Parsed committed = new Parsed(parseContext.commit(parser, tokenKind, commitActions));
    parseContext.endParse(parser, committed, parseContext, tokenKind, invertMatch);
    return committed;
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
