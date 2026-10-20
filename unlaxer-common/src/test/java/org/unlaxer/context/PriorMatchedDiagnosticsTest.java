package org.unlaxer.context;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.WordParser;

/** Compare actual Classic boundaries; native PEG diagnostics have a separate Rust oracle. */
public class PriorMatchedDiagnosticsTest {
    private static final String INPUT = "p😀\r\nab!";
    private static final String PEEK = "😀\r\n";
    private static final String BODY = "😀\r\nab";

    private static final class State implements TransactionalState {
        int value = 7;
        @Override public Runnable checkpoint() {
            int before = value;
            return () -> value = before;
        }
    }

    private static Parser parser(String scenario, String word) {
        Parser hint = new Chain(new WordParser(BODY), new Optional(new WordParser("Z")));
        return switch (scenario) {
            case "peg-miss" -> new PegLookahead(new WordParser("Z"));
            case "peg-negative" -> new PegLookahead(new WordParser(BODY), false);
            case "peg-deep" -> new PegLookahead(new Chain(new WordParser(BODY), new WordParser("Z")));
            case "peg-negative-hint" -> new PegLookahead(hint, false);
            case "peg-success-hint" -> new PegLookahead(hint);
            case "peg-negative-success" -> new PegLookahead(new WordParser("Z"), false);
            case "peg-suffix" -> new Chain(new PegLookahead(new WordParser(BODY)), new WordParser("Z"));
            case "word" -> new WordParser(word);
            case "chain" -> new Chain(new WordParser(word));
            case "consume" -> new DoConsumePropagationStopper(new WordParser(word));
            case "stop-invert" -> new InvertMatchPropagationStopper(new WordParser(word));
            default -> throw new AssertionError(scenario);
        };
    }

