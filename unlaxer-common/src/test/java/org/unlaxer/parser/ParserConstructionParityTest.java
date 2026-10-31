package org.unlaxer.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.Test;
import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.ParserHierarchy;
import org.unlaxer.ParserNameContext;
import org.unlaxer.StringSource;
import org.unlaxer.Tag;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.ast.ASTNodeKind;
import org.unlaxer.context.CreateMetaTokenSpecifier;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseLimitException;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.context.RuleStack;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.combinator.AbstractPropagatableSource;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.Commit;
import org.unlaxer.parser.combinator.InvertMatchPropagationStopper;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.combinator.LazyZeroOrMore;
import org.unlaxer.parser.combinator.MatchOnly;
import org.unlaxer.parser.combinator.NotPropagatableSource;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.referencer.ReferenceByNameParser;
import org.unlaxer.parser.referencer.ReferenceParser;
import org.unlaxer.parser.referencer.Referencer;

/**
 * Shared construction oracle (#845): every Java construction route builds one grammar and must
 * reproduce conformance/parser-construction, as rust/unlaxer-runtime/tests/parser_construction.rs
 * does with Rule indices, RuleNames, predicates resolved once and OnceLock grammars.
 */
public class ParserConstructionParityTest {

  private static final List<String> VARIANTS = List.of(
      "by-name", "predicate-name", "predicate-rule", "factory-class", "factory-supplier", "new-instance");

  private static String decode(String value) {
    return value.equals("~") ? "" : new String(HexFormat.of().parseHex(value), StandardCharsets.UTF_8);
  }

  private static Parser w(String value) {
    return new WordParser(value);
  }

  /** An explicitly named rule, as generated rule classes are. */
  static final class NamedChain extends Chain implements GrammarRule {
    private static final long serialVersionUID = 1L;
    private final String ruleName;

    NamedChain(String ruleName, Parser... children) {
      super(Name.of(ruleName), children);
      this.ruleName = ruleName;
    }

    @Override
    public String grammarRuleName() {
      return ruleName;
    }
  }

  static final class NamedChoice extends Choice implements GrammarRule {
    private static final long serialVersionUID = 1L;
    private final String ruleName;

    NamedChoice(String ruleName, Parser... children) {
      super(Name.of(ruleName), children);
      this.ruleName = ruleName;
    }

    @Override
    public String grammarRuleName() {
      return ruleName;
    }
  }

  /** A user-defined Classic invert source without its own transaction. */
  static final class Toggle extends AbstractPropagatableSource {
    private static final long serialVersionUID = 1L;

    Toggle(Parser child) {
      super(child);
    }

    @Override
    public boolean computeInvertMatch(boolean fromParentValue, boolean thisSourceValue) {
      return fromParentValue != thisSourceValue;
    }

    @Override
    public boolean getThisInvertedSourceValue() {
      return true;
    }

    @Override
    public Parsed parseDelegated(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
      return getChild().parse(parseContext, tokenKind, invertMatch);
    }
  }

  /** One rule graph whose references are built by {@code reference}; returns rules by name. */
  private static Map<String, Parser> referenceGraph(Function<String, Parser> reference) {
    Parser item = new NamedChoice("Item",
        new Chain(w("("), reference.apply("Item"), w(")")), w("x"), w("😀"), w("\r\n"));
    Parser root = new NamedChoice("Root",
        new Chain(reference.apply("Item"), w("!")),
        new Chain(reference.apply("Item"), w("?")),
        reference.apply("Missing"),
        new Chain(new MatchOnly(reference.apply("Item")), reference.apply("Item"), w("#")));
    Parser leaf = new NamedChain("Leaf", w("a"));
    Parser probe = new NamedChain("Probe", reference.apply("Leaf"));
    Parser notProbe = new NamedChain("NotProbe", new Toggle(reference.apply("Leaf")));
    Parser stopProbe = new NamedChain("StopProbe", new InvertMatchPropagationStopper(reference.apply("Leaf")));
    // The container is the Java counterpart of Rust's rule vector: references search from it.
    new PseudoRootParser(new Parsers(root, item, leaf, probe, notProbe, stopProbe));
    Map<String, Parser> rules = new LinkedHashMap<>();
    rules.put("Root", root);
    rules.put("Item", item);
    rules.put("Leaf", leaf);
    rules.put("Probe", probe);
    rules.put("NotProbe", notProbe);
    rules.put("StopProbe", stopProbe);
    return rules;
  }

