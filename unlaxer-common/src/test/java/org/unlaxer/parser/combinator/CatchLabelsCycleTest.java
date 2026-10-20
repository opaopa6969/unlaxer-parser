package org.unlaxer.parser.combinator;

import static org.junit.Assert.*;

import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.context.DiagnosticsSafety;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseLimitException;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.context.TransactionalState;
import org.unlaxer.parser.FirstSets;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.elementary.WordParser;

/**
 * A rule cycle through a CATCH alternative (#720), on the public Java combinator path. Rust's
 * parse-time Classic-mode scan recursed into the alternative with a fresh visited set and
 * overflowed the stack; Java has no such pre-parse scan, and its graph analyses
 * ({@link DiagnosticsSafety}, {@link FirstSets}) are iterative with one identity-visited set.
 * Parsing the cycle ends with the existing resource-limit contract ({@link ParseLimitException}).
 */
public class CatchLabelsCycleTest {

  private static final class State implements TransactionalState {
    int value = 7;
    @Override public Runnable checkpoint() {
      int saved = value;
      return () -> value = saved;
    }
  }

  /** {@code R0 ::= CATCH(LABEL(L, 'Z'), R0, L)}: the alternative re-enters R0 at the same position. */
  static final class LeftCycle extends LazyChain {
    private static final long serialVersionUID = 1L;
    @Override public Parsers getLazyParsers() {
      return Parsers.of(new CatchLabels(new Label("L", new WordParser("Z")), this, "L"));
    }
  }

  /** {@code R0 ::= CATCH(LABEL(L, 'Z'), 'a' R0, L)}: a consuming cycle, bounded by the input. */
  static final class ConsumingCycle extends LazyChain {
    private static final long serialVersionUID = 1L;
    @Override public Parsers getLazyParsers() {
      return Parsers.of(new CatchLabels(new Label("L", new WordParser("Z")), new Chain(new WordParser("a"), this), "L"));
    }
  }

  @Test
  public void graphAnalysesTerminateOnTheCycle() {
    for (Parser parser : new Parser[] {new LeftCycle(), new ConsumingCycle()}) {
      assertTrue(DiagnosticsSafety.isDeferredDiagnosticsSafe(parser));
      assertTrue(FirstSets.of(parser).isUnknown());
    }
  }

  @Test
  public void aLeftCycleThroughTheAlternativeIsAResourceLimit() {
    for (var memo : Memoization.values()) for (var diagnostics : ParseOptions.Diagnostics.values()) {
      String label = memo + "/" + diagnostics;
      Parser parser = new LeftCycle();
      State state = new State();
      ParseLimitException failure;
      try (var context = ParseContext.withOptions(StringSource.createRootSource(""),
          ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
        context.registerTransactionalState(state);
        failure = assertThrows(label, ParseLimitException.class, () -> parser.parse(context));
        assertEquals(label, ParseContext.MAX_PARSE_DEPTH, failure.limit());
        assertEquals(label, 0, failure.offset());
        // Sticky: the context is unusable after the resource failure.
        assertSame(label, failure, assertThrows(ParseLimitException.class, () -> new WordParser("Z").parse(context)));
        assertEquals(label, 7, state.value);
      }
      try (var fresh = new ParseContext(StringSource.createRootSource("Z"))) {
        assertTrue(label, parser.parse(fresh).isSucceeded());
        assertTrue(label, fresh.allConsumed());
      }
    }
  }

  @Test
  public void aConsumingCycleParsesAndADeepOneIsAResourceLimit() {
    for (var memo : Memoization.values()) for (var diagnostics : ParseOptions.Diagnostics.values()) {
      String label = memo + "/" + diagnostics;
      Parser parser = new ConsumingCycle();
      var options = ParseOptions.withMemoization(memo).withDiagnostics(diagnostics);
      try (var context = ParseContext.withOptions(StringSource.createRootSource("aaZ"), options)) {
        assertTrue(label, parser.parse(context).isSucceeded());
        assertTrue(label, context.allConsumed());
      }
      try (var context = ParseContext.withOptions(StringSource.createRootSource("aab"), options)) {
        assertTrue(label, parser.parse(context).isFailed());
        assertEquals(label, 0, context.position());
        if (diagnostics != ParseOptions.Diagnostics.DETAILED_ON_FAILURE) {
          assertEquals(label, 2, context.getParseFailureDiagnostics().getFarthestOffset());
        }
      }
      try (var context = ParseContext.withOptions(StringSource.createRootSource("a".repeat(5_000) + "Z"), options)) {
        var failure = assertThrows(label, ParseLimitException.class, () -> parser.parse(context));
        assertEquals(label, ParseContext.MAX_PARSE_DEPTH, failure.limit());
        assertEquals(label, 0, context.position());
      }
    }
  }
}