    /** The Java side of #706: a failure after a positive LOOKAHEAD token is at its matched reach. */
    @Test public void failureAfterAPositiveLookaheadTokenIsAtTheMatchedReach() {
        for (var memo : Memoization.values()) for (var diagnostics : ParseOptions.Diagnostics.values()) {
            for (boolean reset : List.of(false, true)) for (String input : List.of("ax", "axé")) {
                String label = input + "/" + memo + "/" + diagnostics + "/" + reset;
                try (var context = ParseContext.withOptions(StringSource.createRootSource(input),
                        ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                    context.getCurrent().setResetMatchedWithConsumed(reset);
                    var parser = new Chain(new WordParser("a"), new MatchOnly(new WordParser("x")),
                            new WordParser("é"));
                    assertTrue(label, parser.parse(context).isFailed());
                    assertEquals(label, 0, context.position());
                    assertEquals(label, 0, context.getMatchedPosition().value());
                    assertEquals(label, diagnostics == ParseOptions.Diagnostics.DETAILED_ON_FAILURE ? 0 : 2,
                            context.getParseFailureDiagnostics().getFarthestOffset());
                }
            }
        }
    }

    /** A PEG assertion after the token reports the same Classic frame frontier (#706). */
    @Test public void positiveTokenThenPegAssertionUsesTheClassicMatchedFrontier() {
        for (var memo : Memoization.values()) for (var diagnostics : ParseOptions.Diagnostics.values()) {
            for (boolean reset : List.of(false, true)) {
                try (var context = ParseContext.withOptions(StringSource.createRootSource("ax"),
                        ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                    context.getCurrent().setResetMatchedWithConsumed(reset);
                    var parser = new Chain(new WordParser("a"), new MatchOnly(new WordParser("x")),
                            new PegLookahead(new WordParser("Z")));
                    assertTrue(parser.parse(context).isFailed());
                    assertEquals(0, context.position());
                    assertEquals(0, context.getMatchedPosition().value());
                    assertEquals(diagnostics == ParseOptions.Diagnostics.DETAILED_ON_FAILURE ? 0 : 2,
                            context.getParseFailureDiagnostics().getFarthestOffset());
                }
            }
        }
    }

    @Test public void matchingClassicAndTransactionalEntriesShareTheFrontier() throws Exception {
        var rows = Files.readAllLines(Path.of("../conformance/prior-matched-diagnostics/cases.tsv"))
                .stream().filter(row -> !row.startsWith("#")).toList();
        assertEquals(92, rows.size());
        for (var memo : Memoization.values()) {
            for (var diagnostics : ParseOptions.Diagnostics.values()) {
                for (boolean reset : List.of(false, true)) {
                    for (String row : rows) {
                        String[] c = row.split("\t", -1);
                        assertEquals(13, c.length);
                        String label = c[0] + "/" + memo + "/" + diagnostics + "/reset=" + reset;
                        String word = c[3].equals("~") ? "" : new String(
                                HexFormat.of().parseHex(c[3]), StandardCharsets.UTF_8);
                        Parser parser = parser(c[2], word);
                        TokenKind kind = TokenKind.valueOf(c[4]);
                        State state = new State();
                        try (var context = ParseContext.withOptions(StringSource.createRootSource(INPUT),
                                ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                            context.registerTransactionalState(state);
                            var metadata = new PortableTokenMetadata(context);
                            var prefix = new NamedCapture("stable", new WordParser("p"))
                                    .parse(context).getRootToken();
                            var id = metadata.register(prefix);
                            assertTrue(metadata.putExtra(id, "label", "before"));
                            assertTrue(new WordParser(PEEK).parse(context, TokenKind.matchOnly, false).isSucceeded());
                            context.getCurrent().setResetMatchedWithConsumed(reset);
                            var published = List.copyOf(context.getCurrent().getTokens());
                            var retained = metadata.snapshot();
                            for (int trial = 0; trial < 2; trial++) {
                                // Outer manual rollback restores prior matched even for a raw leaf reset.
                                context.beginPreservingMatched(parser);
                                var before = List.copyOf(context.getCurrent().getTokens());
                                if (c[1].equals("token")) context.beginPreservingMatched(parser);
                                var result = parser.parse(context, kind, Boolean.parseBoolean(c[5]));
                                if (c[1].equals("token")) {
                                    if (result.isSucceeded()) context.commit(parser, kind);
                                    else context.rollback(parser);
                                }
                                assertEquals(label, Boolean.parseBoolean(c[6]), result.isSucceeded());
                                assertEquals(label, Integer.parseInt(c[7]), context.position());
                                assertEquals(label, Integer.parseInt(c[8]), context.getMatchedPosition().value());
                                var failure = context.getParseFailureDiagnostics();
                                boolean detailed = diagnostics != ParseOptions.Diagnostics.DETAILED_ON_FAILURE;
                                assertEquals(label, detailed ? Integer.parseInt(c[9]) : 0, failure.getFarthestOffset());
                                assertEquals(label, detailed && Boolean.parseBoolean(c[10]), !failure.getExpectedTokens().isEmpty());
                                assertEquals(label, before, context.getCurrent().getTokens());
                                assertEquals(label, 7, state.value);
                                assertEquals(label, "p", context.captured("stable").orElseThrow().text());
                                assertEquals(label, "before", metadata.info(id).orElseThrow().extra().get("label"));
                                context.rollback(parser);
                                assertEquals(label, 1, context.position());
                                assertEquals(label, 4, context.getMatchedPosition().value());
                                assertEquals(label, 1, context.getTokenStack().size());
                                assertEquals(label, reset, context.getCurrent().isResetMatchedWithConsumed());
                                assertEquals(label, published, context.getCurrent().getTokens());
                            }
                            assertTrue(label, new WordParser(BODY + "!").parse(context).isSucceeded());
                            assertEquals(label, 7, context.position());
                            assertEquals(label, 7, context.getMatchedPosition().value());
                            assertEquals(label, "before", retained.info(id).orElseThrow().extra().get("label"));
                            assertEquals(label, "p", context.captured("stable").orElseThrow().text());
                        }
                    }
                }
            }
        }
    }

    @Test public void pegThenClassicKeepsRealFailuresAndDiscardsSuccessfulSpeculation() throws Exception {
        var rows = Files.readAllLines(Path.of("../conformance/prior-matched-diagnostics/reverse.tsv"))
                .stream().filter(row -> !row.startsWith("#")).toList();
        assertEquals(4, rows.size());
        for (var memo : Memoization.values()) for (var diagnostics : ParseOptions.Diagnostics.values()) {
            for (boolean reset : List.of(false, true)) for (String row : rows) {
                String[] c = row.split("\t", -1);
                assertEquals(6, c.length);
                String label = c[0] + "/" + memo + "/" + diagnostics + "/reset=" + reset;
                try (var context = ParseContext.withOptions(StringSource.createRootSource(INPUT),
                        ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                    assertTrue(new NamedCapture("stable", new WordParser("p")).parse(context).isSucceeded());
                    context.getCurrent().setResetMatchedWithConsumed(reset);
                    assertEquals(label, Boolean.parseBoolean(c[2]), parser(c[1], "").parse(context).isSucceeded());
                    boolean detailed = diagnostics != ParseOptions.Diagnostics.DETAILED_ON_FAILURE;
                    var initial = context.getParseFailureDiagnostics();
                    assertEquals(label, detailed ? Integer.parseInt(c[3]) : 0, initial.getFarthestOffset());
                    assertEquals(label, detailed && Boolean.parseBoolean(c[4]), !initial.getExpectedTokens().isEmpty());
                    assertEquals(label, 1, context.position());
                    assertEquals(label, 1, context.getMatchedPosition().value());
                    for (String success : List.of("peg-success-hint", "peg-negative-success")) {
                        assertTrue(label, parser(success, "").parse(context).isSucceeded());
                        var afterSuccess = context.getParseFailureDiagnostics();
                        assertEquals(label, initial.getFarthestOffset(), afterSuccess.getFarthestOffset());
                        assertEquals(label, initial.getExpectedTokens(), afterSuccess.getExpectedTokens());
                    }
                    assertTrue(new WordParser(PEEK).parse(context, TokenKind.matchOnly, false).isSucceeded());
                    for (int trial = 0; trial < 2; trial++) {
                        assertTrue(new Chain(new WordParser("Z")).parse(context).isFailed());
                        assertEquals(label, detailed ? Integer.parseInt(c[5]) : 0,
                                context.getParseFailureDiagnostics().getFarthestOffset());
                        assertEquals(label, 1, context.position());
                        assertEquals(label, 4, context.getMatchedPosition().value());
                        assertEquals(label, "p", context.captured("stable").orElseThrow().text());
                    }
                    assertTrue(new WordParser(BODY + "!").parse(context).isSucceeded());
                    assertEquals(label, 7, context.position());
                }
            }
        }
    }
}
