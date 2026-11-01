package org.unlaxer.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.combinator.NoneChildParser;
import org.unlaxer.parser.combinator.Not;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.combinator.Optional;
import org.unlaxer.parser.combinator.PegLookahead;
import org.unlaxer.parser.combinator.Repeat;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.EndOfSourceParser;
import org.unlaxer.parser.elementary.SingleCharacterParser;
import org.unlaxer.parser.elementary.UnicodeIgnoreCaseWordParser;
import org.unlaxer.parser.elementary.WildCardCharacterParser;
import org.unlaxer.parser.elementary.WordParser;

/**
 * Public FIRST queries (#853) against the oracle shared with
 * {@code rust/unlaxer-runtime/tests/first_sets.rs}: {@code conformance/first-sets/cases.tsv}.
 * Each row fixes {@link FirstSet#toString()}, the flags, and {@link FirstSet#mayStartWith(int)}
 * for every ASCII code point, end of input and representative non-ASCII (including non-BMP).
 * {@code accepts.tsv} checks that each parser here accepts and consumes like its Rust pair.
 */
public class FirstSetParityTest {

  private static final int[] NON_ASCII = {0xE9, 0x3042, 0x1F600};

  private static final AtomicInteger CUSTOM_PARSES = new AtomicInteger();

  private static Parser w(String text) {
    return new WordParser(text);
  }

  /** A parser with its own {@code parse}: the query must classify it without running it. */
  private static Parser custom() {
    return new NoneChildParser() {
      private static final long serialVersionUID = 1L;

      @Override
      public Parser createParser() {
        return this;
      }

      @Override
      public Parsed parse(ParseContext context, TokenKind tokenKind, boolean invertMatch) {
        CUSTOM_PARSES.incrementAndGet();
        throw new AssertionError("FirstSets.of ran a custom parser");
      }
    };
  }

