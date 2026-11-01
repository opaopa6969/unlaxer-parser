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

  private static Function<String, Parser> variant(String name) {
    switch (name) {
      case "by-name": {
        Map<String, Parser> rules = referenceGraph(target -> new ReferenceByNameParser(Name.of(target)));
        return rules::get;
      }
      case "predicate-name": {
        Map<String, Parser> rules = referenceGraph(target -> ReferenceParser.of(Name.of(target)));
        return rules::get;
      }
      case "predicate-rule": {
        Map<String, Parser> rules = referenceGraph(target -> new ReferenceParser(
            parser -> parser instanceof GrammarRule && GrammarRule.nameOf(parser).equals(target)));
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
            try (ParseContext context = ParseContext.withOptions(StringSource.createRootSource(decode(c[2])),
                ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
              Parsed parsed = rules.apply(c[1]).parse(context, TokenKind.valueOf(c[3]), Boolean.parseBoolean(c[4]));
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
              String tree = context.getCurrent().getTokens().stream()
                  .map(ParserConstructionParityTest::shape)
                  .collect(Collectors.joining(";"));
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
    assertEquals(56, rows.size());
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