  // Class-factory grammar: lazy children resolve shared singletons, names come from the classes.
  public static final class RootParser extends LazyChoice implements GrammarRule {
    private static final long serialVersionUID = 1L;

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(
          new Chain(Parser.get(ItemParser.class), w("!")),
          new Chain(Parser.get(ItemParser.class), w("?")),
          new ReferenceByNameParser(Name.of("Missing")),
          new Chain(new MatchOnly(Parser.get(ItemParser.class)), Parser.get(ItemParser.class), w("#")));
    }
  }

  public static final class ItemParser extends LazyChoice implements GrammarRule {
    private static final long serialVersionUID = 1L;

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(
          new Chain(w("("), Parser.get(ItemParser.class), w(")")), w("x"), w("😀"), w("\r\n"));
    }
  }

  public static final class LeafParser extends LazyChain implements GrammarRule {
    private static final long serialVersionUID = 1L;

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(w("a"));
    }
  }

  public static final class ProbeParser extends LazyChain implements GrammarRule {
    private static final long serialVersionUID = 1L;

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(Parser.get(LeafParser.class));
    }
  }

  public static final class NotProbeParser extends LazyChain implements GrammarRule {
    private static final long serialVersionUID = 1L;

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(new Toggle(Parser.get(LeafParser.class)));
    }
  }

  public static final class StopProbeParser extends LazyChain implements GrammarRule {
    private static final long serialVersionUID = 1L;

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(new InvertMatchPropagationStopper(Parser.get(LeafParser.class)));
    }
  }

  private static final Map<String, Class<? extends Parser>> RULE_CLASSES = Map.of(
      "Root", RootParser.class, "Item", ItemParser.class, "Leaf", LeafParser.class,
      "Probe", ProbeParser.class, "NotProbe", NotProbeParser.class, "StopProbe", StopProbeParser.class);

  // Supplier-factory grammar: one supplier instance per rule is the singleton key.
  static final class SupplierChoice extends LazyChoice implements GrammarRule {
    private static final long serialVersionUID = 1L;
    private final String ruleName;
    private final transient Supplier<Parsers> children;

    SupplierChoice(String ruleName, Supplier<Parsers> children) {
      this.ruleName = ruleName;
      this.children = children;
    }

    @Override
    public Parsers getLazyParsers() {
      return children.get();
    }

    @Override
    public String grammarRuleName() {
      return ruleName;
    }
  }

  static final class SupplierChain extends LazyChain implements GrammarRule {
    private static final long serialVersionUID = 1L;
    private final String ruleName;
    private final transient Supplier<Parsers> children;

    SupplierChain(String ruleName, Supplier<Parsers> children) {
      this.ruleName = ruleName;
      this.children = children;
    }

    @Override
    public Parsers getLazyParsers() {
      return children.get();
    }

    @Override
    public String grammarRuleName() {
      return ruleName;
    }
  }

  static final Supplier<Parser> ITEM = () -> new SupplierChoice("Item", () -> new Parsers(
      new Chain(w("("), Parser.get(ParserConstructionParityTest.ITEM), w(")")), w("x"), w("😀"), w("\r\n")));
  static final Supplier<Parser> ROOT = () -> new SupplierChoice("Root", () -> new Parsers(
      new Chain(Parser.get(ITEM), w("!")),
      new Chain(Parser.get(ITEM), w("?")),
      new ReferenceByNameParser(Name.of("Missing")),
      new Chain(new MatchOnly(Parser.get(ITEM)), Parser.get(ITEM), w("#"))));
  static final Supplier<Parser> LEAF = () -> new SupplierChain("Leaf", () -> new Parsers(w("a")));
  static final Supplier<Parser> PROBE = () -> new SupplierChain("Probe", () -> new Parsers(Parser.get(LEAF)));
  static final Supplier<Parser> NOT_PROBE =
      () -> new SupplierChain("NotProbe", () -> new Parsers(new Toggle(Parser.get(LEAF))));
  static final Supplier<Parser> STOP_PROBE = () -> new SupplierChain("StopProbe",
      () -> new Parsers(new InvertMatchPropagationStopper(Parser.get(LEAF))));
  private static final Map<String, Supplier<Parser>> RULE_SUPPLIERS = Map.of(
      "Root", ROOT, "Item", ITEM, "Leaf", LEAF, "Probe", PROBE, "NotProbe", NOT_PROBE, "StopProbe", STOP_PROBE);

  private static final List<String> REFERENCE_VARIANTS = List.of("by-name", "predicate-name", "predicate-rule");

  /** How the reference routes of {@link #VARIANTS} build a reference to a rule. */
  private static Function<String, Parser> reference(String name) {
    switch (name) {
      case "by-name":
        return target -> new ReferenceByNameParser(Name.of(target));
      case "predicate-name":
        return target -> ReferenceParser.of(Name.of(target));
      case "predicate-rule":
        return target -> new ReferenceParser(
            parser -> parser instanceof GrammarRule && GrammarRule.nameOf(parser).equals(target));
      default:
        throw new IllegalArgumentException(name);
    }
  }

  private static Function<String, Parser> variant(String name) {
    switch (name) {
      case "by-name":
      case "predicate-name":
      case "predicate-rule": {
        Map<String, Parser> rules = referenceGraph(reference(name));
        return rules::get;
      }
      case "factory-class":
        return rule -> Parser.get(RULE_CLASSES.get(rule));
      case "factory-supplier":
        return rule -> Parser.get(RULE_SUPPLIERS.get(rule));
      case "new-instance":
        return rule -> Parser.newInstance(RULE_CLASSES.get(rule));
      default:
        throw new IllegalArgumentException(name);
    }
  }

  private static String signature(Token token) {
    String text = token.source.sourceAsString();
    return token.tokenKind + "@" + token.source.offsetFromRoot().value() + ":"
        + token.source.codePointLength().value() + ":"
        + (text.isEmpty() ? "~" : HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8)));
  }

  private static String signatures(List<Token> tokens) {
    return tokens.isEmpty() ? "~" : tokens.stream()
        .map(ParserConstructionParityTest::signature)
        .collect(Collectors.joining(";"));
  }

  private static void leaves(Token token, List<Token> target) {
    if (token.parser instanceof WordParser || token.getOriginalChildren().isEmpty()) {
      target.add(token);
      return;
    }
    for (Token child : token.getOriginalChildren()) {
      leaves(child, target);
    }
  }

  /**
   * The whole original CST including intermediate combinator nodes: rule names for GrammarRule
   * parsers, otherwise the combinator kind. cases.tsv compares root and leaves across languages;
   * this shape is compared across the construction routes of one language.
   */
  private static String shape(Token token) {
    Parser parser = token.parser;
    Class<?> type = parser.getClass().isAnonymousClass() ? parser.getClass().getSuperclass() : parser.getClass();
    String label = parser instanceof GrammarRule ? GrammarRule.nameOf(parser) : type.getSimpleName();
    if (token.getOriginalChildren().isEmpty()) {
      return label + "@" + token.source.offsetFromRoot().value() + ":" + token.source.codePointLength().value();
    }
    return label + token.getOriginalChildren().stream()
        .map(ParserConstructionParityTest::shape)
        .collect(Collectors.joining(",", "(", ")"));
  }

  private static String render(java.util.Optional<RuleStack> stack) {
    return stack.map(value -> value.frames().stream()
        .map(frame -> frame.rule() + "@" + frame.start())
        .collect(Collectors.joining(">"))).orElse("~");
  }

  private static List<String[]> rows(String file, int columns) throws Exception {
    List<String[]> rows = Files.readAllLines(Path.of("../conformance/parser-construction/" + file)).stream()
        .filter(line -> false == line.isBlank() && false == line.startsWith("#"))
        .map(line -> line.split("\t", -1))
        .collect(Collectors.toList());
    for (String[] row : rows) {
      assertEquals(String.join("|", row), columns, row.length);
    }
    return rows;
  }

  private static ParseContext context(String inputHex, Memoization memo, ParseOptions.Diagnostics diagnostics) {
    return ParseContext.withOptions(StringSource.createRootSource(decode(inputHex)),
        ParseOptions.withMemoization(memo).withDiagnostics(diagnostics));
  }

  /**
   * Parses one cases.tsv row in a fresh context and checks it against the row; returns the whole
   * original CST so the caller can compare it across construction routes.
   */
  private static String caseObservation(String label, Parser parser, String[] c, Memoization memo,
      ParseOptions.Diagnostics diagnostics) {
    try (ParseContext context = context(c[2], memo, diagnostics)) {
      Parsed parsed = parser.parse(context, TokenKind.valueOf(c[3]), Boolean.parseBoolean(c[4]));
      boolean detailed = context.getOptions().diagnostics() == ParseOptions.Diagnostics.DETAILED;
      List<Token> leaves = new ArrayList<>();
      for (Token token : context.getCurrent().getTokens()) {
        leaves(token, leaves);
      }
      String actual = String.join("\t",
          String.valueOf(parsed.isSucceeded()),
          String.valueOf(context.getConsumedPosition().value()),
          String.valueOf(context.getMatchedPosition().value()),
          String.valueOf(context.getParseFailureDiagnostics().getFarthestOffset()),
          signatures(parsed.getOriginalTokens()),
          signatures(context.getCurrent().getTokens()),
          signatures(leaves),
          render(context.getRuleStack()));
      String expected = String.join("\t",
          c[5], c[6], c[7], detailed || diagnostics == ParseOptions.Diagnostics.AUTO ? c[8] : "0",
          c[9], c[10], c[11], detailed ? c[12] : "~");
      assertEquals(label, expected, actual);
      assertEquals(label, 1, context.getTokenStack().size());
      return context.getCurrent().getTokens().stream()
          .map(ParserConstructionParityTest::shape)
          .collect(Collectors.joining(";"));
    }
  }

  @Test
  public void everyConstructionRouteReproducesTheSharedParseOracle() throws Exception {
    List<String[]> rows = rows("cases.tsv", 13);
    assertEquals(23, rows.size());
    List<String> failures = new ArrayList<>();
    Map<String, String> shapes = new LinkedHashMap<>();
    for (String variantName : VARIANTS) {
      // One graph per route, reused by every row, memo policy and diagnostics mode.
      Function<String, Parser> rules = variant(variantName);
      for (Memoization memo : Memoization.values()) {
        for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
          for (String[] c : rows) {
            String label = c[0] + "/" + variantName + "/" + memo + "/" + diagnostics;
            try {
              String tree = caseObservation(label, rules.apply(c[1]), c, memo, diagnostics);
              String first = shapes.putIfAbsent(c[0] + "/" + memo + "/" + diagnostics, tree);
              assertEquals(label + " intermediate CST differs from " + VARIANTS.get(0), first == null ? tree : first, tree);
            } catch (AssertionError failure) {
              failures.add(failure.getMessage());
            }
          }
        }
      }
    }
    assertTrue(String.join("\n", failures), failures.isEmpty());
  }

  // ---- concurrent parses of one frozen shared graph (#1007)

  private static final int THREADS = 8;
  private static final int ROUNDS = 40;

  /**
   * The shared-graph.tsv grammar: List ::= Elem*; Elem ::= '(' List ')' | 'é' | '😀';
   * Top ::= List '!'. {@code elemList} is Elem's reference to List.
   */
  private static Map<String, Parser> listGraph(Function<String, Parser> reference, Parser elemList) {
    Parser list = new NamedChain("List", new ZeroOrMore(reference.apply("Elem")));
    Parser elem = new NamedChoice("Elem", new Chain(w("("), elemList, w(")")), w("é"), w("😀"));
    Parser top = new NamedChain("Top", reference.apply("List"), w("!"));
    new PseudoRootParser(new Parsers(list, elem, top));
    Map<String, Parser> rules = new LinkedHashMap<>();
    rules.put("List", list);
    rules.put("Elem", elem);
    rules.put("Top", top);
    return rules;
  }

  /** Parses one shared-graph.tsv row in a fresh context and checks it against the row. */
  private static void graphObservation(String label, Parser parser, String[] c, Memoization memo,
      ParseOptions.Diagnostics diagnostics) {
    try (ParseContext context = context(c[2], memo, diagnostics)) {
      Parsed parsed = parser.parse(context);
      boolean detailed = context.getOptions().diagnostics() == ParseOptions.Diagnostics.DETAILED;
      List<Token> leaves = new ArrayList<>();
      for (Token token : context.getCurrent().getTokens()) {
        leaves(token, leaves);
      }
      String actual = String.join("\t",
          String.valueOf(parsed.isSucceeded()),
          String.valueOf(context.getConsumedPosition().value()),
          String.valueOf(context.getParseFailureDiagnostics().getFarthestOffset()),
          signatures(leaves),
          render(context.getRuleStack()));
      String expected = String.join("\t",
          c[3], c[4], detailed || diagnostics == ParseOptions.Diagnostics.AUTO ? c[5] : "0",
          c[6], detailed ? c[7] : "~");
      assertEquals(label, expected, actual);
    }
  }

  /** The prefix observation of {@link #scopeObservation} in one rotated mode per input. */
  private static String scopePrefixes(Parser scoped, Parser control, Memoization[] memos,
      ParseOptions.Diagnostics[] diagnostics, int mode) {
    List<String> observed = new ArrayList<>();
    String[][] calls = {{"a", "s"}, {"(x)", "s"}, {"a", "c"}};
    for (String[] call : calls) {
      int index = mode++ % (memos.length * diagnostics.length);
      try (ParseContext context = ParseContext.withOptions(StringSource.createRootSource(call[0]),
          ParseOptions.withMemoization(memos[index / diagnostics.length])
              .withDiagnostics(diagnostics[index % diagnostics.length]))) {
        observed.add(prefix(call[1].equals("s") ? scoped : control, context));
      }
    }
    return String.join(";", observed);
  }

  /** The graphs of one round, built on the test thread before the parsing threads start. */
  private static final class Round {
    final Map<String, Map<String, Parser>> cases = new LinkedHashMap<>();
    final Map<String, Map<String, Parser>> lists = new LinkedHashMap<>();
    final Map<String, Parser[]> scopes = new LinkedHashMap<>();

    Round() {
      for (String variantName : VARIANTS) {
        Function<String, Parser> rules = variant(variantName);
        Map<String, Parser> resolved = new LinkedHashMap<>();
        for (String rule : RULE_CLASSES.keySet()) {
          // Resolved once, so new-instance shares its instances like every other route.
          resolved.put(rule, rules.apply(rule));
        }
        cases.put(variantName, resolved);
      }
      for (String variantName : REFERENCE_VARIANTS) {
        Function<String, Parser> reference = reference(variantName);
        lists.put(variantName, listGraph(reference, reference.apply("List")));
      }
      for (String variantName : List.of("by-name", "predicate-name")) {
        Function<String, Parser> reference = reference(variantName);
        scopes.put(variantName, new Parser[] {scopeGraph(true, reference), scopeGraph(false, reference)});
      }
    }
  }

  @Test
  public void aFrozenSharedGraphParsesConcurrentlyLikeTheSharedOracle() throws Exception {
    List<String[]> cases = rows("cases.tsv", 13);
    List<String[]> graphRows = rows("shared-graph.tsv", 8);
    assertEquals(6, graphRows.size());
    Map<String, String> scopeExpected = new LinkedHashMap<>();
    for (String[] row : rows("construction.tsv", 4)) {
      if (row[0].startsWith("root-indicator-scope-")) {
        scopeExpected.put(row[0].equals("root-indicator-scope-by-name") ? "by-name" : "predicate-name", row[1]);
      }
    }
    assertEquals(2, scopeExpected.size());
    Memoization[] memos = Memoization.values();
    ParseOptions.Diagnostics[] diagnostics = ParseOptions.Diagnostics.values();
    int modes = memos.length * diagnostics.length;
    List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
    Map<String, String> shapes = new ConcurrentHashMap<>();
    AtomicInteger parses = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    try {
      for (int round = 0; round < ROUNDS; round++) {
        Round graphs = new Round();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> threads = new ArrayList<>();
        for (int thread = 0; thread < THREADS; thread++) {
          int threadIndex = thread;
          int roundIndex = round;
          // Submitting publishes the graphs built above to the parsing thread.
          threads.add(pool.submit(() -> {
            start.await();
            // Even threads share one order and so race on the same first uses; odd ones rotate.
            int shift = threadIndex % 2 == 0 ? 0 : threadIndex;
            int mode = threadIndex + roundIndex;
            for (String variantName : VARIANTS) {
              Map<String, Parser> rules = graphs.cases.get(variantName);
              for (int row = 0; row < cases.size(); row++) {
                String[] c = cases.get((row + shift) % cases.size());
                int index = mode++ % modes;
                Memoization memo = memos[index / diagnostics.length];
                ParseOptions.Diagnostics diagnostic = diagnostics[index % diagnostics.length];
                String label = c[0] + "/" + variantName + "/" + memo + "/" + diagnostic + "/thread" + threadIndex
                    + "/round" + roundIndex;
                try {
                  String tree = caseObservation(label, rules.get(c[1]), c, memo, diagnostic);
                  String first = shapes.putIfAbsent(c[0] + "/" + memo + "/" + diagnostic, tree);
                  assertEquals(label + " intermediate CST", first == null ? tree : first, tree);
                } catch (AssertionError | RuntimeException failure) {
                  failures.add(label + ": " + failure);
                }
                parses.incrementAndGet();
              }
            }
            for (String variantName : REFERENCE_VARIANTS) {
              Map<String, Parser> rules = graphs.lists.get(variantName);
              for (int row = 0; row < graphRows.size(); row++) {
                String[] c = graphRows.get((row + shift) % graphRows.size());
                int index = mode++ % modes;
                Memoization memo = memos[index / diagnostics.length];
                ParseOptions.Diagnostics diagnostic = diagnostics[index % diagnostics.length];
                String label = c[0] + "/" + variantName + "/" + memo + "/" + diagnostic + "/thread" + threadIndex
                    + "/round" + roundIndex;
                try {
                  graphObservation(label, rules.get(c[1]), c, memo, diagnostic);
                } catch (AssertionError | RuntimeException failure) {
                  failures.add(label + ": " + failure);
                }
                parses.incrementAndGet();
              }
            }
            for (Map.Entry<String, Parser[]> scope : graphs.scopes.entrySet()) {
              String label = "scope/" + scope.getKey() + "/thread" + threadIndex + "/round" + roundIndex;
              try {
                assertEquals(label, scopeExpected.get(scope.getKey()),
                    scopePrefixes(scope.getValue()[0], scope.getValue()[1], memos, diagnostics, mode));
              } catch (AssertionError | RuntimeException failure) {
                failures.add(label + ": " + failure);
              }
              mode += 3;
              parses.addAndGet(3);
            }
            return null;
          }));
        }
        start.countDown();
        for (Future<?> thread : threads) {
          thread.get(120, TimeUnit.SECONDS);
        }
      }
    } finally {
      pool.shutdownNow();
    }
    assertTrue(failures.size() + " failures\n" + String.join("\n", failures.subList(0, Math.min(20, failures.size()))),
        failures.isEmpty());
    int perThread = VARIANTS.size() * cases.size() + REFERENCE_VARIANTS.size() * graphRows.size() + 2 * 3;
    assertEquals(THREADS * ROUNDS * perThread, parses.get());
  }

  /**
   * first-use-parked in shared-graph.tsv: a thread stopped inside the first resolution of a
   * ReferenceParser leaves the cache unpublished, holds no lock, and another context on the same
   * graph resolves and finishes on its own; both match the row.
   */
  @Test
  public void aThreadParkedInAFirstResolutionDoesNotBlockOrCorruptAnother() throws Exception {
    String[] row = rows("shared-graph.tsv", 8).stream()
        .filter(c -> c[0].equals("list-nested"))
        .findFirst()
        .orElseThrow();
    CountDownLatch parked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean first = new AtomicBoolean(true);
    Parser elemList = new ReferenceParser(parser -> {
      if (first.compareAndSet(true, false)) {
        parked.countDown();
        try {
          release.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
      }
      return parser instanceof GrammarRule && GrammarRule.nameOf(parser).equals("List");
    });
    Parser list = listGraph(reference("predicate-rule"), elemList).get("List");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> stopped = pool.submit(() -> {
        graphObservation("parked", list, row, Memoization.OFF, ParseOptions.Diagnostics.DETAILED);
        return null;
      });
      assertTrue("the first resolution was reached", parked.await(60, TimeUnit.SECONDS));
      Future<?> other = pool.submit(() -> {
        graphObservation("other", list, row, Memoization.OFF, ParseOptions.Diagnostics.DETAILED);
        return null;
      });
      other.get(60, TimeUnit.SECONDS);
      assertFalse("still parked while the other context finished", stopped.isDone());
      release.countDown();
      stopped.get(60, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  // ---- lazy delegates of user parsers that inherit AbstractParser.parse (#1007)

  /**
   * A user parser whose inherited parse runs the body that createParser builds. The body is new on
   * every call and is not a child yet, so the first use attaches it: a graph change.
   */
  static final class LazyDelegate extends LazyAbstractParser {
    private static final long serialVersionUID = 1L;
    final transient Function<LazyDelegate, Parser> body;
    final AtomicInteger creations = new AtomicInteger();
    final AtomicInteger hooks = new AtomicInteger();

    LazyDelegate(Function<LazyDelegate, Parser> body) {
      this.body = body;
    }

    @Override
    public Parser createParser() {
      creations.incrementAndGet();
      return body.apply(this);
    }

    @Override
    public void createParserAndThen(Parser createdParser) {
      hooks.incrementAndGet();
    }

    @Override
    public void prepareChildren(Parsers childrenContainer) {
    }

    @Override
    public ChildOccurs getChildOccurs() {
      return ChildOccurs.single;
    }
  }

  /** The shared-graph.tsv grammar with List's body {@code Elem*} built by a lazy delegate. */
  private static final class DelegateGraph {
    final LazyDelegate delegate;
    final Parser root;
    final Map<String, Parser> rules = new LinkedHashMap<>();

    DelegateGraph(Function<String, Parser> reference, Function<LazyDelegate, Parser> body) {
      delegate = new LazyDelegate(body);
      Parser list = new NamedChain("List", delegate);
      Parser elem = new NamedChoice("Elem", new Chain(w("("), reference.apply("List"), w(")")), w("é"), w("😀"));
      Parser top = new NamedChain("Top", reference.apply("List"), w("!"));
      root = new PseudoRootParser(new Parsers(list, elem, top));
      rules.put("List", list);
      rules.put("Elem", elem);
      rules.put("Top", top);
    }
  }

  private static Function<LazyDelegate, Parser> listBody(Function<String, Parser> reference) {
    return owner -> new ZeroOrMore(Name.of("ListBody"), reference.apply("Elem"));
  }

  /**
   * The graph after its first uses: one body, attached once below the delegate, inside the root's
   * scope, and found by a name lookup from another rule.
   */
  private static String delegateProperties(DelegateGraph graph) {
    Parser body = graph.delegate.getParser();
    return graph.delegate.creations.get() + ";" + graph.delegate.getChildren().size() + ";"
        + identity(graph.delegate.getChildren().get(0), body) + ";"
        + identity(body.getParent().orElse(null), graph.delegate) + ";"
        + identity(body.getChildren().get(0).getRoot(), graph.root) + ";"
        + identity(graph.rules.get("Elem").getParser(Name.of("ListBody")).orElse(null), body);
  }

  private static final String ATTACHED_ONCE = "1;1;same;same;same;same";

  /** A by-name graph whose delegate's first createParser call throws. */
  private static DelegateGraph flakyDelegateGraph() {
    Function<String, Parser> reference = reference("by-name");
    return new DelegateGraph(reference, owner -> {
      if (owner.creations.get() == 1) {
        throw new IllegalStateException("first creation fails");
      }
      return new ZeroOrMore(Name.of("ListBody"), reference.apply("Elem"));
    });
  }

  // ---- lazy children supplied into one retained Parsers (#1014)

  /** Runs before a supplier's build with the supplier's index and its number of earlier calls. */
  private interface SupplyHook {
    void before(int index, int earlierCalls);
  }

  /**
   * One Parsers of counted suppliers that a lazy parser keeps and returns from getLazyParsers, so
   * every first use prepares the same instance (Rust: a OnceLock beside the grammar).
   */
  private static final class Supplied {
    final List<AtomicInteger> calls = new ArrayList<>();
    final Parsers parsers;

    @SuppressWarnings("unchecked")
    Supplied(List<Supplier<Parser>> builds, SupplyHook hook) {
      Supplier<Parser>[] suppliers = new Supplier[builds.size()];
      for (int index = 0; index < builds.size(); index++) {
        int supplier = index;
        AtomicInteger counter = new AtomicInteger();
        calls.add(counter);
        suppliers[index] = () -> {
          hook.before(supplier, counter.getAndIncrement());
          return builds.get(supplier).get();
        };
      }
      parsers = new Parsers(suppliers);
    }

    String calls() {
      return calls.stream().map(String::valueOf).collect(Collectors.joining("/"));
    }
  }

  /** Children A ::= 'a', B ::= 'b', C ::= 'c' in declaration order. */
  private static Supplied abc(SupplyHook hook) {
    return new Supplied(List.of(() -> new NamedChain("A", w("a")), () -> new NamedChain("B", w("b")),
        () -> new NamedChain("C", w("c"))), hook);
  }

  /** abc's second supplier fails on its first call; the owner parses abc twice. */
  private static String suppliedFailureRetried(Runnable failure) {
    Supplied supplied = abc((index, earlierCalls) -> {
      if (index == 1 && earlierCalls == 0) {
        failure.run();
      }
    });
    Parser owner = new SupplierChain("Abc", () -> supplied.parsers);
    String first;
    try {
      first = parse(owner, "abc");
    } catch (IllegalStateException | AssertionError expected) {
      first = "error";
    }
    return first + ";" + parse(owner, "abc") + ";" + owner.getChildren().size() + ";" + supplied.calls();
  }

  private static final String FIRST_SUPPLY_FAILS = "first supply fails";

  /** A named lazy choice that returns one retained Parsers, found by name and by predicate. */
  static final class RetainedChoice extends LazyChoice implements GrammarRule {
    private static final long serialVersionUID = 1L;
    private final String ruleName;
    private final transient Parsers children;

    RetainedChoice(String ruleName, Parsers children) {
      super(Name.of(ruleName));
      this.ruleName = ruleName;
      this.children = children;
    }

    @Override
    public Parsers getLazyParsers() {
      return children;
    }

    @Override
    public String grammarRuleName() {
      return ruleName;
    }
  }

  /**
   * The shared-graph.tsv grammar whose Elem alternatives come from one retained Parsers. Lazy
   * children get no parent, and a new Chain holding the List instance would become List's parent
   * (shared-parent-last-writer), so the first supplier returns one Chain built with the graph,
   * whose parent is Elem and whose reference to List takes the reference route.
   */
  private static final class SuppliedGraph {
    final Supplied supplied;
    final Map<String, Parser> rules = new LinkedHashMap<>();

    SuppliedGraph(Function<String, Parser> reference) {
      Parser parenthesized = new Chain(w("("), reference.apply("List"), w(")"));
      supplied = new Supplied(List.of(() -> parenthesized, () -> w("é"), () -> w("😀")),
          (index, earlierCalls) -> {
            if (index == 1 && earlierCalls == 0) {
              throw new IllegalStateException(FIRST_SUPPLY_FAILS);
            }
          });
      Parser list = new NamedChain("List", new ZeroOrMore(reference.apply("Elem")));
      Parser elem = new RetainedChoice("Elem", supplied.parsers);
      parenthesized.setParent(elem);
      Parser top = new NamedChain("Top", reference.apply("List"), w("!"));
      new PseudoRootParser(new Parsers(list, elem, top));
      rules.put("List", list);
      rules.put("Elem", elem);
      rules.put("Top", top);
    }
  }

  private static final String SUPPLIED_ONCE = "1;2/2/1;3";

  /**
   * lazy-children first use in shared-graph.tsv (#1014): THREADS threads, each parsing every row in
   * two contexts, start together on a graph nobody used yet whose Elem alternatives come from one
   * retained Parsers; its second supplier throws on its first call. Exactly one parse gets that
   * failure, every other parse matches its row, and the list holds all three alternatives.
   */
  @Test
  public void concurrentFirstUsesOfLazyChildrenPublishOnlyTheCompleteList() throws Exception {
    List<String[]> graphRows = rows("shared-graph.tsv", 8);
    Memoization[] memos = Memoization.values();
    ParseOptions.Diagnostics[] diagnostics = ParseOptions.Diagnostics.values();
    int modes = memos.length * diagnostics.length;
    int contexts = 2;
    List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
    AtomicInteger parses = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    try {
      for (int round = 0; round < ROUNDS; round++) {
        Map<String, SuppliedGraph> graphs = new LinkedHashMap<>();
        Map<String, AtomicInteger> supplyFailures = new LinkedHashMap<>();
        for (String variantName : REFERENCE_VARIANTS) {
          graphs.put(variantName, new SuppliedGraph(reference(variantName)));
          supplyFailures.put(variantName, new AtomicInteger());
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> threads = new ArrayList<>();
        for (int thread = 0; thread < THREADS; thread++) {
          int threadIndex = thread;
          int roundIndex = round;
          threads.add(pool.submit(() -> {
            start.await();
            int shift = threadIndex % 2 == 0 ? 0 : threadIndex;
            int mode = threadIndex + roundIndex;
            for (Map.Entry<String, SuppliedGraph> graph : graphs.entrySet()) {
              for (int row = 0; row < graphRows.size(); row++) {
                String[] c = graphRows.get((row + shift) % graphRows.size());
                for (int context = 0; context < contexts; context++) {
                  int index = mode++ % modes;
                  Memoization memo = memos[index / diagnostics.length];
                  ParseOptions.Diagnostics diagnostic = diagnostics[index % diagnostics.length];
                  String label = c[0] + "/" + graph.getKey() + "/" + memo + "/" + diagnostic + "/thread"
                      + threadIndex + "/context" + context + "/round" + roundIndex;
                  try {
                    graphObservation(label, graph.getValue().rules.get(c[1]), c, memo, diagnostic);
                  } catch (IllegalStateException failure) {
                    if (FIRST_SUPPLY_FAILS.equals(failure.getMessage())) {
                      supplyFailures.get(graph.getKey()).incrementAndGet();
                    } else {
                      failures.add(label + ": " + failure);
                    }
                  } catch (AssertionError | RuntimeException failure) {
                    failures.add(label + ": " + failure);
                  }
                  parses.incrementAndGet();
                }
              }
            }
            return null;
          }));
        }
        start.countDown();
        for (Future<?> thread : threads) {
          thread.get(120, TimeUnit.SECONDS);
        }
        for (Map.Entry<String, SuppliedGraph> graph : graphs.entrySet()) {
          SuppliedGraph value = graph.getValue();
          String actual = supplyFailures.get(graph.getKey()).get() + ";" + value.supplied.calls() + ";"
              + value.rules.get("Elem").getChildren().size();
          if (false == actual.equals(SUPPLIED_ONCE)) {
            failures.add(graph.getKey() + "/round" + round + " properties: " + actual);
          }
        }
      }
    } finally {
      pool.shutdownNow();
    }
    assertTrue(failures.size() + " failures\n" + String.join("\n", failures.subList(0, Math.min(20, failures.size()))),
        failures.isEmpty());
    assertEquals(THREADS * ROUNDS * REFERENCE_VARIANTS.size() * graphRows.size() * contexts, parses.get());
  }

  /**
   * lazy-delegate first use in shared-graph.tsv: 8 threads, each parsing every row in two contexts,
   * start together on graphs whose delegate nobody used yet (first-use) and on graphs whose
   * delegate was materialized before sharing (materialized). Every parse matches its row; afterwards
   * the delegate created and attached one body.
   */
  @Test
  public void concurrentFirstUsesOfALazyDelegateCreateAndAttachOneBody() throws Exception {
    List<String[]> graphRows = rows("shared-graph.tsv", 8);
    Memoization[] memos = Memoization.values();
    ParseOptions.Diagnostics[] diagnostics = ParseOptions.Diagnostics.values();
    int modes = memos.length * diagnostics.length;
    int contexts = 2;
    List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
    AtomicInteger parses = new AtomicInteger();
    int graphCount = 0;
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    try {
      for (int round = 0; round < ROUNDS; round++) {
        Map<String, DelegateGraph> graphs = new LinkedHashMap<>();
        for (String variantName : REFERENCE_VARIANTS) {
          for (String state : List.of("first-use", "materialized")) {
            DelegateGraph graph = new DelegateGraph(reference(variantName), listBody(reference(variantName)));
            if (state.equals("materialized")) {
              // The freeze step: build complete, then create the delegate's body before sharing.
              graph.delegate.getParser();
            }
            graphs.put(variantName + "/" + state, graph);
          }
        }
        graphCount = graphs.size();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> threads = new ArrayList<>();
        for (int thread = 0; thread < THREADS; thread++) {
          int threadIndex = thread;
          int roundIndex = round;
          threads.add(pool.submit(() -> {
            start.await();
            int shift = threadIndex % 2 == 0 ? 0 : threadIndex;
            int mode = threadIndex + roundIndex;
            for (Map.Entry<String, DelegateGraph> graph : graphs.entrySet()) {
              for (int row = 0; row < graphRows.size(); row++) {
                String[] c = graphRows.get((row + shift) % graphRows.size());
                for (int context = 0; context < contexts; context++) {
                  int index = mode++ % modes;
                  Memoization memo = memos[index / diagnostics.length];
                  ParseOptions.Diagnostics diagnostic = diagnostics[index % diagnostics.length];
                  String label = c[0] + "/" + graph.getKey() + "/" + memo + "/" + diagnostic + "/thread"
                      + threadIndex + "/context" + context + "/round" + roundIndex;
                  try {
                    graphObservation(label, graph.getValue().rules.get(c[1]), c, memo, diagnostic);
                  } catch (AssertionError | RuntimeException failure) {
                    failures.add(label + ": " + failure);
                  }
                  parses.incrementAndGet();
                }
              }
            }
            return null;
          }));
        }
        start.countDown();
        for (Future<?> thread : threads) {
          thread.get(120, TimeUnit.SECONDS);
        }
        for (Map.Entry<String, DelegateGraph> graph : graphs.entrySet()) {
          String actual = delegateProperties(graph.getValue());
          if (false == actual.equals(ATTACHED_ONCE)) {
            failures.add(graph.getKey() + "/round" + round + " properties: " + actual);
          }
        }
      }
    } finally {
      pool.shutdownNow();
    }
    assertTrue(failures.size() + " failures\n" + String.join("\n", failures.subList(0, Math.min(20, failures.size()))),
        failures.isEmpty());
    assertEquals(THREADS * ROUNDS * graphCount * graphRows.size() * contexts, parses.get());
  }

  /** Stops the first caller until the test releases it; later callers pass. */
  private static void parkOnce(AtomicBoolean first, CountDownLatch parked, CountDownLatch release) {
    if (first.compareAndSet(true, false)) {
      parked.countDown();
      try {
        release.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
    }
  }

  /**
   * lazy-delegate-parked in shared-graph.tsv: creating and attaching the body is a graph change, so
   * it runs once under the delegate's monitor. A thread stopped inside createParser, or inside the
   * attachment (the body's setParent), holds it; another context's first use waits on that monitor
   * instead of creating a second body or parsing a body that is not attached yet, then both match
   * the row. (Reference resolution holds no lock; see the first-use-parked test.)
   */
  @Test
  public void aFirstUseParkedInTheCreationMakesTheOtherFirstUseWaitForTheOneBody() throws Exception {
    String[] row = rows("shared-graph.tsv", 8).stream()
        .filter(c -> c[0].equals("list-nested"))
        .findFirst()
        .orElseThrow();
    for (String parkedIn : List.of("createParser", "attachment")) {
      CountDownLatch parked = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      AtomicBoolean first = new AtomicBoolean(true);
      Function<String, Parser> reference = reference("by-name");
      DelegateGraph graph = new DelegateGraph(reference, owner -> {
        if (parkedIn.equals("createParser")) {
          parkOnce(first, parked, release);
          return new ZeroOrMore(Name.of("ListBody"), reference.apply("Elem"));
        }
        return new ZeroOrMore(Name.of("ListBody"), reference.apply("Elem")) {
          private static final long serialVersionUID = 1L;

          @Override
          public void setParent(Parser parent) {
            parkOnce(first, parked, release);
            super.setParent(parent);
          }
        };
      });
      Parser list = graph.rules.get("List");
      List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
      Function<String, Thread> observer = label -> new Thread(() -> {
        try {
          graphObservation(label, list, row, Memoization.OFF, ParseOptions.Diagnostics.DETAILED);
        } catch (Throwable failure) {
          errors.add(failure);
        }
      }, parkedIn + "/" + label);
      Thread stopped = observer.apply("parked");
      Thread other = observer.apply("other");
      try {
        stopped.start();
        assertTrue(parkedIn + " was reached", parked.await(60, TimeUnit.SECONDS));
        other.start();
        java.lang.management.ThreadMXBean threads = java.lang.management.ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        java.lang.management.LockInfo waitedOn = null;
        while (other.isAlive() && System.nanoTime() < deadline) {
          java.lang.management.ThreadInfo info = threads.getThreadInfo(other.getId());
          if (info != null && info.getThreadState() == Thread.State.BLOCKED) {
            waitedOn = info.getLockInfo();
            break;
          }
          Thread.sleep(1);
        }
        assertTrue(parkedIn + ": the other first use waits for the parked one: " + errors,
            waitedOn != null && waitedOn.getIdentityHashCode() == System.identityHashCode(graph.delegate));
        release.countDown();
        stopped.join(60_000);
        other.join(60_000);
        assertFalse(parkedIn, stopped.isAlive() || other.isAlive());
        assertTrue(parkedIn + ": " + errors, errors.isEmpty());
        assertEquals(parkedIn, ATTACHED_ONCE, delegateProperties(graph));
      } finally {
        release.countDown();
      }
    }
  }

  // ---- construction and identity observations

  public static final class FlakyParser extends LazyChain {
    private static final long serialVersionUID = 1L;
    static final AtomicInteger constructions = new AtomicInteger();

    public FlakyParser() {
      if (constructions.getAndIncrement() == 0) {
        throw new IllegalStateException("first construction fails");
      }
    }

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(w("f"));
    }
  }

  public static final class KindParser extends LazyChain {
    private static final long serialVersionUID = 1L;

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(w("k"));
    }
  }

  public static final class EagerCycleParser extends Chain {
    private static final long serialVersionUID = 1L;

    public EagerCycleParser() {
      super(new Parsers(EagerCycleParser.class));
    }
  }

  /** A RootParserIndicator scope: references below it search only the scope's subtree. */
  static final class ScopeChoice extends Choice implements RootParserIndicator, GrammarRule {
    private static final long serialVersionUID = 1L;

    ScopeChoice(Parser... children) {
      super(Name.of("Scope"), children);
    }

    @Override
    public String grammarRuleName() {
      return "Scope";
    }
  }

  /**
   * Outer ::= Scope; Scope ::= '(' Scope ')' | Item | 'x'; Item ::= 'a' is registered outside the
   * scope. With the indicator, Scope still resolves itself but not the outer Item.
   */
  private static Parser scopeGraph(boolean indicator, Function<String, Parser> reference) {
    Parser[] alternatives = {
        new Chain(w("("), reference.apply("Scope"), w(")")), reference.apply("Item"), w("x")};
    Parser scope = indicator ? new ScopeChoice(alternatives) : new NamedChoice("Scope", alternatives);
    Parser outer = new NamedChain("Outer", scope);
    new PseudoRootParser(new Parsers(outer, new NamedChain("Item", w("a"))));
    return outer;
  }

  /** The same observation in every memo policy and diagnostics mode, or the differing labels. */
  private static String everyOption(Function<ParseContext, String> observation, String input) {
    Set<String> observed = new java.util.TreeSet<>();
    for (Memoization memo : Memoization.values()) {
      for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
        try (ParseContext context = ParseContext.withOptions(StringSource.createRootSource(input),
            ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
          observed.add(observation.apply(context));
        }
      }
    }
    return String.join("|", observed);
  }

  private static String scopeObservation(Function<String, Parser> reference) {
    Parser scoped = scopeGraph(true, reference);
    Parser control = scopeGraph(false, reference);
    // A failure, then a success on the same graph, then the control without the indicator.
    return String.join(";", everyOption(context -> prefix(scoped, context), "a"),
        everyOption(context -> prefix(scoped, context), "(x)"),
        everyOption(context -> prefix(control, context), "a"));
  }

  private static String prefix(Parser parser, ParseContext context) {
    Parsed parsed = parser.parse(context);
    return parsed.isSucceeded() + "/" + context.getConsumedPosition().value();
  }

  /** A marked parser: neither marker has a caller, so construction and parsing ignore them. */
  public static final class MarkedParser extends LazyChain implements StaticParser, Initializable {
    private static final long serialVersionUID = 1L;
    static final AtomicInteger initializations = new AtomicInteger();

    @Override
    public void initialize() {
      initializations.incrementAndGet();
    }

    @Override
    public Parsers getLazyParsers() {
      return new Parsers(w("m"));
    }
  }

  /** Writes parse-local state into the context it parses with. */
  static final class Recording extends Chain {
    private static final long serialVersionUID = 1L;

    Recording(Parser child) {
      super(child);
    }

    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
      parseContext.getGlobalScopeTreeMap().put(Name.of("seen"), Boolean.TRUE);
      return super.parse(parseContext, tokenKind, invertMatch);
    }
  }

  private static String metaShape(Parser parser, CreateMetaTokenSpecifier meta) {
    try (ParseContext context = new ParseContext(StringSource.createRootSource("aa"), meta)) {
      assertTrue(parser.parse(context).isSucceeded());
      return context.getCurrent().getTokens().stream()
          .map(ParserConstructionParityTest::shape)
          .collect(Collectors.joining(";"));
    }
  }

  private static String parse(Parser parser, String input) {
    try (ParseContext context = new ParseContext(StringSource.createRootSource(input))) {
      Parsed parsed = parser.parse(context);
      return parsed.isSucceeded() + "/" + context.getConsumedPosition().value();
    }
  }

  private static String identity(Object first, Object second) {
    return first == second ? "same" : "distinct";
  }

  private static String failure(Runnable action) {
    try {
      action.run();
      return "ok";
    } catch (RuntimeException exception) {
      return exception.getClass().getSimpleName()
          + (exception.getCause() == null ? "" : ":" + exception.getCause().getClass().getSimpleName());
    }
  }

  private static String duplicate(Parser root) {
    try {
      new ParserNameContext(root);
      return "ok";
    } catch (IllegalStateException exception) {
      Matcher matcher = Pattern.compile("Duplicate key (\\S+)").matcher(exception.getMessage());
      assertTrue(exception.getMessage(), matcher.find());
      return "duplicate:" + matcher.group(1);
    }
  }

  private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (type.isInstance(current)) {
        return true;
      }
    }
    return false;
  }

  private static String fallbackName(Object parser) {
    String name = GrammarRule.nameOf(parser);
    return name.equals(parser.getClass().getName()) ? "fallback" : name;
  }

  private static String observe(String id) {
    switch (id) {
      case "factory-class-shared":
        return identity(Parser.get(ItemParser.class), Parser.get(ItemParser.class));
      case "factory-new-instance":
        return identity(Parser.newInstance(ItemParser.class), Parser.newInstance(ItemParser.class));
      case "factory-supplier-same":
        return identity(Parser.get(ITEM), Parser.get(ITEM));
      case "factory-supplier-distinct": {
        Supplier<Parser> first = () -> w("s");
        Supplier<Parser> second = () -> w("s");
        return identity(Parser.get(first), Parser.get(second));
      }
      case "factory-get-supplier":
        return identity(Parser.getSupplier(ItemParser.class).get(), Parser.getSupplier(ItemParser.class).get());
      case "factory-failure-retried": {
        String first = failure(() -> Parser.get(FlakyParser.class)).startsWith("RuntimeException") ? "error" : "ok";
        FlakyParser second = Parser.get(FlakyParser.class);
        return first + ";" + (second == null ? "null" : "instance") + ";"
            + identity(second, Parser.get(FlakyParser.class));
      }
      case "factory-kind": {
        Parser tagged = ParserFactoryByClass.get(ASTNodeKind.Other, KindParser.class);
        return identity(tagged, ParserFactoryByClass.get(ASTNodeKind.Other, KindParser.class)) + ";"
            + identity(tagged, ParserFactoryByClass.get(ASTNodeKind.ChoicedOperand, KindParser.class)) + ";"
            + identity(tagged, Parser.get(KindParser.class));
      }
      case "class-and-kind-without-kind":
        return new ParserFactoryByClass.ClassAndKind(KindParser.class).kind().isPresent() ? "present" : "empty";
      case "eager-construction-cycle":
        try {
          Parser.get(EagerCycleParser.class);
          return "ok";
        } catch (RuntimeException | StackOverflowError failure) {
          return causedBy(failure, StackOverflowError.class) ? "StackOverflowError" : failure.toString();
        }
      case "lazy-construction-cycle":
        return parse(Parser.get(ItemParser.class), "((x))");
      case "lazy-delegate-built-once": {
        DelegateGraph graph = new DelegateGraph(reference("by-name"), listBody(reference("by-name")));
        for (int parse = 0; parse < 3; parse++) {
          assertEquals("true/1", parse(graph.rules.get("List"), "é"));
        }
        return String.valueOf(graph.delegate.creations.get());
      }
      case "lazy-delegate-failure-retried": {
        DelegateGraph graph = flakyDelegateGraph();
        String first = failure(() -> parse(graph.rules.get("List"), "é")).equals("ok") ? "ok" : "error";
        return first + ";" + parse(graph.rules.get("List"), "é") + ";" + graph.delegate.creations.get();
      }
      case "lazy-delegate-hook-each-call": {
        DelegateGraph graph = new DelegateGraph(reference("by-name"), listBody(reference("by-name")));
        for (int parse = 0; parse < 3; parse++) {
          assertEquals("true/1", parse(graph.rules.get("List"), "é"));
        }
        return graph.delegate.creations.get() + ";" + graph.delegate.hooks.get();
      }
      case "lazy-delegate-attach": {
        DelegateGraph graph = flakyDelegateGraph();
        List<Integer> sizes = new ArrayList<>();
        sizes.add(graph.delegate.getChildren().size());
        assertFalse(failure(() -> parse(graph.rules.get("List"), "é")).equals("ok"));
        sizes.add(graph.delegate.getChildren().size());
        for (int parse = 0; parse < 2; parse++) {
          assertEquals("true/1", parse(graph.rules.get("List"), "é"));
          sizes.add(graph.delegate.getChildren().size());
        }
        return sizes.stream().map(String::valueOf).collect(Collectors.joining(";")) + ";"
            + identity(graph.delegate.getParser().getParent().orElse(null), graph.delegate);
      }
      case "lazy-delegate-walk-during-attach": {
        DelegateGraph graph = new DelegateGraph(reference("by-name"), listBody(reference("by-name")));
        java.util.Iterator<Parser> walk = graph.delegate.getChildren().iterator();
        assertEquals("true/1", parse(graph.rules.get("List"), "é"));
        int walked = 0;
        while (walk.hasNext()) {
          walk.next();
          walked++;
        }
        return walked + ";" + graph.delegate.getChildren().size();
      }
      case "lazy-delegate-reentrant-getter": {
        Function<String, Parser> reference = reference("by-name");
        List<String> seen = new ArrayList<>();
        DelegateGraph[] graph = new DelegateGraph[1];
        graph[0] = new DelegateGraph(reference, owner -> {
          seen.add(String.valueOf(owner.getChildren().size()));
          seen.add(identity(owner.getRoot(), graph[0].root));
          seen.add(owner.getParser(Name.of("Elem")).isPresent() ? "present" : "absent");
          return new ZeroOrMore(Name.of("ListBody"), reference.apply("Elem"));
        });
        return parse(graph[0].rules.get("List"), "é") + ";" + String.join(";", seen);
      }
      case "lazy-delegate-self-reentry": {
        DelegateGraph graph = new DelegateGraph(reference("by-name"), owner -> owner.getParser());
        try {
          return parse(graph.rules.get("List"), "é");
        } catch (StackOverflowError expected) {
          return "StackOverflowError";
        }
      }
      case "lazy-delegate-detached-cache": {
        DelegateGraph graph = new DelegateGraph(reference("by-name"), listBody(reference("by-name")));
        String first = parse(graph.rules.get("List"), "é");
        graph.delegate.getChildren().clear();
        int cleared = graph.delegate.getChildren().size();
        return first + ";" + cleared + ";" + parse(graph.rules.get("List"), "é") + ";"
            + graph.delegate.getChildren().size() + ";" + graph.delegate.creations.get();
      }
      case "lazy-delegate-lookup-before-materialize": {
        DelegateGraph graph = new DelegateGraph(reference("by-name"), listBody(reference("by-name")));
        Parser elem = graph.rules.get("Elem");
        String before = elem.getParser(Name.of("ListBody")).isPresent() ? "present" : "absent";
        graph.delegate.getParser();
        return before + ";" + (elem.getParser(Name.of("ListBody")).isPresent() ? "present" : "absent");
      }
      case "lazy-children-once": {
        AtomicInteger calls = new AtomicInteger();
        Parser lazy = new LazyChain() {
          private static final long serialVersionUID = 1L;

          @Override
          public Parsers getLazyParsers() {
            calls.incrementAndGet();
            return new Parsers(w("a"));
          }
        };
        for (int index = 0; index < 3; index++) {
          assertEquals("true/1", parse(lazy, "a"));
        }
        return String.valueOf(calls.get());
      }
      case "lazy-children-failure-retried":
        return suppliedFailureRetried(() -> {
          throw new IllegalStateException(FIRST_SUPPLY_FAILS);
        });
      case "lazy-children-error-retried":
        return suppliedFailureRetried(() -> {
          throw new AssertionError(FIRST_SUPPLY_FAILS);
        });
      case "lazy-children-lookup-after-failure": {
        Supplied supplied = abc((index, earlierCalls) -> {
          if (index == 1 && earlierCalls == 0) {
            throw new IllegalStateException(FIRST_SUPPLY_FAILS);
          }
        });
        Parser owner = new SupplierChain("Abc", () -> supplied.parsers);
        String first = failure(() -> owner.getParser(Name.of("B")));
        Optional<Parser> found = owner.getParser(Name.of("B"));
        Parser second = supplied.parsers.size() > 1 ? supplied.parsers.get(1) : null;
        return first + ";" + (found.isPresent() ? "present" : "absent") + ";" + identity(found.orElse(null), second);
      }
      case "lazy-children-empty": {
        Supplied supplied = new Supplied(List.of(), (index, earlierCalls) -> {
        });
        Parser owner = new SupplierChain("Empty", () -> supplied.parsers);
        return parse(owner, "") + ";" + owner.getChildren().size() + ";"
            + identity(supplied.parsers.list(), supplied.parsers.list());
      }
      case "lazy-children-published-once": {
        Supplied supplied = abc((index, earlierCalls) -> {
        });
        Parser owner = new SupplierChain("Abc", () -> supplied.parsers);
        for (int index = 0; index < 3; index++) {
          assertEquals("true/3", parse(owner, "abc"));
        }
        return supplied.calls() + ";" + identity(supplied.parsers.list(), supplied.parsers.list()) + ";"
            + identity(supplied.parsers.get(1), owner.getChildren().get(1));
      }
      case "lazy-children-null": {
        Supplied supplied = new Supplied(List.of(() -> w("a"), () -> null), (index, earlierCalls) -> {
        });
        Parser owner = new SupplierChain("Null", () -> supplied.parsers);
        return supplied.parsers.size() + ";" + supplied.parsers.get(1) + ";" + failure(() -> parse(owner, "a"));
      }
      case "lazy-children-reentrant": {
        Parsers[] own = new Parsers[1];
        Supplied supplied = abc((index, earlierCalls) -> {
          if (index == 1 && earlierCalls == 0) {
            own[0].size();
          }
        });
        own[0] = supplied.parsers;
        String first = failure(() -> supplied.parsers.size());
        return first + ";" + supplied.parsers.size() + ";" + supplied.calls();
      }
      case "shared-graph-independent-contexts": {
        Parser root = Parser.get(RootParser.class);
        return parse(root, "x!") + ";" + parse(root, "y") + ";" + parse(root, "x?");
      }
      case "name-context-duplicate":
        return duplicate(new NamedChain("Root", new NamedChain("Item", w("a")), new NamedChain("Item", w("b"))));
      case "name-context-reused-instance": {
        Parser item = new NamedChain("Item", w("a"));
        Parser root = new NamedChain("Root", new Chain(Name.of("Left"), item), new Chain(Name.of("Right"), item));
        return new ParserNameContext(root).get(Name.of("Item")).map(GrammarRule::nameOf).orElse("absent");
      }
      case "name-context-unregistered":
        return new ParserNameContext(new NamedChain("Root", w("a"))).get(Name.of("Missing"))
            .map(GrammarRule::nameOf).orElse("absent");
      case "name-context-default-names":
        return duplicate(new NamedChain("Root", w("a"), w("b")));
      case "by-name-first-match": {
        Parser root = new NamedChain("Root", new ReferenceByNameParser(Name.of("Item")));
        new PseudoRootParser(new Parsers(root, new NamedChain("Item", w("a")), new NamedChain("Item", w("b"))));
        return parse(root, "a") + ";" + parse(root, "b");
      }
      case "by-name-late-registration": {
        Parser root = new NamedChain("Root", new ReferenceByNameParser(Name.of("Late")));
        String before = parse(root, "x");
        root.getChildren().add(new NamedChain("Late", w("x")));
        return before + ";" + parse(root, "xx");
      }
      case "predicate-miss-cached": {
        Parser root = new NamedChain("Root", ReferenceParser.of(Name.of("Late")));
        String before = parse(root, "x");
        root.getChildren().add(new NamedChain("Late", w("x")));
        return before + ";" + parse(root, "xx");
      }
      case "predicate-evaluated-once": {
        AtomicInteger evaluations = new AtomicInteger();
        Parser root = new NamedChain("Root", new ReferenceParser(parser -> {
          evaluations.incrementAndGet();
          return parser.getName().equals(Name.of("Item"));
        }));
        new PseudoRootParser(new Parsers(root, new NamedChain("Item", w("a"))));
        assertEquals("true/1", parse(root, "a"));
        assertTrue(evaluations.get() > 0);
        evaluations.set(0);
        assertEquals("true/1", parse(root, "a"));
        assertEquals("false/0", parse(root, "b"));
        return String.valueOf(evaluations.get());
      }
      case "referencer-uncached": {
        Referencer reference = new Referencer.AbstractReference(parser -> parser.getName().equals(Name.of("Late"))) {
          private static final long serialVersionUID = 1L;
        };
        Parser root = new NamedChain("Root", w("x"));
        String before = reference.getReference(root).isPresent() ? "present" : "absent";
        root.getChildren().add(new NamedChain("Late", w("x")));
        return before + ";" + (reference.getReference(root).isPresent() ? "present" : "absent");
      }
      case "shared-parent-last-writer": {
        Parser shared = new NamedChain("Shared", w("a"));
        Parser first = new Chain(Name.of("First"), shared);
        Parser second = new Chain(Name.of("Second"), shared);
        return shared.getParent().get() == second ? "second" : identity(shared.getParent().get(), first);
      }
      case "parent-cycle-walk": {
        Parser first = new NamedChain("A", w("a"));
        Parser second = new NamedChain("B", w("b"));
        first.setParent(second);
        second.setParent(first);
        return failure(() -> first.findFirstToParent(parser -> false));
      }
      case "left-recursion": {
        Parser recursive = new NamedChain("A", new ReferenceByNameParser(Name.of("A")), w("x"));
        new PseudoRootParser(new Parsers(recursive));
        try {
          return parse(recursive, "xx");
        } catch (ParseLimitException limit) {
          assertTrue(limit.getMessage(), limit.getMessage().startsWith("ULX-PARSE-DEPTH-LIMIT"));
          return "resource_limit";
        }
      }
      case "pseudo-root-as-child":
        return failure(() -> new Chain(new PseudoRootParser(new Parsers(w("a")))));
      case "pseudo-root-multi":
        return failure(() -> parse(new PseudoRootParser(new Parsers(w("a"), w("b"))), "a"));
      case "source-before-parse":
        return failure(() -> new NotPropagatableSource(w("a")).getInvertMatchToChild());
      case "destination-without-source":
        return String.valueOf(w("a").getInvertMatchFromParent());
      case "destination-under-source": {
        NotPropagatableSource not = new NotPropagatableSource(w("a"));
        assertEquals("true/1", parse(not, "b"));
        return String.valueOf(not.getChild().getInvertMatchFromParent());
      }
      case "token-parsed-by":
        try (ParseContext context = new ParseContext(StringSource.createRootSource("a"))) {
          Token token = Parser.get(LeafParser.class).parse(context).getRootToken();
          Parser.checkTokenParsedBySpecifiedParser(token, LeafParser.class);
          return Parser.isTokenParsedBySpecifiedParser(token, LeafParser.class) + ";"
              + Parser.isTokenParsedBySpecifiedParser(token, LazyChain.class);
        }
      case "parsers-specifier":
        return new ParsersSpecifier(LeafParser.class).contains(Parser.get(LeafParser.class)) + ";"
            + new ParsersSpecifier(LazyChain.class).contains(Parser.get(LeafParser.class));
      case "grammar-rule-names":
        return String.join(";", GrammarRule.nameOf(new NamedChain("Item", w("x"))),
            GrammarRule.nameOf(Parser.get(ItemParser.class)), GrammarRule.nameOf(w("a")),
            fallbackName(new GrammarRule() {
              @Override
              public String grammarRuleName() {
                return null;
              }
            }),
            fallbackName(new GrammarRule() {
              @Override
              public String grammarRuleName() {
                return " ";
              }
            }));
      case "parser-printer": {
        Parser leaf = w("x");
        new Chain(Name.of("Root"), new Chain(Name.of("Item"), leaf));
        return ParserPrinter.get(leaf, OutputLevel.simple) + ";" + ParserPrinter.get(leaf, OutputLevel.detail)
            + ";" + ParserPrinter.get(leaf, OutputLevel.mostDetail);
      }
      case "name-kinds": {
        // 同じ文字列から 2 回作った Name の identity を観測する（同一式の比較は Error Prone が拒否する）。
        Name first = Name.of("x");
        Name second = Name.of("x");
        return Name.of(WordParser.class.getName()).equals(Name.of(WordParser.class)) + ";"
            + Name.classBaseOf(w("a")).equals(Name.of(WordParser.class.getName())) + ";"
            + (first == second);
      }
      case "new-filtered": {
        Parser excluded = w("c");
        Chain chain = new Chain(w("a"), w("b"), excluded);
        return parse((Parser) chain.newFiltered(parser -> parser == excluded), "ab");
      }
      case "new-without-recursive": {
        Parser excluded = w("c");
        Chain chain = new Chain(new Chain(w("a"), excluded, w("b")), excluded);
        return parse((Parser) chain.newWithoutRecursive(parser -> parser == excluded), "ab");
      }
      case "child-occurs":
        return w("a").getChildOccurs() + ";" + new MatchOnly(w("a")).getChildOccurs() + ";"
            + new Chain(w("a")).getChildOccurs();
      case "root-indicator-scope-by-name":
        return scopeObservation(target -> new ReferenceByNameParser(Name.of(target)));
      case "root-indicator-scope-predicate":
        return scopeObservation(target -> ReferenceParser.of(Name.of(target)));
      case "root-indicator-root": {
        Parser reference = new ReferenceByNameParser(Name.of("Item"));
        new PseudoRootParser(new Parsers(new NamedChain("Outer", new ScopeChoice(reference)),
            new NamedChain("Item", w("a"))));
        return reference.getRoot().getName().getSimpleName() + ";"
            + reference.findFirstToParent(parser -> parser.getName().equals(Name.of("Outer"))).isPresent();
      }
      case "root-indicator-path": {
        Parser leaf = w("x");
        Parser scope = new ScopeChoice(new Chain(Name.of("Inner"), leaf));
        new PseudoRootParser(new Parsers(new NamedChain("Outer", scope)));
        return leaf.findParents(parser -> false).size() + ";" + leaf.getPath() + ";"
            + ParserHierarchy.isRoot.test(scope) + ";"
            + ParserPrinter.get(leaf, OutputLevel.detail);
      }
      case "root-indicator-rule-stack": {
        Parser outer = scopeGraph(true, target -> new ReferenceByNameParser(Name.of(target)));
        try (ParseContext context = ParseContext.withOptions(StringSource.createRootSource("y"),
            ParseOptions.withMemoization(Memoization.OFF).withDiagnostics(ParseOptions.Diagnostics.DETAILED))) {
          assertFalse(outer.parse(context).isSucceeded());
          return render(context.getRuleStack());
        }
      }
      case "by-name-hit-cached": {
        Parser use = new NamedChain("Use", new ReferenceByNameParser(Name.of("Item")));
        Parser top = new NamedChoice("Top", use, new NamedChain("Item", w("a")));
        String first = parse(use, "a");
        top.getChildren().add(1, new NamedChain("Item", w("b")));
        // A reference created after the change resolves the new first Item.
        Parser fresh = new NamedChain("Fresh", new ReferenceByNameParser(Name.of("Item")));
        fresh.setParent(top);
        return first + ";" + parse(use, "b") + ";" + parse(use, "a") + ";" + parse(fresh, "b");
      }
      case "root-cache-after-reparent": {
        Parser inner = new NamedChain("Inner", new ReferenceByNameParser(Name.of("Late")));
        String before = parse(inner, "x");
        new NamedChain("NewRoot", inner, new NamedChain("Late", w("x")));
        Parser attached = new NamedChain("Inner", new ReferenceByNameParser(Name.of("Late")));
        new NamedChain("NewRoot", attached, new NamedChain("Late", w("x")));
        return before + ";" + parse(inner, "x") + ";" + parse(attached, "x");
      }
      case "class-and-kind-null-kind":
        return failure(() -> new ParserFactoryByClass.ClassAndKind(null, KindParser.class)) + ";"
            + failure(() -> ParserFactoryByClass.get((ASTNodeKind) null, KindParser.class));
      case "class-and-kind-equality": {
        ParserFactoryByClass.ClassAndKind kindless = new ParserFactoryByClass.ClassAndKind(KindParser.class);
        ParserFactoryByClass.ClassAndKind other = new ParserFactoryByClass.ClassAndKind(ASTNodeKind.Other, KindParser.class);
        return kindless.equals(new ParserFactoryByClass.ClassAndKind(KindParser.class)) + ";"
            + kindless.equals(other) + ";"
            + other.equals(new ParserFactoryByClass.ClassAndKind(ASTNodeKind.Other, KindParser.class));
      }
      case "new-without-recursive-mixed": {
        Parser excluded = w("c");
        Chain chain = new Chain(Name.of("Outer"), new Choice(Name.of("Nested"), w("a"), excluded, w("z")), w("b"),
            excluded);
        Parser filtered = (Parser) chain.newWithoutRecursive(parser -> parser == excluded);
        Parser kept = filtered.getChildren().get(0);
        return filtered.getClass().getSimpleName() + ":" + filtered.getName().getSimpleName() + "("
            + kept.getClass().getSimpleName() + ":" + kept.getName().getSimpleName() + ");"
            + parse(filtered, "ab") + ";" + parse(filtered, "zb") + ";" + parse(filtered, "cb");
      }
      case "new-without-recursive-tags": {
        Parser excluded = w("c");
        Choice nested = new Choice(Name.of("Nested"), w("a"), excluded);
        nested.addTag(Tag.of("T"));
        Chain chain = new Chain(nested, w("b"));
        HasChildrenParser filtered = chain.newWithoutRecursive(parser -> parser == excluded);
        return nested.getTags().size() + ";" + filtered.getChildren().get(0).getTags().size();
      }
      case "new-without-recursive-single-child": {
        Parser excluded = w("c");
        Parser commit = new Commit(excluded, "m");
        Chain chain = new Chain(commit, w("b"));
        String emptied = failure(() -> chain.newWithoutRecursive(parser -> parser == excluded));
        return emptied + ";" + parse((Parser) chain.newWithoutRecursive(parser -> parser == commit), "b");
      }
      case "lazy-instance-meta-token": {
        Parser eager = new Chain(new ZeroOrMore(w("a")));
        Parser lazy = new Chain(new LazyZeroOrMore() {
          private static final long serialVersionUID = 1L;

          @Override
          public Supplier<Parser> getLazyParser() {
            return () -> w("a");
          }

          @Override
          public Optional<Parser> getLazyTerminatorParser() {
            return Optional.empty();
          }
        });
        return metaShape(eager, CreateMetaTokenSpecifier.createMetaOn) + "|"
            + metaShape(eager, CreateMetaTokenSpecifier.createMetaOff) + "|"
            + metaShape(lazy, CreateMetaTokenSpecifier.createMetaOff);
      }
      case "marker-no-effect": {
        MarkedParser.initializations.set(0);
        MarkedParser marked = Parser.get(MarkedParser.class);
        return MarkedParser.initializations.get() + ";" + identity(marked, Parser.get(MarkedParser.class)) + ";"
            + parse(marked, "m") + ";" + MarkedParser.initializations.get();
      }
      case "shared-graph-context-state": {
        Parser recording = new Recording(w("a"));
        String first;
        try (ParseContext context = new ParseContext(StringSource.createRootSource("a"))) {
          assertTrue(recording.parse(context).isSucceeded());
          first = String.valueOf(context.getGlobalScopeTreeMap().get(Name.of("seen")));
        }
        try (ParseContext context = new ParseContext(StringSource.createRootSource("a"))) {
          String fresh = context.getGlobalScopeTreeMap().containsKey(Name.of("seen")) ? "present" : "absent";
          return first + ";" + fresh + ";" + prefix(recording, context);
        }
      }
      default:
        throw new AssertionError("unimplemented observation " + id);
    }
  }

  @Test
  public void constructionAndIdentityObservationsMatchTheSharedTable() throws Exception {
    List<String[]> rows = rows("construction.tsv", 4);
    assertEquals(72, rows.size());
    Set<String> ids = rows.stream().map(row -> row[0]).collect(Collectors.toSet());
    assertEquals(rows.size(), ids.size());
    List<String> failures = new ArrayList<>();
    for (String[] row : rows) {
      assertFalse(row[0], row[3].isBlank());
      assertFalse(row[0], row[1].equals("-") && row[2].equals("-"));
      if (row[1].equals("-")) {
        continue;
      }
      try {
        assertEquals(row[0], row[1], observe(row[0]));
      } catch (AssertionError failure) {
        failures.add(failure.getMessage());
      }
    }
    assertTrue(String.join("\n", failures), failures.isEmpty());
  }
}
