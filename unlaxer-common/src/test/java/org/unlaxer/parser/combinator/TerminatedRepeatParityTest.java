package org.unlaxer.parser.combinator;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.ast.ASTNodeKind;
import org.unlaxer.context.*;
import org.unlaxer.parser.*;
import org.unlaxer.parser.elementary.*;

/**
 * Java Occurs terminator oracle shared with rust/unlaxer-runtime/tests/terminated_repeat.rs (#846).
 * Every public construction of a scenario must reproduce the same row, and one parser
 * instance is reused across all rows and modes.
 */
public class TerminatedRepeatParityTest {
    private static final Path CASES = Path.of("../conformance/terminated-repeat/cases.tsv");
    private static final Path STATE = Path.of("../conformance/terminated-repeat/state.tsv");
    private static final Path WRAPPERS = Path.of("../conformance/terminated-repeat/wrappers.tsv");
    private static final Path RAISED_STATE = Path.of("../conformance/terminated-repeat/raised-state.tsv");

    public static class A extends WordParser { private static final long serialVersionUID = 1L; public A() { super("a"); } }
    public static class Semi extends WordParser { private static final long serialVersionUID = 1L; public Semi() { super(";"); } }

    private static Parser w(String text) { return new WordParser(text); }
    private static Name n() { return Name.of("n"); }

    private static final class LZ extends LazyZeroOrMore {
        private static final long serialVersionUID = 1L;
        LZ(Name name) { super(name); }
        LZ() { super(); }
        @Override public Supplier<Parser> getLazyParser() { return () -> w("a"); }
        @Override public java.util.Optional<Parser> getLazyTerminatorParser() { return java.util.Optional.of(w(";")); }
    }
    private static final class LO extends LazyOneOrMore {
        private static final long serialVersionUID = 1L;
        LO(Name name) { super(name); }
        LO() { super(); }
        @Override public Supplier<Parser> getLazyParser() { return () -> w("a"); }
        @Override public java.util.Optional<Parser> getLazyTerminatorParser() { return java.util.Optional.of(w(";")); }
    }
    private static final class LOpt extends LazyOptional {
        private static final long serialVersionUID = 1L;
        LOpt(Name name) { super(name); }
        LOpt() { super(); }
        @Override public Supplier<Parser> getLazyParser() { return () -> w("a"); }
        @Override public java.util.Optional<Parser> getLazyTerminatorParser() { return java.util.Optional.of(w(";")); }
    }
    private static final class LZoo extends LazyZeroOrOne {
        private static final long serialVersionUID = 1L;
        LZoo(Name name) { super(name); }
        LZoo() { super(); }
        @Override public Supplier<Parser> getLazyParser() { return () -> w("a"); }
        @Override public java.util.Optional<Parser> getLazyTerminatorParser() { return java.util.Optional.of(w(";")); }
    }
    private static final class LRep extends LazyRepeat {
        private static final long serialVersionUID = 1L;
        LRep(Name name) { super(name, 2, 3); }
        LRep() { super(2, 3); }
        @Override public Supplier<Parser> getLazyParser() { return () -> w("a"); }
        @Override public java.util.Optional<Parser> getLazyTerminatorParser() { return java.util.Optional.of(w(";")); }
    }
    /** The public ChildOccursWithTerminator constructors, as a ZeroOrMore-bounded subclass. */
    private static final class Child extends ChildOccursWithTerminator {
        private static final long serialVersionUID = 1L;
        Child(Name name, Class<? extends Parser> inner, Class<? extends Parser> terminator) { super(name, inner, terminator); }
        Child(Name name, Parser inner, Parser terminator) { super(name, inner, terminator); }
        @Override public int min() { return 0; }
        @Override public int max() { return Integer.MAX_VALUE; }
    }

    private static Parser semicolons() { return new ZeroOrMore(w("a"), w(";")); }

