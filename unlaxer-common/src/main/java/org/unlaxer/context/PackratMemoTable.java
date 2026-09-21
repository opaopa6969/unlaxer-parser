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
    Entry entry = parseContext.getPackratMemoTable()
        .get(parser, positionKeyOf(parseContext, tokenKind, invertMatch));
    if (entry != null) {
      if (entry.tokens == null) {
        parseContext.replayMemoTransactionEvents(entry.diagnostic);
        parseContext.getPackratMemoTable().failureHits++;
      } else {
        parseContext.getPackratMemoTable().successHits++;
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

  public int failureHits() { return failureHits; }
  public int successHits() { return successHits; }
}
