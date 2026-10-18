package org.unlaxer.parser.combinator;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.context.RaisedFailure;
import org.unlaxer.context.SafeFailureMemoizable;
import org.unlaxer.context.TransactionalState;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.WordParser;

/** Cut and labeled failures (#700) against the shared Java/Rust oracle. */
public class CutLabeledFailureTest {

  private static String text(String value) {
    return value.equals("~") ? "" : value.replace("\\r", "\r").replace("\\n", "\n");
  }

  private static Parser w(String value) {
    return new WordParser(value);
  }

  /** A memoizable exact class, so SAFE_FAILURES really stores and replays failure entries. */
  static final class MemoChoice extends Choice implements SafeFailureMemoizable {
    private static final long serialVersionUID = 1L;
    MemoChoice(Parser... children) { super(children); }
  }

  static final class State implements TransactionalState {
    int value = 7;
    @Override public Runnable checkpoint() { int before = value; return () -> value = before; }
  }

  /** Succeeds without input after a registered state mutation. */
  private static Parser mutate(State state) {
    return new Chain() {
      private static final long serialVersionUID = 1L;
      @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
        state.value = 99;
        return Parsed.SUCCEEDED;
      }
    };
  }

  /** Mirrors {@code fixture} in rust/unlaxer-runtime/tests/cut_labeled_failure.rs. */
  static Parser fixture(String name, State state) {
    return switch (name) {
      case "plain-choice" -> new Choice(new Chain(w("("), w("x"), w(")")), w("(y)"));
      case "commit-choice" -> new Choice(new Chain(w("("), new Commit(w("x"), "expected x"), w(")")), w("(y)"));
      case "commit-default" -> new Commit(w("a"));
      case "commit-optional" -> new Chain(
          new Optional(new Chain(w("a"), new Commit(w("b"), "expected b"))), w("a"), w("c"));
      case "commit-repeat" -> new Chain(
          new ZeroOrMore(new Chain(w(","), new Commit(w("n"), "expected n"))), w(";"));
      case "label-repeat" -> new OneOrMore(new Label("item", w("a")));
      case "lookahead-commit" -> new Chain(new PegLookahead(new Commit(w("a"), "expected a")), w("a"));
      case "not-commit" -> new Chain(new PegLookahead(new Commit(w("a"), "expected a"), false), w("b"));
      case "label-choice" -> new Choice(new Label("L", w("a")), w("b"));
      case "catch-alternative" -> new CatchLabels(
          new Chain(w("("), new Label("close", w(")"))), w("(]"), "close");
      case "catch-other-label" -> new CatchLabels(new Label("A", w("a")), w("b"), "B");
      case "catch-ordinary" -> new CatchLabels(w("a"), w("b"), "L");
      case "catch-commit" -> new CatchLabels(new Commit(w("a"), "expected a"), w("b"), "L");
      case "label-in-commit" -> new Commit(new Label("L", w("a")), "m");
      case "commit-in-label" -> new Label("L", new Commit(w("a"), "m"));
      case "innermost-label" -> new Label("outer", new Chain(w("a"), new Label("inner", w("b"))));
      case "nested-catch" -> new CatchLabels(
          new CatchLabels(new Label("A", w("a")), new Label("B", w("b")), "A"), w("c"), "B");
      case "recovery-commit" -> new Chain(
          new SyncPointRecoveryParser(new Chain(w("x"), new Commit(w("y"), "expected y")), ";"), w("!"));
      case "recovery-label" -> new SyncPointRecoveryParser(new Chain(w("x"), new Label("L", w("y"))), ";");
      case "longest-commit" -> new LongestChoice(new Chain(w("a"), new Commit(w("b"), "expected b")), w("ac"));
      case "nonordered-label" -> new NonOrdered(new Label("L", w("a")), w("b"));
      case "unicode-label" -> new Chain(w("😀"), w("\r\n"), new Label("L", w("界")));
      case "match-only-commit" -> new MatchOnly(new Commit(w("a"), "expected a"));
      case "memo-label" -> {
        Parser rule = new MemoChoice(new Label("L", w("a")));
        // Same depth for both evaluations: a memo hit needs the original resource budget.
        yield new CatchLabels(new Choice(rule), new MemoChoice(rule, w("b")), "L");
      }
      case "state-success" -> new Chain(mutate(state), new NamedCapture("c", w("a")));
      case "state-catch" -> new CatchLabels(
          new Chain(mutate(state), new NamedCapture("c", w("a")), new Label("L", w("b"))),
          new Chain(w("a"), w("c")), "L");
      case "state-commit" -> new Chain(mutate(state), new NamedCapture("c", w("a")), new Commit(w("b"), "expected b"));
      case "state-label" -> new Chain(mutate(state), new NamedCapture("c", w("a")), new Label("L", w("b")));
      default -> throw new IllegalArgumentException(name);
    };
  }

  private static List<String[]> rows(String file, int columns) throws Exception {
    var rows = Files.readAllLines(Path.of("../conformance/cut-labeled-failure/" + file)).stream()
        .filter(row -> !row.startsWith("#")).map(row -> row.split("\t", -1)).toList();
    for (String[] row : rows) assertEquals(String.join("|", row), columns, row.length);
    return rows;
  }

  private static void assertOutcome(String label, String[] expected, int outcomeColumn,
      int offsetColumn, ParseContext context, Parsed result, ParseOptions.Diagnostics diagnostics) {
    String outcome = expected[outcomeColumn];
    var raised = context.getRaisedFailure();
    var failure = context.getParseFailureDiagnostics();
    switch (outcome) {
      case "success", "ordinary" -> {
        assertEquals(label, outcome.equals("success"), result.isSucceeded());
        assertTrue(label, raised.isEmpty());
        assertTrue(label, failure.getRaisedFailure().isEmpty());
      }
      case "commit", "label" -> {
        assertFalse(label, result.isSucceeded());
        RaisedFailure value = raised.orElseThrow(() -> new AssertionError(label));
        assertEquals(label, outcome.equals("commit") ? RaisedFailure.Kind.COMMIT : RaisedFailure.Kind.LABEL, value.kind());
        int offset = Integer.parseInt(expected[offsetColumn]);
        assertEquals(label, offset, value.offset());
        // The final error prefers the raised failure in every diagnostics policy.
        assertEquals(label, offset, failure.getFarthestOffset());
        assertEquals(label, Set.of(value.message()), failure.getExpectedTokens());
        assertEquals(label, value, failure.getRaisedFailure().orElseThrow());
      }
      default -> fail(label + ": " + outcome);
    }
  }

  @Test
  public void sharedIndependentOracle() throws Exception {
    var rows = rows("cases.tsv", 10);
    assertEquals(45, rows.size());
    for (var memo : Memoization.values()) for (var diagnostics : ParseOptions.Diagnostics.values()) for (String[] c : rows) {
      String label = c[0] + "/" + memo + "/" + diagnostics;
      try (var context = ParseContext.withOptions(StringSource.createRootSource(text(c[2])),
          ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
        Parsed result = fixture(c[1], null).parse(context);
        assertEquals(label, Boolean.parseBoolean(c[3]), result.isSucceeded());
        assertEquals(label, Integer.parseInt(c[4]), context.getConsumedPosition().value());
        assertEquals(label, Integer.parseInt(c[5]), context.getMatchedPosition().value());
        assertOutcome(label, c, 6, 9, context, result, diagnostics);
        context.getRaisedFailure().ifPresent(raised -> {
          assertEquals(label, c[7].equals("~") ? null : c[7], raised.label());
          assertEquals(label, text(c[8]), raised.message());
        });
        if (!result.isSucceeded()) assertTrue(label, context.getCurrent().getTokens().isEmpty());
        if (c[1].equals("memo-label") && c[2].equals("b") && memo == Memoization.SAFE_FAILURES) {
          // The replayed entry must restore the label, or the choice would try "b".
          assertTrue(label, context.getPackratMemoTable().failureHits() > 0);
        }
      }
    }
  }

  @Test
  public void sharedStateOracle() throws Exception {
    var rows = rows("state.tsv", 9);
    assertEquals(4, rows.size());
    for (var memo : Memoization.values()) for (var diagnostics : ParseOptions.Diagnostics.values()) for (String[] c : rows) {
      String label = c[0] + "/" + memo + "/" + diagnostics;
      State state = new State();
      try (var context = ParseContext.withOptions(StringSource.createRootSource(text(c[2])),
          ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
        context.registerTransactionalState(state);
        Parsed result = fixture(c[1], state).parse(context);
        assertEquals(label, Boolean.parseBoolean(c[3]), result.isSucceeded());
        assertEquals(label, Integer.parseInt(c[4]), context.getConsumedPosition().value());
        assertOutcome(label, c, 5, 6, context, result, diagnostics);
        assertEquals(label, Integer.parseInt(c[7]), state.value);
        assertEquals(label, c[8].equals("1"), context.captured("c").isPresent());
      }
    }
  }

  @Test
  public void aRaisedFailureDoesNotLeakIntoTheNextParse() {
    try (var context = new ParseContext(StringSource.createRootSource("b"))) {
      assertFalse(fixture("label-choice", null).parse(context).isSucceeded());
      assertEquals("L", context.getRaisedFailure().orElseThrow().label());
      assertTrue(new Choice(w("a"), w("b")).parse(context).isSucceeded());
      assertTrue(context.getRaisedFailure().isEmpty());
      assertTrue(context.getParseFailureDiagnostics().getRaisedFailure().isEmpty());
    }
  }

  @Test
  public void classicNotDoesNotCatchARaisedFailure() {
    try (var context = new ParseContext(StringSource.createRootSource("b"))) {
      assertFalse(new Chain(new Not(new Label("L", w("a"))), w("b")).parse(context).isSucceeded());
      assertEquals("L", context.getRaisedFailure().orElseThrow().label());
    }
    try (var context = new ParseContext(StringSource.createRootSource("b"))) {
      assertTrue(new Chain(new Not(w("a")), w("b")).parse(context).isSucceeded());
    }
  }

  /** Observation for #700: a committed error stops the remaining alternatives being explored. */
  @Test
  public void commitStopsFurtherAlternatives() {
    for (boolean committed : List.of(false, true)) {
      AtomicInteger attempts = new AtomicInteger();
      Parser counted = new Chain(w("(")) {
        private static final long serialVersionUID = 1L;
        @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
          attempts.incrementAndGet();
          return super.parse(context, kind, invert);
        }
      };
      Parser first = new Chain(w("("), committed ? new Commit(w("x"), "expected x") : w("x"));
      Parser[] alternatives = new Parser[17];
      alternatives[0] = first;
      for (int i = 1; i < alternatives.length; i++) alternatives[i] = new Chain(counted, w("" + (char) ('a' + i)));
      try (var context = new ParseContext(StringSource.createRootSource("(z"))) {
        assertFalse(new Choice(alternatives).parse(context).isSucceeded());
        assertEquals(committed ? 0 : 16, attempts.get());
        assertEquals(committed, context.getRaisedFailure().isPresent());
      }
    }
  }

  @Test
  public void constructorsRejectMalformedOperators() {
    assertThrows(IllegalArgumentException.class, () -> new Label("", w("a")));
    assertThrows(IllegalArgumentException.class, () -> new Commit(w("a"), ""));
    assertThrows(IllegalArgumentException.class, () -> new CatchLabels(w("a"), w("b")));
    assertThrows(IllegalArgumentException.class, () -> new CatchLabels(w("a"), w("b"), "L", "L"));
    assertThrows(IllegalArgumentException.class, () -> new CatchLabels(w("a"), w("b"), ""));
  }
}