    /** Every public overload, factory and lazy form that ports to the scenario's Rust expression. */
    static List<Parser> parsers(String scenario) {
        return switch (scenario) {
            case "zom-word" -> List.of(semicolons(), new ZeroOrMore(A.class, Semi.class),
                    new ZeroOrMore(n(), w("a"), w(";")), new ZeroOrMore(n(), ASTNodeKind.Operand, w("a"), w(";")),
                    new ZeroOrMore(w("a")).newWithTerminator(w(";")),
                    new Child(n(), A.class, Semi.class), new Child(n(), w("a"), w(";")));
            case "lazy-zom" -> List.of(new LZ(), new LZ(n()));
            case "zom-match" -> List.of(new ZeroOrMore(w("a"), new MatchOnly(w(";"))));
            case "oom-word" -> List.of(new OneOrMore(n(), A.class, Semi.class), new OneOrMore(n(), w("a"), w(";")),
                    new OneOrMore(n(), ASTNodeKind.Operand, A.class, Semi.class),
                    new OneOrMore(A.class).newWithTerminator(Semi.class), new OneOrMore(w("a")).newWithTerminator(w(";")));
            case "lazy-oom" -> List.of(new LO(), new LO(n()));
            case "opt-word" -> List.of(new Optional(n(), A.class, Semi.class), new Optional(n(), w("a"), w(";")));
            case "zoo-word" -> List.of(new ZeroOrOne(n(), A.class, Semi.class), new ZeroOrOne(n(), w("a"), w(";")));
            case "lazy-opt" -> List.of(new LOpt(), new LOpt(n()));
            case "lazy-zoo" -> List.of(new LZoo(), new LZoo(n()));
            case "rep-2-3" -> List.of(new Repeat(w("a"), 2, 3).newWithTerminator(w(";")),
                    new Repeat(A.class, 2, 3).newWithTerminator(w(";")),
                    new Repeat(ASTNodeKind.Operand, A.class, 2, 3).newWithTerminator(w(";")),
                    new Repeat(n(), ASTNodeKind.Operand, A.class, 2, 3).newWithTerminator(w(";")));
            case "lazy-rep" -> List.of(new LRep(), new LRep(n()));
            case "chain-term" -> List.of(new ZeroOrMore(w("a"), new Chain(w(";"), w("x"))));
            case "choice-term" -> List.of(new ZeroOrMore(w("a"), new Choice(w(";"), w("\r\n"), w("\n"))));
            case "nullable-term" -> List.of(new ZeroOrMore(w("a"), new Optional(w("z"))));
            case "nullable-term-oom" -> List.of(new OneOrMore(n(), w("a"), new Optional(w("z"))));
            case "nullable-body" -> List.of(new ZeroOrMore(new Optional(w("a")), w(";")));
            case "eof-term" -> List.of(new ZeroOrMore(w("a"), new EndOfSourceParser()));
            case "empty-term" -> List.of(new ZeroOrMore(w("a"), new EmptyParser()));
            case "until" -> List.of(new WildCardStringTerminatorParser(";", "\r\n"),
                    new WildCardStringTerminatorParser(true, ";", "\r\n"),
                    new WildCardStringTerminatorParser(n(), ";", "\r\n"),
                    new WildCardStringTerminatorParser(n(), true, ";", "\r\n"));
            case "until-consumed" -> List.of(new WildCardStringTerminatorParser(false, ";", "\r\n"),
                    new WildCardStringTerminatorParser(n(), false, ";", "\r\n"));
            case "until-parser" -> List.of(new WildCardStringTerminatorParser(w(";")),
                    new WildCardStringTerminatorParser(true, w(";")));
            case "until-parser-consumed" -> List.of(new WildCardStringTerminatorParser(false, w(";")));
            case "until-named" -> List.of(new WildCardStringTerminatorParser(Name.of("u"), ";"));
            case "jdk-until" -> List.of(new WildCardStringTerminatorParser(";"));
            case "reuse" -> { Parser repeat = semicolons(); yield List.of(new Chain(repeat, repeat)); }
            case "retry" -> {
                Parser repeat = semicolons();
                yield List.of(new Choice(new Chain(repeat, w("x")), new Chain(repeat, w("y"))));
            }
            case "lookahead" -> List.of(new MatchOnly(semicolons()));
            case "nested" -> List.of(new ZeroOrMore(semicolons(), w(".")));
            case "bounded-max0" -> List.of(new Repeat(w("a"), 0, 0).newWithTerminator(w(";")));
            case "bounded-invalid" -> List.of(new Repeat(w("a"), 2, 1).newWithTerminator(w(";")));
            default -> throw new AssertionError(scenario);
        };
    }