  /** {@code list = list "," | "x"?}: left recursion through a nullable alternative. */
  static final class LeftRecursive extends LazyChoice {
    private static final long serialVersionUID = 1L;

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(new Chain(this, w(",")), new Optional(w("x")));
    }
  }

  /** {@code a = b "a" | "x"}; {@code b = a "b" | "y"?}. */
  static final class MutualA extends LazyChoice {
    private static final long serialVersionUID = 1L;
    private final MutualB b = new MutualB(this);

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(new Chain(b, w("a")), w("x"));
    }
  }

  static final class MutualB extends LazyChoice {
    private static final long serialVersionUID = 1L;
    private final Parser a;

    MutualB(Parser a) {
      this.a = a;
    }

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(new Chain(a, w("b")), new Optional(w("y")));
    }
  }

  static final class OnlyA extends SingleCharacterParser {
    private static final long serialVersionUID = 1L;

    @Override
    public boolean isMatch(int codePoint) {
      return codePoint == 'a';
    }
  }

  /** The shape the generator emits for a UBNF character range ({@code ParserTokenEmitter}). */
  static final class RangeAToC extends SingleCharacterParser {
    private static final long serialVersionUID = 1L;

    @Override
    public boolean isMatch(char target) {
      return target >= 'a' && target <= 'c';
    }
  }

  /** The parser of a row, or {@code null} for set-algebra rows. Each call builds a new graph. */
  private static Parser parser(String id) {
    return switch (id) {
      case "literal" -> w("ab");
      case "empty-word" -> w("");
      case "non-bmp-literal" -> w("😀x");
      case "choice" -> new Choice(w("a"), w("b"));
      case "sequence" -> new Chain(new Optional(w("a")), w("b"));
      case "sequence-nullable" -> new Chain(new Optional(w("a")), new ZeroOrMore(w("b")));
      case "optional" -> new Optional(w("a"));
      case "zero-or-more" -> new ZeroOrMore(w("a"));
      case "one-or-more" -> new OneOrMore(w("a"));
      case "repeat-zero" -> new Repeat(w("a"), 0, 0);
      case "repeat-invalid" -> new Repeat(w("a"), 2, 1);
      case "eof" -> new EndOfSourceParser();
      case "eof-then-literal" -> new Chain(new EndOfSourceParser(), w("a"));
      case "zero-or-more-any" -> new ZeroOrMore(new WildCardCharacterParser());
      case "custom" -> custom();
      case "literal-then-custom" -> new Chain(w("a"), custom());
      case "custom-then-literal" -> new Chain(custom(), w("a"));
      case "choice-with-custom" -> new Choice(w("a"), custom());
      case "recursive-left" -> new LeftRecursive();
      case "recursive-mutual" -> new MutualA();
      case "char-range" -> new RangeAToC();
      case "character-predicate" -> new OnlyA();
      case "not-lookahead" -> new Not(w("a"));
      case "peg-not-lookahead" -> new PegLookahead(w("a"), false);
      case "case-insensitive" -> new UnicodeIgnoreCaseWordParser("ab");
      case "empty-chain" -> new Chain(new Parser[0]);
      case "empty-choice" -> new Choice(new Parser[0]);
      default -> null;
    };
  }

  private static FirstSet build(String id) {
    Parser parser = parser(id);
    if (parser != null) {
      return FirstSets.of(parser);
    }
    return switch (id) {
      case "of-code-points" -> FirstSet.ofCodePoints('a', 'Z', 0x1F600);
      case "control" -> FirstSet.ofCodePoints(0, ' ', '~', 127);
      case "union" -> FirstSet.ofCodePoints('a').union(FirstSet.EPSILON);
      case "union-starts-into-nullable" ->
          FirstSet.EPSILON.unionStarts(FirstSet.ofCodePoints('b'));
      case "union-starts-drops-nullable" ->
          FirstSet.ofCodePoints('a').unionStarts(FirstSet.EPSILON);
      case "unknown-union" -> FirstSet.ofCodePoints('a').union(FirstSet.UNKNOWN);
      case "unknown-union-starts" -> FirstSet.ofCodePoints('a').unionStarts(FirstSet.UNKNOWN);
      case "nullable-non-ascii" -> FirstSet.ofCodePoints('a').asNullable().withNonAscii();
      case "empty" -> FirstSet.EMPTY;
      case "epsilon" -> FirstSet.EPSILON;
      case "unknown" -> FirstSet.UNKNOWN;
      default -> throw new AssertionError("no Java construction for " + id);
    };
  }

  private static boolean[] ascii(String column) {
    boolean[] result = new boolean[128];
    if (column.equals("all")) {
      Arrays.fill(result, true);
    } else if (false == column.equals("none")) {
      for (String part : column.split(",")) {
        String[] bounds = part.split("-");
        int from = Integer.parseInt(bounds[0], 16);
        int to = Integer.parseInt(bounds[bounds.length - 1], 16);
        for (int codePoint = from; codePoint <= to; codePoint++) {
          result[codePoint] = true;
        }
      }
    }
    return result;
  }

  private static boolean flag(String column) {
    if (column.equals("yes")) {
      return true;
    }
    assertEquals("no", column);
    return false;
  }

  @Test
  public void publicQueriesMatchTheSharedOracle() throws Exception {
    Map<String, Set<String>> hosts = new HashMap<>();
    Set<String> checked = new HashSet<>();
    CUSTOM_PARSES.set(0);
    for (String line : Files.readAllLines(Path.of("../conformance/first-sets/cases.tsv"))) {
      if (line.startsWith("#")) {
        continue;
      }
      String[] row = line.split("\t", -1);
      assertEquals(line, 9, row.length);
      String id = row[0];
      String host = row[1];
      assertTrue(line, Set.of("both", "java", "rust").contains(host));
      assertTrue(line, hosts.computeIfAbsent(id, key -> new HashSet<>()).add(host));
      if (false == host.equals("both")) {
        assertFalse("a split row explains the difference: " + line, row[8].isEmpty());
      }
      if (host.equals("rust")) {
        continue;
      }
      assertTrue("one Java row per id: " + line, checked.add(id));
      FirstSet set = build(id);
      boolean nullable = flag(row[4]);
      boolean unknown = flag(row[5]);
      boolean nonAscii = flag(row[3]);
      boolean[] starts = ascii(row[2]);
      String context = id + " " + set;
      assertEquals(context, row[7], set.toString());
      assertEquals(context, nullable, set.isNullable());
      assertEquals(context, unknown, set.isUnknown());
      boolean open = unknown || nullable;
      for (int codePoint = 0; codePoint < 128; codePoint++) {
        assertEquals(context + " U+" + Integer.toHexString(codePoint),
            open || starts[codePoint], set.mayStartWith(codePoint));
      }
      assertEquals(context + " EOF", open, set.mayStartWith(-1));
      for (int codePoint : NON_ASCII) {
        assertEquals(context + " U+" + Integer.toHexString(codePoint),
            open || nonAscii, set.mayStartWith(codePoint));
      }
    }
    for (Map.Entry<String, Set<String>> entry : hosts.entrySet()) {
      Set<String> rowHosts = entry.getValue();
      assertTrue(entry.getKey() + " " + rowHosts, rowHosts.equals(Set.of("both"))
          || rowHosts.equals(Set.of("java", "rust")));
    }
    assertEquals(hosts.keySet(), checked);
    assertEquals("the query never runs a custom parser", 0, CUSTOM_PARSES.get());
  }

  private static final String[] ACCEPT_INPUTS = {"", "a", "b", "ab", "abc", "😀", " a", "é"};

  /** The paired primitives accept and consume alike; FIRST rows compare the same parsers. */
  @Test
  public void pairedParsersAcceptAsTheSharedOracle() throws Exception {
    int rows = 0;
    for (String line : Files.readAllLines(Path.of("../conformance/first-sets/accepts.tsv"))) {
      if (line.startsWith("#")) {
        continue;
      }
      String[] row = line.split("\t", -1);
      assertEquals(line, 1 + ACCEPT_INPUTS.length, row.length);
      for (int i = 0; i < ACCEPT_INPUTS.length; i++) {
        String actual;
        try (ParseContext context = new ParseContext(StringSource.createRootSource(ACCEPT_INPUTS[i]))) {
          Parsed parsed = parser(row[0]).parse(context);
          actual = parsed.isSucceeded()
              ? String.valueOf(context.getConsumedPosition().value()) : "fail";
        }
        assertEquals(row[0] + " on '" + ACCEPT_INPUTS[i] + "'", row[i + 1], actual);
      }
      rows++;
    }
    assertEquals(21, rows);
  }

  @Test
  public void aQueriedSetIsCachedPerInstanceAndValueEqual() {
    Parser parser = new Chain(new Optional(w("a")), w("b"));
    FirstSet first = FirstSets.of(parser);
    assertTrue(first == FirstSets.of(parser));
    assertEquals(first, FirstSets.of(new Chain(new Optional(w("a")), w("b"))));
    assertEquals(first.hashCode(), FirstSet.ofCodePoints('a', 'b').hashCode());
    assertEquals(FirstSet.EPSILON, FirstSets.of(new Repeat(w("a"), 0, 0)));
  }
}
