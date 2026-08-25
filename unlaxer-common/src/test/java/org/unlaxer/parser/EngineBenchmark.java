package org.unlaxer.parser;

import java.util.Arrays;
import java.util.Locale;

import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.calculator.CalculatorParsers;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.elementary.WordParser;

/**
 * Reproducible engine micro-benchmark (LOOP ENGINEERING MODE).
 *
 * <p>Three scenarios:
 * <ol>
 *   <li>calculator-short  — CalculatorParsers, ~10-char expression (no backtracking)</li>
 *   <li>calculator-long   — CalculatorParsers, ~4 KiB generated expression (mild backtracking)</li>
 *   <li>exponential-deep  — PackratMemoizationTest grammar, nested(20,"x") (heavy backtracking)</li>
 * </ol>
 *
 * <p>Each scenario runs with memoization OFF and ON; warm-up then measured iterations;
 * reports min / median / p99 / mean in microseconds. Output is single-line CSV so the
 * LOOP engine can diff runs mechanically.
 *
 * <p>Run:
 * <pre>
 * mvn -B -pl unlaxer-common test-compile
 * mvn -B -pl unlaxer-common exec:java \
 *   -Dexec.mainClass=org.unlaxer.parser.EngineBenchmark \
 *   -Dexec.classpathScope=test \
 *   -Dgpg.skip=true
 * </pre>
 */
public class EngineBenchmark {

  // ---- scenario inputs ---------------------------------------------------

  private static final String CALC_SHORT = "1+2*3-sqrt(4)/2+cos(0)";

  private static String calculatorLongInput() {
    StringBuilder builder = new StringBuilder();
    String[] atoms = {"1", "2", "3", "sin(0)", "cos(0)", "sqrt(4)", "(1+2)", "(3*4)"};
    for (int index = 0; index < 512; index++) {
      if (index > 0) {
        builder.append(index % 2 == 0 ? "+" : "-");
      }
      builder.append(atoms[index % atoms.length]);
    }
    return builder.toString();
  }

  // ---- PackratMemoizationTest grammar (mirrored here so this class is self-contained) ----

  public static class Expr extends LazyChoice {
    private static final long serialVersionUID = 1L;
    @Override public Parsers getLazyParsers() {
      return new Parsers(Parser.get(A.class), Parser.get(B.class));
    }
  }
  public static class A extends LazyChain {
    private static final long serialVersionUID = 1L;
    @Override public Parsers getLazyParsers() {
      return new Parsers(Parser.get(Inner.class), new WordParser("!"));
    }
  }
  public static class B extends LazyChain {
    private static final long serialVersionUID = 1L;
    @Override public Parsers getLazyParsers() {
      return new Parsers(Parser.get(Inner.class), new WordParser("?"));
    }
  }
  public static class Inner extends LazyChoice {
    private static final long serialVersionUID = 1L;
    @Override public Parsers getLazyParsers() {
      return new Parsers(Parser.get(Paren.class), new WordParser("x"));
    }
  }
  public static class Paren extends LazyChain {
    private static final long serialVersionUID = 1L;
    @Override public Parsers getLazyParsers() {
      return new Parsers(new WordParser("("), Parser.get(Expr.class), new WordParser(")"));
    }
  }

  private static String nested(int depth, String core) {
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < depth; index++) {
      builder.append('(');
    }
    builder.append(core);
    for (int index = 0; index < depth; index++) {
      builder.append(')');
    }
    return builder.toString();
  }

  // ---- benchmark harness --------------------------------------------------

  private static final int WARMUP = 50;
  private static final int MEASURE = 200;

  private static long[] benchmark(Runnable parseOnce) {
    for (int index = 0; index < WARMUP; index++) {
      parseOnce.run();
    }
    long[] times = new long[MEASURE];
    for (int index = 0; index < MEASURE; index++) {
      long start = System.nanoTime();
      parseOnce.run();
      times[index] = System.nanoTime() - start;
    }
    return times;
  }

  private static String stats(long[] nanos) {
    long[] sorted = nanos.clone();
    Arrays.sort(sorted);
    long min = sorted[0];
    long median = sorted[sorted.length / 2];
    long p99 = sorted[(int) (sorted.length * 0.99)];
    long sum = 0;
    for (long value : sorted) {
      sum += value;
    }
    double mean = (double) sum / sorted.length;
    return String.format(Locale.US,
        "min_us=%.3f,median_us=%.3f,p99_us=%.3f,mean_us=%.3f",
        min / 1000.0, median / 1000.0, p99 / 1000.0, mean / 1000.0);
  }

  private static void runScenario(String name, String source, boolean memoize, Parser parser) {
    Runnable parseOnce = () -> {
      ParseContext parseContext = memoize
          ? new ParseContext(StringSource.createRootSource(source), ParseContext.memoize())
          : new ParseContext(StringSource.createRootSource(source));
      try (parseContext) {
        Parsed parsed = parser.parse(parseContext);
        if (null == parsed) {
          throw new AssertionError("null parsed");
        }
      }
    };
    long[] times = benchmark(parseOnce);
    System.out.printf(Locale.US,
        "scenario=%s,memoize=%b,input_chars=%d,%s%n",
        name, memoize, source.length(), stats(times));
  }

  public static void main(String[] arguments) {
    System.out.println("# EngineBenchmark baseline");
    System.out.printf(Locale.US, "# warmup=%d, measure=%d, java=%s%n",
        Integer.valueOf(WARMUP), Integer.valueOf(MEASURE),
        System.getProperty("java.version"));
    System.out.printf(Locale.US, "# os=%s %s%n",
        System.getProperty("os.name"), System.getProperty("os.arch"));

    Parser calculatorRoot = CalculatorParsers.getRootParser();
    String calculatorLong = calculatorLongInput();

    // Scenario 1: calculator short
    runScenario("calculator-short", CALC_SHORT, false, calculatorRoot);
    runScenario("calculator-short", CALC_SHORT, true, calculatorRoot);

    // Scenario 2: calculator long
    runScenario("calculator-long", calculatorLong, false, calculatorRoot);
    runScenario("calculator-long", calculatorLong, true, calculatorRoot);

    // Scenario 3: exponential-deep (depth=12 — tractable even with memoize off, ~2^12 worst case)
    Parser exprRoot = Parser.get(Expr.class);
    String deepInput = nested(12, "x");
    runScenario("exponential-deep", deepInput, false, exprRoot);
    runScenario("exponential-deep", deepInput, true, exprRoot);
  }
}
