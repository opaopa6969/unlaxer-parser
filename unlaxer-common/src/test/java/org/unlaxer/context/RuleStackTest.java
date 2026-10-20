package org.unlaxer.context;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.parser.GrammarRule;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.CatchLabels;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.Commit;
import org.unlaxer.parser.combinator.Label;
import org.unlaxer.parser.combinator.PegLookahead;
import org.unlaxer.parser.elementary.WordParser;

/** Rule stacks of the final error (#703) against the shared Java/Rust oracle. */
public class RuleStackTest {

  /** A memoizable sequence rule, as generated rules are rule classes with exact memo markers. */
  static final class Rule extends Chain implements GrammarRule, SafeFailureMemoizable {
    private static final long serialVersionUID = 1L;
    private final String name;
    Rule(String name, Parser... children) { super(children); this.name = name; }
    @Override public String grammarRuleName() { return name; }
  }

  /** A memoizable choice rule. */
  static final class AltRule extends Choice implements GrammarRule, SafeFailureMemoizable {
    private static final long serialVersionUID = 1L;
    private final String name;
    AltRule(String name, Parser... children) { super(children); this.name = name; }
    @Override public String grammarRuleName() { return name; }
  }

  private static Parser w(String value) {
    return new WordParser(value);
  }

  /** Mirrors {@code grammar} in rust/unlaxer-runtime/tests/rule_stack.rs; returns the root. */
  static Parser grammar(String name) {
    switch (name) {
      case "nested":
        return new Rule("Root", w("("), new AltRule("Item", w("x"), w("y")), w(")"));
      case "alternatives":
        return new AltRule("Root", new Rule("A", w("a"), w("b")), new Rule("B", w("a"), w("c"), w("d")));
      case "deeper":
        return new AltRule("Root", new Rule("A", w("a"), w("q")), new Rule("B", w("a"), new Rule("C", w("r"))));
      case "equal":
        return new AltRule("Root", new Rule("A", w("a"), w("q")), new Rule("B", w("a"), w("r")));
      case "lookahead": {
        Parser n = new Rule("N", w("b"), w("c"));
        Parser m = new Rule("M", w("a"), n);
        Parser probe = new Rule("Probe", w("("), m);
        Parser body = new AltRule("Body", new Chain(w("("), m, w("!")), new Chain(w("("), w("k")));
        return new Rule("Root", new PegLookahead(probe, false), body);
      }
      case "commit": {
        Parser inner = new Rule("Inner", w("x"), new Commit(w("y"), "expected y"));
        return new Rule("Root", w("("), new Rule("Body", inner, w(")")));
      }
      case "label":
        return new Rule("Root", new Rule("List", w("["), new Label("item", new Rule("Item", w("a"))), w("]")));
      case "memo-label": {
        Parser m = new Rule("M", new Label("L", w("x")));
        return new Rule("Root", new CatchLabels(new Rule("A", w("("), m), new Rule("B", w("("), m), "L"));
      }
      case "caught":
        return new Rule("Root", new CatchLabels(new Rule("P", new Label("L", w("a"))),
            new Rule("Q", w("b"), w("c")), "L"));
      case "deep":
      case "deep-commit": {
        Parser next = name.equals("deep") ? w("x") : new Commit(w("x"), "expected x");
        for (int index = 39; index >= 0; index--) next = new Rule("D" + index, w("("), next);
        return next;
      }
      case "unicode":
        return new Rule("Root", w("😀"), new Rule("Item", w("界"), w("x")));
      case "bare":
        return new Chain(w("a"), w("b"));
      default:
        throw new IllegalArgumentException(name);
    }
  }

  private static List<String[]> rows() throws Exception {
    var rows = Files.readAllLines(Path.of("../conformance/rule-stack/cases.tsv")).stream()
        .filter(row -> !row.startsWith("#")).map(row -> row.split("\t", -1)).collect(Collectors.toList());
    for (String[] row : rows) assertEquals(String.join("|", row), 8, row.length);
    assertEquals(15, rows.size());
    return rows;
  }

  /** {@code Root@0>Item@1}, or {@code ~} without a stack, and the omitted count. */
  private static String render(Optional<RuleStack> stack) {
    if (stack.isEmpty()) return "~|0";
    return stack.get().frames().stream().map(frame -> frame.rule() + "@" + frame.start())
        .collect(Collectors.joining(">")) + "|" + stack.get().omitted();
  }

