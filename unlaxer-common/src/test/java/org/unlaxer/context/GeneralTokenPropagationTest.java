package org.unlaxer.context;

import static org.junit.Assert.*;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.clang.IdentifierParser;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.NumberParser;
import org.unlaxer.parser.elementary.SingleQuotedParser;
import org.unlaxer.parser.elementary.WordParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/** Independent classic-token oracle; deliberately distinct from pure PEG assertions. */
public class GeneralTokenPropagationTest {
    private static String decode(String value) {
        return value.equals("~")
                ? ""
                : new String(HexFormat.of().parseHex(value), StandardCharsets.UTF_8);
    }

    private static final class RangeParser extends org.unlaxer.parser.elementary.SingleCharacterParser {
        @Override public boolean isMatch(int cp) {return cp >= 'a' && cp <= 'c';}
    }
    private static Parser word(String value) {
        return new WordParser(value);
    }

    private static Parser match(Parser child) {
        return new MatchOnly(child);
    }

    private static Parser consume(Parser child) {
        return new DoConsumePropagationStopper(child);
    }

    private static Parser generated(boolean forceVirtual) {
        return new NoneChildParser() {
            @Override public Parser createParser() {return this;}
            @Override public Parsed parse(ParseContext context,TokenKind kind,boolean invert) {
                TokenKind actual=forceVirtual?TokenKind.virtualTokenMatchOnly:kind;
                Token token=new Token(actual,StringSource.createDetachedSource("x"),this);
                context.getCurrent().addToken(token,actual);
                return new Parsed(token);
            }
        };
    }
    private static Parser parser(String scenario) {
        return switch (scenario) {
            case "generated" -> new Chain(generated(false));
            case "match-generated" -> match(generated(true));
            case "lookahead" -> new MatchOnly(word("a"));
            case "negative" -> new Not(word("a"));
            case "until" -> new org.unlaxer.parser.elementary.WildCardStringTerminatorParser(";");
            case "longest" -> new LongestChoice(word("a"),word("ab"));
            case "unique-longest" -> new UniqueLongestChoice(word("a"),word("ab"));
            case "unique-tie" -> new UniqueLongestChoice(word("a"),word("a"));
            case "any" -> new org.unlaxer.parser.elementary.WildCardCharacterParser();
            case "grapheme" -> new org.unlaxer.parser.elementary.GraphemeParser();
            case "unicode-ci" -> new org.unlaxer.parser.elementary.UnicodeIgnoreCaseWordParser("k");
            case "range" -> new RangeParser();
            case "eof" -> new org.unlaxer.parser.elementary.EndOfSourceParser();
            case "java-empty" -> new org.unlaxer.parser.elementary.EmptyParser();
            case "word-a" -> word("a");
            case "word-b" -> word("b");
            case "word-x" -> word("x");
            case "word-ab" -> word("ab");
            case "empty-word" -> word("");
            case "match-word" -> match(word("a"));
            case "match-b" -> match(word("b"));
            case "match-emoji" -> match(word("😀"));
            case "match-crlf" -> match(word("a\r\n😀"));
            case "consume-word" -> consume(word("a"));
            case "stop-invert-word" -> new InvertMatchPropagationStopper(word("a"));
            case "not-word" -> new NotPropagatableSource(word("a"));
            case "double-not" -> new NotPropagatableSource(new NotPropagatableSource(word("a")));
            case "not-stop-not" ->
                    new NotPropagatableSource(
                            new InvertMatchPropagationStopper(
                                    new NotPropagatableSource(word("a"))));
            case "match-chain" -> match(new Chain(word("a"), word("b")));
            case "chain-match" -> new Chain(match(word("a")), word("a"));
            case "match-consume" -> match(consume(word("a")));
            case "nested-consume" -> match(new Chain(word("a"), consume(word("a")), word("b")));
            case "after-match" -> new Chain(word("a"), match(word("b")));
            case "chain-bb" -> new Chain(word("b"), word("b"));
            case "choice-failure" -> new Choice(word("x"),word("y"));
            case "choice-rollback" ->
                    new Choice(new Chain(word("a"), word("x")), new Chain(word("a"), word("b")));
            case "match-nonordered" -> match(new NonOrdered(word("b"), word("a")));
            case "match-empty-chain" -> match(new Chain());
            case "number" -> new NumberParser();
            case "match-number" -> match(new NumberParser());
            case "identifier" -> new IdentifierParser();
            case "match-identifier" -> match(new IdentifierParser());
            case "match-quoted" -> match(new SingleQuotedParser());
            case "quoted" -> new SingleQuotedParser();
            case "double-quoted" -> new org.unlaxer.parser.elementary.DoubleQuotedParser();
            case "stop-invert-identifier" -> new InvertMatchPropagationStopper(new IdentifierParser());
            case "stop-consume-number" -> consume(new NumberParser());
            case "optional-a" -> new org.unlaxer.parser.combinator.Optional(word("a"));
            case "match-optional-a" -> match(new org.unlaxer.parser.combinator.Optional(word("a")));
            case "repeat-a" -> new ZeroOrMore(word("a"));
            case "match-repeat-a" -> match(new ZeroOrMore(word("a")));
            default -> throw new AssertionError(scenario);
        };
    }

