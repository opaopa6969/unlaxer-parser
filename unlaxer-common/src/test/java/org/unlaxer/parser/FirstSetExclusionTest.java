package org.unlaxer.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseContextEffector;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.context.ParseOptions.Diagnostics;
import org.unlaxer.context.SafeFailureMemoizable;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.combinator.Not;
import org.unlaxer.parser.combinator.Optional;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.DigitParser;
import org.unlaxer.parser.posix.SpaceParser;

/**
 * FIRST-set candidate exclusion (#292) must change how long a parse takes and nothing else. Each
 * test parses the same input with {@code DETAILED_ON_FAILURE} (exclusion active) and with
 * {@code DETAILED} (exclusion off by construction) and requires the same answer.
 */
public class FirstSetExclusionTest {

  /** Leading trivia lives inside the alternative, so its FIRST set has to reach past it. */
  static final class Space extends ZeroOrMore {
    private static final long serialVersionUID = 1L;

    Space() {
      super(SpaceParser.class);
    }
  }

  static final class Sin extends LazyChain implements SafeFailureMemoizable {
    private static final long serialVersionUID = 1L;

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(new Space(), new WordParser("sin"), new WordParser("("),
          new DigitParser(), new WordParser(")"));
    }
  }

  static final class Cos extends LazyChain implements SafeFailureMemoizable {
    private static final long serialVersionUID = 1L;

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(new Space(), new WordParser("cos"), new WordParser("("),
          new DigitParser(), new WordParser(")"));
    }
  }

  /** An alternative the analysis cannot model: it must be tried whatever the next code point is. */
  static final class Guarded extends LazyChain implements SafeFailureMemoizable {
    private static final long serialVersionUID = 1L;

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(new Not(new WordParser("@@")), new WordParser("@"), new DigitParser());
    }
  }

  static final class CaseInsensitive extends LazyChain implements SafeFailureMemoizable {
    private static final long serialVersionUID = 1L;

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(new WordParser("TRUE", true));
    }
  }

  static final class Unicode extends LazyChain implements SafeFailureMemoizable {
    private static final long serialVersionUID = 1L;

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(new WordParser("あい"));
    }
  }

  static final class Alternatives extends LazyChoice implements SafeFailureMemoizable {
    private static final long serialVersionUID = 1L;

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(new Sin(), new Cos(), new Guarded(), new CaseInsensitive(), new Unicode());
    }
  }

  /** A nullable prefix followed by a real token: the optional part must widen the FIRST set. */
  static final class OptionalSign extends LazyChain implements SafeFailureMemoizable {
    private static final long serialVersionUID = 1L;

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(new Optional(new WordParser("-")), new DigitParser());
    }
  }

  static final class Root extends LazyChain implements SafeFailureMemoizable {
    private static final long serialVersionUID = 1L;

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(new ZeroOrMore(new Alternatives()), new OptionalSign());
    }
  }

  private static final String[] INPUTS = {
      "sin(1)0", "cos(2)-3", "@1 0", "TrUe 5", "あい 7", " sin(1)cos(2)8", "",
      "sin", "cos(", "@@1", "あ 1", "x", "-9", "sin(1)@@0"
  };

  private record Observation(boolean succeeded, int consumed, List<String> tree) {}

  private static Observation parse(Parser parser, String input, Diagnostics diagnostics,
      Memoization memoization) {
    ParseOptions options = ParseOptions.withMemoization(memoization).withDiagnostics(diagnostics);
    ParseContext context = ParseContext.withOptions(StringSource.createRootSource(input), options,
        new ParseContextEffector[0]);
    try {
      Parsed parsed = parser.parse(context);
      List<String> tree = new ArrayList<>();
      if (parsed.isSucceeded()) {
        appendTree(parsed.getRootToken(), 0, tree);
      }
      return new Observation(parsed.isSucceeded(),
          context.getConsumedPosition().value(), tree);
    } finally {
      context.close();
    }
  }

  private static void appendTree(Token token, int depth, List<String> out) {
    out.add(depth + " " + token.getParser().getClass().getSimpleName() + " '"
        + token.getSource().sourceAsString() + "'");
    token.getChildren(child -> true, Token.ChildrenKind.original)
        .forEach(child -> appendTree(child, depth + 1, out));
  }

  @Test
  public void excludingCandidatesKeepsEveryParseResult() {
    Root root = new Root();
    for (String input : INPUTS) {
      for (Memoization memoization : Memoization.values()) {
        Observation detailed = parse(root, input, Diagnostics.DETAILED, memoization);
        Observation deferred = parse(root, input, Diagnostics.DETAILED_ON_FAILURE, memoization);
        assertEquals(input + " " + memoization, detailed, deferred);
      }
    }
  }

  @Test
  public void aParserThatTheAnalysisCannotModelIsNeverExcluded() {
    // Not(...) makes the whole alternative unknown, so "@1" is still reached through the choice.
    Root root = new Root();
    assertTrue(parse(root, "@10", Diagnostics.DETAILED_ON_FAILURE, Memoization.SAFE_FAILURES)
        .succeeded());
    assertTrue(FirstSets.of(new Guarded()).isUnknown());
    assertTrue(FirstSets.of(new Guarded()).mayStartWith('z'));
  }

  @Test
  public void firstSetsFollowTheCombinatorRules() {
    FirstSet sin = FirstSets.of(new Sin());
    assertTrue(sin.mayStartWith('s'));
    assertTrue("leading trivia is part of the alternative", sin.mayStartWith(' '));
    assertFalse(sin.mayStartWith('c'));
    assertFalse(sin.isNullable());

    FirstSet caseInsensitive = FirstSets.of(new CaseInsensitive());
    assertTrue(caseInsensitive.mayStartWith('T'));
    assertTrue(caseInsensitive.mayStartWith('t'));
    assertFalse(caseInsensitive.mayStartWith('R'));

    FirstSet unicode = FirstSets.of(new Unicode());
    assertTrue(unicode.mayStartWith('あ'));
    assertFalse(unicode.mayStartWith('a'));

    FirstSet optionalSign = FirstSets.of(new OptionalSign());
    assertTrue("the optional prefix widens the set", optionalSign.mayStartWith('-'));
    assertTrue(optionalSign.mayStartWith('7'));
    assertFalse(optionalSign.mayStartWith('+'));
    assertFalse(optionalSign.isNullable());

    assertTrue("a repetition that accepts zero matches starts anywhere",
        FirstSets.of(new ZeroOrMore(new Alternatives())).mayStartWith('z'));
    assertTrue(FirstSets.of(new Space()).isNullable());

    // A character class is read off exactly for ASCII.
    FirstSet digits = FirstSets.of(new DigitParser());
    for (int codePoint = 0; codePoint < 128; codePoint++) {
      assertEquals((char) codePoint + "", codePoint >= '0' && codePoint <= '9',
          digits.mayStartWith(codePoint));
    }

    assertSame(FirstSet.UNKNOWN, FirstSets.of(new Guarded()));
    assertTrue(FirstSet.UNKNOWN.mayStartWith(-1));
    assertFalse(FirstSet.EMPTY.mayStartWith(-1));
    assertTrue(FirstSet.EPSILON.mayStartWith(-1));
  }

  /** A recursive grammar makes the parser graph cyclic; the analysis has to reach a fixed point. */
  static final class Expression extends LazyChoice implements SafeFailureMemoizable {
    private static final long serialVersionUID = 1L;

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(new Parenthesised(), new DigitParser());
    }
  }

  static final class Parenthesised extends LazyChain implements SafeFailureMemoizable {
    private static final long serialVersionUID = 1L;

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(new WordParser("("), Parser.get(Expression.class), new WordParser(")"));
    }
  }

  @Test
  public void aRecursiveGrammarReachesAFixedPoint() {
    FirstSet expression = FirstSets.of(Parser.get(Expression.class));
    assertTrue(expression.mayStartWith('('));
    assertTrue(expression.mayStartWith('5'));
    assertFalse(expression.mayStartWith(')'));
    assertFalse(expression.isNullable());
    for (String input : new String[] {"((7))", "3", "(", ")", "((3)"}) {
      assertEquals(input,
          parse(Parser.get(Expression.class), input, Diagnostics.DETAILED, Memoization.SAFE_FAILURES),
          parse(Parser.get(Expression.class), input, Diagnostics.DETAILED_ON_FAILURE,
              Memoization.SAFE_FAILURES));
    }
  }

  /**
   * Parsers are shared singletons, so several threads can race on the very first evaluation of the
   * same parser. Every thread has to see the same complete set and the same parse results.
   */
  @Test
  public void theLazyCacheIsSafeWhenThreadsParseTheSameParserInstance() throws Exception {
    int threads = 8;
    Root root = new Root();
    Observation expected = parse(root, "sin(1)cos(2)-3", Diagnostics.DETAILED,
        Memoization.SAFE_FAILURES);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Callable<List<Observation>>> tasks = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        tasks.add(() -> {
          List<Observation> observations = new ArrayList<>();
          for (int round = 0; round < 40; round++) {
            observations.add(parse(root, "sin(1)cos(2)-3", Diagnostics.DETAILED_ON_FAILURE,
                Memoization.SAFE_FAILURES));
          }
          return observations;
        });
      }
      for (Future<List<Observation>> future : pool.invokeAll(tasks)) {
        for (Observation observation : future.get()) {
          assertEquals(expected, observation);
        }
      }
    } finally {
      pool.shutdown();
      assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
    }
  }
}