  private static void assertStack(String label, String[] row, Optional<RuleStack> stack) {
    assertEquals(label, row[6] + "|" + row[7], render(stack));
    stack.ifPresent(value -> {
      assertEquals(label, Integer.parseInt(row[5]), value.offset());
      assertTrue(label, value.frames().size() <= RuleStack.LIMIT);
      for (var frame : value.frames()) assertEquals(label, value.offset(), value.end(frame));
    });
  }

  @Test
  public void sharedIndependentOracle() throws Exception {
    for (var memo : Memoization.values()) for (var diagnostics : ParseOptions.Diagnostics.values()) for (String[] row : rows()) {
      String label = row[0] + "/" + memo + "/" + diagnostics;
      try (var context = ParseContext.withOptions(StringSource.createRootSource(row[2]),
          ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
        Parsed result = grammar(row[1]).parse(context);
        assertFalse(label, result.isSucceeded());
        boolean detailed = context.getOptions().diagnostics() == ParseOptions.Diagnostics.DETAILED;
        var failure = context.getParseFailureDiagnostics();
        if (row[3].equals("syntax")) {
          assertTrue(label, context.getRaisedFailure().isEmpty());
          if (detailed) assertEquals(label, Integer.parseInt(row[5]), failure.getFarthestOffset());
        } else {
          RaisedFailure raised = context.getRaisedFailure().orElseThrow(() -> new AssertionError(label));
          assertEquals(label, row[3].equals("commit") ? RaisedFailure.Kind.COMMIT : RaisedFailure.Kind.LABEL, raised.kind());
          assertEquals(label, row[4].equals("~") ? null : row[4], raised.label());
          assertEquals(label, Integer.parseInt(row[5]), raised.offset());
        }
        var stack = context.getRuleStack();
        assertEquals(label, stack, failure.getRuleStack());
        if (detailed) {
          assertStack(label, row, stack);
        } else {
          // A direct context never retries, and only a DETAILED pass records rules.
          assertTrue(label, stack.isEmpty());
        }
        if (memo == Memoization.SAFE_FAILURES && (row[1].equals("lookahead") || row[1].equals("memo-label"))) {
          // The expected stack is only reachable through a rebased memo replay.
          assertTrue(label, context.getPackratMemoTable().failureHits() > 0);
        }
      }
    }
  }

  @Test
  public void aRaisedStackDoesNotLeakIntoALaterParse() {
    try (var context = new ParseContext(StringSource.createRootSource("(xz"))) {
      assertFalse(grammar("commit").parse(context).isSucceeded());
      assertEquals("Root@0>Body@1>Inner@1|0", render(context.getRuleStack()));
      // The same context, now an ordinary failure: the farthest failure's stack.
      assertFalse(grammar("equal").parse(context).isSucceeded());
      assertTrue(context.getRaisedFailure().isEmpty());
      assertEquals(context.getParseFailureDiagnostics().getFarthestOffset(),
          context.getRuleStack().orElseThrow().offset());
    }
  }

  @Test
  public void ruleNamesDefaultToTheGeneratedClassName() {
    final class ExpressionParser extends Chain implements GrammarRule {
      private static final long serialVersionUID = 1L;
    }
    final class Parser extends Chain implements GrammarRule {
      private static final long serialVersionUID = 1L;
    }
    assertEquals("Expression", new ExpressionParser().grammarRuleName());
    assertEquals("Parser", new Parser().grammarRuleName());
  }

  @Test
  public void stacksAreBoundedAndCarryOriginalOffsets() {
    var frames = new java.util.ArrayList<RuleStack.Frame>();
    for (int index = 0; index < 40; index++) frames.add(new RuleStack.Frame("D" + index, index));
    RuleStack stack = RuleStack.bounded(40, frames);
    assertEquals(8, stack.omitted());
    assertEquals(40, stack.depth());
    assertEquals(new RuleStack.Frame("D8", 8), stack.frames().get(0));
    assertNull(RuleStack.bounded(0, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new RuleStack(0, frames.subList(0, 3), 1));
  }
}