    private static String signature(Token token) {
        String text = token.source.sourceAsString();
        return token.tokenKind
                + "@"
                + token.source.offsetFromRoot().value()
                + ":"
                + token.source.codePointLength().value()
                + ":"
                + (text.isEmpty()
                        ? "~"
                        : HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String signatures(List<Token> tokens) {
        return tokens.isEmpty()
                ? "~"
                : tokens.stream()
                        .map(GeneralTokenPropagationTest::signature)
                        .collect(Collectors.joining(";"));
    }

    private static boolean atomic(Token token) {
        return token.parser instanceof WordParser
                || token.parser instanceof NumberParser
                || token.parser instanceof IdentifierParser
                || token.parser instanceof SingleQuotedParser
                || token.parser instanceof org.unlaxer.parser.elementary.DoubleQuotedParser;
    }

    private static void leaves(Token token, List<Token> target) {
        if (atomic(token) || token.getOriginalChildren().isEmpty()) target.add(token);
        else for (Token child : token.getOriginalChildren()) leaves(child, target);
    }

    @Test
    public void sharedIndependentOracleFixesBothCursorsAndBothTokenViews() throws Exception {
        var rows =
                Files.readAllLines(Path.of("../conformance/token-propagation/cases.tsv")).stream()
                        .filter(line -> !line.startsWith("#") && !line.isBlank())
                        .toList();
        assertEquals(166, rows.size());
        verifyOracle(rows);
    }

    @Test public void atomicTriviaOraclePreservesLexicalSourceAndCursors() throws Exception {
        var rows = Files.readAllLines(Path.of("../conformance/token-propagation/atomic-trivia.tsv")).stream()
                .filter(line -> !line.startsWith("#") && !line.isBlank()).toList();
        assertEquals(52, rows.size());
        verifyOracle(rows);
    }

    private void verifyOracle(List<String> rows) {
        var failures = new ArrayList<String>();
        for (Memoization memo : Memoization.values()) {
            for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
                for (String row : rows) {
                    String[] c = row.split("\t", -1);
                    assertEquals(16, c.length);
                    String label = c[0] + "/" + memo + "/" + diagnostics;
                    try (var context =
                            ParseContext.withOptions(
                                    StringSource.createRootSource(decode(c[1])),
                                    ParseOptions.withMemoization(memo)
                                            .withDiagnostics(diagnostics))) {
                        context.getCurrent().setResetMatchedWithConsumed(false);
                        if (!c[5].equals("~"))
                            assertTrue(label, word(decode(c[5])).parse(context).isSucceeded());
                        if (!c[6].equals("~"))
                            assertTrue(
                                    label,
                                    word(decode(c[6]))
                                            .parse(context, TokenKind.matchOnly, false)
                                            .isSucceeded());
                        context.getCurrent()
                                .setResetMatchedWithConsumed(Boolean.parseBoolean(c[7]));
                        Parsed parsed =
                                parser(c[2])
                                        .parse(
                                                context,
                                                TokenKind.valueOf(c[3]),
                                                Boolean.parseBoolean(c[4]));
                        assertEquals(label, Boolean.parseBoolean(c[8]), parsed.isSucceeded());
                        assertEquals(
                                label,
                                Integer.parseInt(c[9]),
                                context.getConsumedPosition().value());
                        assertEquals(
                                label,
                                Integer.parseInt(c[10]),
                                context.getMatchedPosition().value());
                        var diagnostic = context.getParseFailureDiagnostics();
                        assertEquals(
                                label,
                                diagnostics == ParseOptions.Diagnostics.DETAILED_ON_FAILURE
                                        ? 0
                                        : Integer.parseInt(c[11]),
                                diagnostic.getFarthestOffset());
                        assertEquals(
                                label,
                                diagnostics != ParseOptions.Diagnostics.DETAILED_ON_FAILURE
                                        && Boolean.parseBoolean(c[12]),
                                !diagnostic.getExpectedTokens().isEmpty());
                        boolean builtin =
                                c[2].contains("number")
                                        || c[2].contains("identifier")
                                        || c[2].contains("quoted");
                        List<Token> originals =
                                builtin && parsed.isSucceeded()
                                        ? List.of(parsed.getRootToken())
                                        : parsed.getOriginalTokens();
                        assertEquals(label + "/original", c[13], signatures(originals));
                        assertEquals(
                                label + "/committed",
                                c[14],
                                signatures(context.getCurrent().getTokens()));
                        var leaves = new ArrayList<Token>();
                        for (Token token : context.getCurrent().getTokens()) leaves(token, leaves);
                        assertEquals(label + "/leaves", c[15], signatures(leaves));
                        assertEquals(label, 1, context.getTokenStack().size());
                    } catch (AssertionError failure) {
                        failures.add(failure.getMessage());
                    }
                }
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    private static final class State implements TransactionalState {
        int value = 7;

        @Override
        public Runnable checkpoint() {
            int saved = value;
            return () -> value = saved;
        }
    }

    @Test
    public void sharedStateOracleRestoresOwnedValuesWithoutDiscardingMatchResults()
            throws Exception {
        var rows =
                Files.readAllLines(Path.of("../conformance/token-propagation/state.tsv")).stream()
                        .filter(line -> !line.startsWith("#") && !line.isBlank())
                        .toList();
        assertEquals(11, rows.size());
        for (Memoization memo : Memoization.values())
            for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
                for (String row : rows) {
                    String[] c = row.split("\t", -1);
                    assertEquals(17, c.length);
                    String label = c[0] + "/" + memo + "/" + diagnostics;
                    State state = new State();
                    ParseContext context =
                            ParseContext.withOptions(
                                    StringSource.createRootSource(decode(c[2])),
                                    ParseOptions.withMemoization(memo)
                                            .withDiagnostics(diagnostics));
                    PortableTokenMetadata metadata = new PortableTokenMetadata(context);
                    context.registerTransactionalState(state);
                    context.getCurrent().setResetMatchedWithConsumed(false);
                    Token baseline = word("p").parse(context).getRootToken();
                    var existing = metadata.register(baseline);
                    metadata.putExtra(existing, "label", "before");
                    var fresh =
                            new java.util.concurrent.atomic.AtomicReference<
                                    PortableTokenMetadata.NodeId>();
                    var callbacks = new java.util.concurrent.atomic.AtomicInteger();
                    Parser mutates =
                            new WordParser("😀") {
                                @Override
                                public Token getToken(
                                        ParseContext current, TokenKind kind, boolean invert) {
                                    callbacks.incrementAndGet();
                                    state.value = 99;
                                    metadata.putExtra(existing, "label", "during");
                                    Token token = super.getToken(current, kind, invert);
                                    var node = metadata.register(token);
                                    fresh.set(node);
                                    metadata.putRelated(existing, "new", node);
                                    if (c[1].endsWith("exception")) {
                                        if (c[1].equals("open-exception")) current.begin(this);
                                        throw new IllegalStateException("callback failure");
                                    }
                                    // Host Token side maps are intentionally independent of
                                    // portable owned state.
                                    token.putExtraObject(org.unlaxer.Name.of("host"), "retained");
                                    return token;
                                }
                            };
                    Parser child = mutates;
                    if (c[1].equals("choice"))
                        child = new Choice(new Chain(mutates, word("x")), word("😀"));
                    if (c[1].equals("raw-consume"))
                        child =
                                new Chain() {
                                    @Override
                                    public Parsed parse(
                                            ParseContext current, TokenKind kind, boolean invert) {
                                        state.value = 99;
                                        metadata.putExtra(existing, "label", "during");
                                        current.consume(new org.unlaxer.CodePointLength(1));
                                        return Parsed.SUCCEEDED;
                                    }
                                };
                    if (c[1].contains("recovery")) {
                        child = new SyncPointRecoveryParser(word("ok"), ";");
                        if (c[1].equals("consume-recovery")) child = consume(child);
                    }
                    if (c[1].equals("resource")) {
                        Parser nested = word("x");
                        for (int i = 0; i < 1500; i++) nested = match(nested);
                        child = new Chain(mutates, nested);
                    }
                    Parser parser = match(child);
                    Parsed parsed = Parsed.FAILED;
                    try (context) {
                        if (c[1].equals("outer-rollback")) context.begin(parser);
                        if (c[3].equals("resource_limit")) {
                            ParseLimitException failure =
                                    assertThrows(
                                            ParseLimitException.class, () -> parser.parse(context));
                            assertEquals(ParseLimitException.CODE, failure.code());
                            assertSame(
                                    failure,
                                    assertThrows(
                                            ParseLimitException.class,
                                            () -> word("p").parse(context)));
                        } else if (c[3].equals("callback_exception")) {
                            assertEquals(
                                    "callback failure",
                                    assertThrows(
                                                    IllegalStateException.class,
                                                    () -> parser.parse(context))
                                            .getMessage());
                            assertEquals(label, 1, context.getTokenStack().size());
                            assertEquals(label, 7, state.value);
                            context.begin(parser);
                            assertTrue(label, word("😀").parse(context).isSucceeded());
                            context.rollback(parser);
                        } else {
                            if (c[1].equals("retry")) {
                                context.begin(parser);
                                assertTrue(label, parser.parse(context).isSucceeded());
                                context.rollback(parser);
                            }
                            parsed = parser.parse(context);
                            assertEquals(label, c[3].equals("success"), parsed.isSucceeded());
                            if (c[1].equals("raw-consume")) {
                                assertEquals("matchOnly@2:0:~", signature(parsed.getRootToken()));
                            }
                            if (c[1].equals("success")) {
                                var atomLeaves = new ArrayList<Token>();
                                leaves(parsed.getRootToken(), atomLeaves);
                                assertEquals(
                                        "retained",
                                        atomLeaves
                                                .get(0)
                                                .getExtraObject(org.unlaxer.Name.of("host"))
                                                .orElseThrow());
                            }
                        }
                        if (c[1].equals("outer-rollback")) context.rollback(parser);
                        // Restoration is observable before caller-owned context cleanup.
                        assertEquals(
                                label,
                                Integer.parseInt(c[4]),
                                context.getConsumedPosition().value());
                        assertEquals(
                                label,
                                Integer.parseInt(c[5]),
                                context.getMatchedPosition().value());
                        assertEquals(label, Integer.parseInt(c[6]), state.value);
                        assertEquals(label, 1, context.getTokenStack().size());
                        if (c[1].equals("retry")) assertEquals(label, 2, callbacks.get());
                    }
                    assertEquals(
                            label, Integer.parseInt(c[4]), context.getConsumedPosition().value());
                    assertEquals(
                            label, Integer.parseInt(c[5]), context.getMatchedPosition().value());
                    assertEquals(label, Integer.parseInt(c[6]), state.value);
                    assertEquals(
                            label,
                            c[7],
                            metadata.info(existing).orElseThrow().extra().get("label"));
                    assertEquals(
                            label,
                            Boolean.parseBoolean(c[8]),
                            metadata.info(existing).orElseThrow().related().containsKey("new"));
                    assertEquals(
                            label,
                            Boolean.parseBoolean(c[9]),
                            fresh.get() != null && metadata.info(fresh.get()).isPresent());
                    assertEquals(
                            label,
                            Integer.parseInt(c[10]),
                            context.getCurrent().getTokens().size());
                    assertEquals(label, Integer.parseInt(c[11]), parsed.getOriginalTokens().size());
                    assertEquals(
                            label,
                            Integer.parseInt(c[12]),
                            RecoveryDiagnostic.from(context).size());
                    assertEquals(label, 1, context.getTokenStack().size());
                }
            }
    }

    private static final class SafeRule extends LazyChain implements SafeSuccessMemoizable {
        @Override
        public org.unlaxer.parser.Parsers getLazyParsers() {
            return new org.unlaxer.parser.Parsers(word("a"), word("b"));
        }
    }

    @Test
    public void sameRuleRetryReplaysTheMatchModeWithoutAliasingItsTree() {
        for (Memoization memo : Memoization.values())
            for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
                try (var context =
                        ParseContext.withOptions(
                                StringSource.createRootSource("ab"),
                                ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                    context.getCurrent().setResetMatchedWithConsumed(false);
                    Parser rule = new SafeRule();
                    Parser parser = match(rule);
                    context.begin(parser);
                    Parsed first = parser.parse(context);
                    Token retained = first.getRootToken();
                    context.rollback(parser);
                    assertEquals(0, context.getConsumedPosition().value());
                    assertEquals(0, context.getMatchedPosition().value());
                    assertTrue(context.getCurrent().getTokens().isEmpty());
                    Parsed second = parser.parse(context);
                    assertTrue(second.isSucceeded());
                    assertEquals("matchOnly@0:2:6162", signatures(second.getOriginalTokens()));
                    assertEquals(
                            "matchOnly@0:2:6162", signatures(context.getCurrent().getTokens()));
                    assertEquals(0, context.getConsumedPosition().value());
                    assertEquals(2, context.getMatchedPosition().value());
                    assertNotSame(retained, second.getRootToken());
                    assertEquals("matchOnly@0:2:6162", signature(retained));
                    if (memo == Memoization.OFF) assertNull(context.getPackratMemoTable());
                    else assertEquals(1, context.getPackratMemoTable().successHits());
                }
            }
    }
}