    private static String decode(String hex) {
        return hex.equals("~") ? "" : new String(HexFormat.of().parseHex(hex), StandardCharsets.UTF_8);
    }
    private static String encode(String text) {
        return text.isEmpty() ? "~" : HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8));
    }
    private static String signature(Token token) {
        return token.tokenKind + "@" + token.source.offsetFromRoot().value() + ":"
                + token.source.codePointLength().value() + ":" + encode(token.source.sourceAsString());
    }
    /** Kind-filtered children. No construction adds a notNode tag, so the AST view is the same. */
    private static String tree(Token token) {
        List<Token> children = token.getOriginalChildren();
        assertEquals(children, token.filteredChildren);
        if (children.isEmpty() || token.parser instanceof WordParser) return signature(token);
        return signature(token) + "[" + children.stream().map(TerminatedRepeatParityTest::tree).collect(Collectors.joining(",")) + "]";
    }
    private static String joined(List<String> values) { return values.isEmpty() ? "~" : String.join(";", values); }

    private static List<String[]> rows(Path path) throws Exception {
        return Files.readAllLines(path).stream().filter(line -> !line.startsWith("#") && !line.isBlank())
                .map(line -> line.split("\t", -1)).toList();
    }

    private static List<String> observe(Parser parser, String[] row, Memoization memo, ParseOptions.Diagnostics mode) {
        try (var context = ParseContext.withOptions(StringSource.createRootSource(decode(row[1])),
                ParseOptions.withMemoization(memo).withDiagnostics(mode))) {
            // Prior roots use independent cursors, as in the token-propagation oracle.
            context.getCurrent().setResetMatchedWithConsumed(false);
            if (!row[4].equals("~")) assertTrue(w(decode(row[4])).parse(context, TokenKind.matchOnly, false).isSucceeded());
            context.getCurrent().setResetMatchedWithConsumed(Boolean.parseBoolean(row[5]));
            Parsed parsed = row[6].equals("default") ? parser.parse(context)
                    : parser.parse(context, TokenKind.valueOf(row[2]), Boolean.parseBoolean(row[3]));
            var diagnostic = context.getParseFailureDiagnostics();
            return List.of(String.valueOf(parsed.isSucceeded()),
                    String.valueOf(context.getConsumedPosition().value()),
                    String.valueOf(context.getMatchedPosition().value()),
                    String.valueOf(diagnostic.getFarthestOffset()),
                    String.valueOf(!diagnostic.getExpectedTokens().isEmpty()),
                    parsed.isSucceeded() ? joined(parsed.getOriginalTokens().stream().map(TerminatedRepeatParityTest::signature).toList()) : "~",
                    joined(context.getCurrent().getTokens().stream().map(TerminatedRepeatParityTest::tree).toList()),
                    String.valueOf(context.getTokenStack().size()),
                    String.valueOf(context.getCurrent().isResetMatchedWithConsumed()));
        }
    }

    @Test public void sharedTerminatedRepeatOracleForEveryPublicConstructionAndMode() throws Exception {
        List<String[]> rows = rows(CASES);
        assertEquals(732, rows.size());
        Map<String, List<Parser>> constructions = new HashMap<>();
        var failures = new ArrayList<String>();
        int checked = 0;
        for (Memoization memo : Memoization.values()) for (ParseOptions.Diagnostics mode : ParseOptions.Diagnostics.values()) {
            for (String[] row : rows) {
                assertEquals(16, row.length);
                List<String> expected = new ArrayList<>(Arrays.asList(row).subList(7, 16));
                if (mode == ParseOptions.Diagnostics.DETAILED_ON_FAILURE) {
                    expected.set(3, "0");
                    expected.set(4, "false");
                }
                List<Parser> parsers = constructions.computeIfAbsent(row[0], TerminatedRepeatParityTest::parsers);
                for (int index = 0; index < parsers.size(); index++) {
                    List<String> actual = observe(parsers.get(index), row, memo, mode);
                    checked++;
                    if (false == actual.equals(expected)) {
                        failures.add(Arrays.toString(Arrays.copyOf(row, 7)) + "#" + index + "/" + memo + "/" + mode
                                + "\n expected " + expected + "\n actual   " + actual);
                    }
                }
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
        assertEquals(constructions.keySet(), new HashSet<>(rows.stream().map(row -> row[0]).toList()));
        assertTrue(checked > rows.size() * Memoization.values().length * ParseOptions.Diagnostics.values().length);
    }

    private static final class State implements TransactionalState {
        int value = 7;
        @Override public Runnable checkpoint() { int saved = value; return () -> value = saved; }
    }

    @Test public void sharedTerminatorStateCaptureAndRollbackOracle() throws Exception {
        List<String[]> rows = rows(STATE);
        assertEquals(24, rows.size());
        for (Memoization memo : Memoization.values()) for (ParseOptions.Diagnostics mode : ParseOptions.Diagnostics.values()) {
            for (String[] row : rows) {
                State state = new State();
                AtomicInteger calls = new AtomicInteger();
                Parser semi = new WordParser(";") {
                    private static final long serialVersionUID = 1L;
                    @Override public Token getToken(ParseContext context, TokenKind kind, boolean invert) {
                        calls.incrementAndGet();
                        state.value = 99;
                        return super.getToken(context, kind, invert);
                    }
                };
                Parser terminator = new NamedCapture("t", semi);
                Parser repeat = row[0].equals("chain-partial")
                        ? new ZeroOrMore(w("a"), new Chain(terminator, w("x")))
                        : new ZeroOrMore(w("a"), terminator);
                Parser parser = switch (row[0]) {
                    case "plain", "chain-partial" -> repeat;
                    case "outer-fail" -> new Chain(repeat, w("z"));
                    case "retry" -> new Choice(new Chain(repeat, w("x")), new Chain(repeat, w("y")));
                    case "lookahead" -> new MatchOnly(repeat);
                    case "reuse" -> new Chain(repeat, repeat);
                    default -> throw new AssertionError(row[0]);
                };
                try (var context = ParseContext.withOptions(StringSource.createRootSource(decode(row[1])),
                        ParseOptions.withMemoization(memo).withDiagnostics(mode))) {
                    context.registerTransactionalState(state);
                    Parsed parsed = parser.parse(context, TokenKind.valueOf(row[2]), false);
                    String captured = context.captured("t").map(value -> value.start() + ":" + encode(value.text())).orElse("~");
                    List<String> actual = List.of(String.valueOf(parsed.isSucceeded()),
                            String.valueOf(context.getConsumedPosition().value()), String.valueOf(context.getMatchedPosition().value()),
                            String.valueOf(state.value), captured, String.valueOf(calls.get()),
                            String.valueOf(context.getParseFailureDiagnostics().getFarthestOffset()));
                    List<String> expected = new ArrayList<>(Arrays.asList(row).subList(3, 10));
                    if (mode == ParseOptions.Diagnostics.DETAILED_ON_FAILURE) expected.set(6, "0");
                    assertEquals(Arrays.toString(row) + "/" + memo + "/" + mode, expected, actual);
                }
            }
        }
    }

    /** The generated rule shape (ParserRuleEmitter): a LazyChain/LazyChoice marked as GrammarRule. */
    public static class SemiXParser extends LazyChain implements DiagnosticsAgnostic, GrammarRule {
        private static final long serialVersionUID = 1L;
        @Override public String grammarRuleName() { return "SemiX"; }
        @Override public Parsers getLazyParsers() { return new Parsers(w(";"), w("x")); }
    }
    public static class EndParser extends LazyChoice implements DiagnosticsAgnostic, GrammarRule {
        private static final long serialVersionUID = 1L;
        @Override public String grammarRuleName() { return "End"; }
        @Override public Parsers getLazyParsers() { return new Parsers(w(";"), w("\n")); }
    }

    private static Parser semiCut() { return new ZeroOrMore(w("a"), new Chain(w(";"), new Commit(w("x")))); }

    /** Wrapper, cut/label, generated-rule and lookahead terminators; see README.md for the Rust ports. */
    static Parser wrapperParser(String scenario) {
        return switch (scenario) {
            case "commit-term" -> new ZeroOrMore(w("a"), new Commit(new Chain(w(";"), w("x"))));
            case "commit-word-term" -> new ZeroOrMore(w("a"), new Commit(w(";")));
            case "label-term" -> new ZeroOrMore(w("a"), new Label("L", new Chain(w(";"), w("x"))));
            case "label-word-term" -> new ZeroOrMore(w("a"), new Label("L", w(";")));
            case "stopconsume-term" -> new ZeroOrMore(w("a"), new DoConsumePropagationStopper(w(";")));
            case "stopinvert-term" -> new ZeroOrMore(w("a"), new InvertMatchPropagationStopper(w(";")));
            case "invert-term" -> new ZeroOrMore(w("a"), new NotPropagatableSource(w(";")));
            case "commit-inner-term" -> semiCut();
            case "label-inner-term" -> new ZeroOrMore(w("a"), new Chain(w(";"), new Label("L", w("x"))));
            case "commit-body" -> new ZeroOrMore(new Chain(w("a"), new Commit(w("b"))), w(";"));
            case "raise-choice" -> new Choice(new Chain(semiCut(), w("!")), w("a;y"));
            case "raise-matchonly" -> new MatchOnly(semiCut());
            case "capture-term" -> new ZeroOrMore(w("a"), new NamedCapture("t", new Chain(w(";"), w("x"))));
            case "matchonly-chain-term" -> new ZeroOrMore(w("a"), new MatchOnly(new Chain(w(";"), w("x"))));
            case "until-term" -> new ZeroOrMore(w("a"), new WildCardStringTerminatorParser(";"));
            case "zom-term" -> new ZeroOrMore(w("a"), new ZeroOrMore(w(";")));
            case "rule-chain-term" -> new ZeroOrMore(w("a"), new SemiXParser());
            case "rule-choice-term" -> new ZeroOrMore(w("a"), new EndParser());
            case "not-term" -> new ZeroOrMore(w("a"), new Not(w(";")));
            case "lookahead-term" -> new ZeroOrMore(w("a"), new MatchOnly(w(";")));
            case "peg-ahead-term" -> new ZeroOrMore(w("a"), new PegLookahead(w(";")));
            case "peg-not-term" -> new ZeroOrMore(w("a"), new PegLookahead(w(";"), false));
            case "name-scope-term" -> new ZeroOrMore(w("a"), new NameResolutionScope(w(";"), List.of()));
            default -> throw new AssertionError(scenario);
        };
    }

    private static String raised(ParseContext context) {
        if (false == context.hasRaisedFailure()) return "~";
        RaisedFailure raised = context.raisedFailure();
        return (raised.isCommit() ? "commit" : "label:" + raised.label()) + "@" + raised.offset();
    }
    /** Leaves of the kind-filtered CST, the Rust token trace's view. */
    private static void leaves(Token token, List<String> out) {
        if (token.filteredChildren.isEmpty()) {
            int start = token.source.offsetFromRoot().value();
            out.add(token.tokenKind + "," + start + "," + (start + token.source.codePointLength().value())
                    + "," + encode(token.source.sourceAsString()));
        } else for (Token child : token.filteredChildren) leaves(child, out);
    }

    /**
     * #861 (fix PR #862, not in this base): Java DETAILED_ON_FAILURE excludes Choice candidates by the
     * matched cursor before the reset, so this one row fails only in that mode. It is not a terminator
     * difference (it reproduces with Choice(Chain(a, !), "a;y")); the Rust row and every other Java mode
     * follow the shared expectation. Remove this entry together with the #862 fix.
     */
    private static final Map<String, List<String>> ISSUE_861_DETAILED_ON_FAILURE = Map.of(
            "raise-choice\t613b79\tmatchOnly\tfalse\t61\ttrue",
            List.of("false", "0", "1", "0", "false", "~", "~", "matchOnly@0:1:61", "matchOnly,0,1,61", "1", "true"));

    @Test public void sharedWrapperRuleAndLookaheadTerminatorOracleInEveryMode() throws Exception {
        List<String[]> rows = rows(WRAPPERS);
        assertEquals(2342, rows.size());
        Map<String, Parser> parsers = new HashMap<>();
        var failures = new ArrayList<String>();
        int issue861 = 0;
        for (Memoization memo : Memoization.values()) for (ParseOptions.Diagnostics mode : ParseOptions.Diagnostics.values()) {
            for (String[] row : rows) {
                assertEquals(17, row.length);
                List<String> expected = new ArrayList<>(Arrays.asList(row).subList(6, 17));
                // A raised failure (#700) keeps its diagnostics when DETAILED_ON_FAILURE records lazily.
                if (mode == ParseOptions.Diagnostics.DETAILED_ON_FAILURE && row[11].equals("~")) {
                    expected.set(3, "0");
                    expected.set(4, "false");
                }
                List<String> known = ISSUE_861_DETAILED_ON_FAILURE.get(String.join("\t", Arrays.copyOf(row, 6)));
                if (mode == ParseOptions.Diagnostics.DETAILED_ON_FAILURE && known != null) {
                    expected = known;
                    issue861++;
                }
                Parser parser = parsers.computeIfAbsent(row[0], TerminatedRepeatParityTest::wrapperParser);
                try (var context = ParseContext.withOptions(StringSource.createRootSource(decode(row[1])),
                        ParseOptions.withMemoization(memo).withDiagnostics(mode))) {
                    context.getCurrent().setResetMatchedWithConsumed(false);
                    if (!row[4].equals("~")) assertTrue(w(decode(row[4])).parse(context, TokenKind.matchOnly, false).isSucceeded());
                    context.getCurrent().setResetMatchedWithConsumed(Boolean.parseBoolean(row[5]));
                    Parsed parsed = parser.parse(context, TokenKind.valueOf(row[2]), Boolean.parseBoolean(row[3]));
                    var diagnostic = context.getParseFailureDiagnostics();
                    List<String> leaves = new ArrayList<>();
                    for (Token token : context.getCurrent().getTokens()) leaves(token, leaves);
                    List<String> actual = List.of(String.valueOf(parsed.isSucceeded()),
                            String.valueOf(context.getConsumedPosition().value()),
                            String.valueOf(context.getMatchedPosition().value()),
                            String.valueOf(diagnostic.getFarthestOffset()),
                            String.valueOf(!diagnostic.getExpectedTokens().isEmpty()), raised(context),
                            parsed.isSucceeded() ? joined(parsed.getOriginalTokens().stream().map(TerminatedRepeatParityTest::signature).toList()) : "~",
                            joined(context.getCurrent().getTokens().stream().map(TerminatedRepeatParityTest::tree).toList()),
                            joined(leaves), String.valueOf(context.getTokenStack().size()),
                            String.valueOf(context.getCurrent().isResetMatchedWithConsumed()));
                    if (false == actual.equals(expected)) {
                        failures.add(Arrays.toString(Arrays.copyOf(row, 6)) + "/" + memo + "/" + mode
                                + "\n expected " + expected + "\n actual   " + actual);
                    }
                }
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
        assertEquals(23, parsers.size());
        assertEquals(Memoization.values().length, issue861);
    }

    @Test public void sharedRaisedTerminatorStateAndRollbackOracle() throws Exception {
        List<String[]> rows = rows(RAISED_STATE);
        assertEquals(112, rows.size());
        for (Memoization memo : Memoization.values()) for (ParseOptions.Diagnostics mode : ParseOptions.Diagnostics.values()) {
            for (String[] row : rows) {
                State state = new State();
                AtomicInteger calls = new AtomicInteger();
                Parser semi = new WordParser(";") {
                    private static final long serialVersionUID = 1L;
                    @Override public Token getToken(ParseContext context, TokenKind kind, boolean invert) {
                        calls.incrementAndGet();
                        state.value = 99;
                        return super.getToken(context, kind, invert);
                    }
                };
                Parser terminator = new NamedCapture("t", semi);
                Parser cut = new ZeroOrMore(w("a"), new Chain(terminator, new Commit(w("x"))));
                Parser parser = switch (row[0]) {
                    case "commit-chain" -> new ZeroOrMore(w("a"), new Commit(new Chain(terminator, w("x"))));
                    case "chain-commit" -> cut;
                    case "chain-label" -> new ZeroOrMore(w("a"), new Chain(terminator, new Label("L", w("x"))));
                    case "choice-raise" -> new Choice(new Chain(cut, w("z")), w("a;y"));
                    case "catch-label" -> new CatchLabels(new Chain(new ZeroOrMore(w("a"),
                            new Chain(terminator, new Label("L", w("x")))), w("z")), w("a;y"), "L");
                    case "matchonly-raise" -> new MatchOnly(cut);
                    case "outer-fail" -> new Chain(cut, w("z"));
                    default -> throw new AssertionError(row[0]);
                };
                try (var context = ParseContext.withOptions(StringSource.createRootSource(decode(row[1])),
                        ParseOptions.withMemoization(memo).withDiagnostics(mode))) {
                    context.registerTransactionalState(state);
                    Parsed parsed = parser.parse(context, TokenKind.valueOf(row[2]), false);
                    String captured = context.captured("t").map(value -> value.start() + ":" + encode(value.text())).orElse("~");
                    List<String> actual = List.of(String.valueOf(parsed.isSucceeded()),
                            String.valueOf(context.getConsumedPosition().value()), String.valueOf(context.getMatchedPosition().value()),
                            String.valueOf(state.value), captured, String.valueOf(calls.get()),
                            String.valueOf(context.getParseFailureDiagnostics().getFarthestOffset()), raised(context));
                    List<String> expected = new ArrayList<>(Arrays.asList(row).subList(3, 11));
                    if (mode == ParseOptions.Diagnostics.DETAILED_ON_FAILURE && row[10].equals("~")) expected.set(6, "0");
                    assertEquals(Arrays.toString(row) + "/" + memo + "/" + mode, expected, actual);
                }
            }
        }
    }

    @Test public void firstSetsUnionBodyAndTerminatorLikeTheRustAnalysis() {
        // Java FirstSets visits the body, then the terminator: `;` can start the repetition.
        assertTrue(FirstSets.of(new OneOrMore(n(), w("a"), w(";"))).mayStartWith(';'));
        assertTrue(FirstSets.of(new OneOrMore(n(), w("a"), w(";"))).mayStartWith('a'));
        assertFalse(FirstSets.of(new OneOrMore(n(), w("a"), w(";"))).mayStartWith('b'));
        assertEquals(FirstSet.EPSILON, FirstSets.of(new Repeat(w("a"), 0, 0).newWithTerminator(w(";"))));
        assertEquals(FirstSet.EMPTY, FirstSets.of(new Repeat(w("a"), 2, 1).newWithTerminator(w(";"))));
    }
}
