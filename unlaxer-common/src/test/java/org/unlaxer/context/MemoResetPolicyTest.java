package org.unlaxer.context;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.elementary.WordParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** #621: a cursor reset policy is part of the parser input, including cache probes. */
public class MemoResetPolicyTest {
    static final class SafeChoice extends LazyChoice implements SafeSuccessMemoizable {
        final Parsers children;

        SafeChoice(String word) {
            children = new Parsers(new WordParser(word));
        }

        @Override
        public Parsers getLazyParsers() {
            return children;
        }
    }

    private record Case(
            String id,
            String input,
            String word,
            int consumed,
            int matched,
            boolean warmReset,
            boolean nextReset,
            boolean warmOk,
            boolean nextOk,
            int endConsumed,
            int endMatched,
            int tokenStart,
            int tokenEnd,
            String tokenHex,
            int hits) {}

    private record Outcome(String summary, String tree, String diagnostic) {}

    private List<Case> cases() throws Exception {
        var cases = new ArrayList<Case>();
        for (String row :
                Files.readAllLines(Path.of("../conformance/memo-reset-policy/cases.tsv"))) {
            if (row.startsWith("#") || row.isBlank()) continue;
            String[] p = row.split("\t");
            cases.add(
                    new Case(
                            p[0],
                            decode(p[1]),
                            decode(p[2]),
                            Integer.parseInt(p[3]),
                            Integer.parseInt(p[4]),
                            Boolean.parseBoolean(p[5]),
                            Boolean.parseBoolean(p[6]),
                            Boolean.parseBoolean(p[7]),
                            Boolean.parseBoolean(p[8]),
                            Integer.parseInt(p[9]),
                            Integer.parseInt(p[10]),
                            Integer.parseInt(p[11]),
                            Integer.parseInt(p[12]),
                            p[13],
                            Integer.parseInt(p[14])));
        }
        return cases;
    }

    private static String decode(String hex) {
        return hex.equals("-")
                ? ""
                : new String(HexFormat.of().parseHex(hex), StandardCharsets.UTF_8);
    }

    private static String hex(String text) {
        return text.isEmpty()
                ? "-"
                : HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String tree(Token token) {
        return token.tokenKind
                + ":"
                + token.source.offsetFromRoot().value()
                + ":"
                + token.source.codePointLength().value()
                + ":"
                + hex(token.source.sourceAsString())
                + token.getOriginalChildren().stream().map(MemoResetPolicyTest::tree).toList()
                + "/"
                + token.filteredChildren.stream().map(MemoResetPolicyTest::tree).toList();
    }

    private Outcome run(Case row, Memoization memo) {
        SafeChoice parser = new SafeChoice(row.word);
        try (ParseContext context =
                ParseContext.withOptions(
                        StringSource.createRootSource(row.input),
                        ParseOptions.withMemoization(memo)
                                .withDiagnostics(ParseOptions.Diagnostics.DETAILED))) {
            context.getCurrent().setResetMatchedWithConsumed(false);
            context.consume(new CodePointLength(row.consumed));
            context.matchOnly(new CodePointLength(row.matched - row.consumed));
            context.getCurrent().setResetMatchedWithConsumed(row.warmReset);
            context.beginPreservingMatched(parser);
            Parsed warm = parser.parse(context, TokenKind.matchOnly, false);
            assertEquals(row.id + "/warm/" + memo, row.warmOk, warm.isSucceeded());
            Token prior = warm.isSucceeded() ? context.getCurrent().getTokens().get(0) : null;
            context.rollback(parser);
            assertEquals(row.id, row.consumed, context.position());
            assertEquals(row.id, row.matched, context.matchedPosition());
            if (prior != null) prior.filteredChildren.clear(); // cache must own its snapshot
            context.getCurrent().setResetMatchedWithConsumed(row.nextReset);
            context.beginPreservingMatched(parser);
            Parsed parsed = parser.parse(context, TokenKind.matchOnly, false);
            assertEquals(row.id + "/second/" + memo, row.nextOk, parsed.isSucceeded());
            assertEquals(row.id, row.endConsumed, context.position());
            assertEquals(row.id, row.endMatched, context.matchedPosition());
            String actualTree = "-";
            int start = -1, end = -1;
            String text = "-";
            if (parsed.isSucceeded()) {
                assertEquals(row.id, 1, context.getCurrent().getTokens().size());
                Token token = context.getCurrent().getTokens().get(0);
                assertNotSame(prior, token);
                start = token.source.offsetFromRoot().value();
                end = start + token.source.codePointLength().value();
                text = hex(token.source.sourceAsString());
                actualTree = tree(token);
            } else assertTrue(context.getCurrent().getTokens().isEmpty());
            assertEquals(row.id, row.tokenStart, start);
            assertEquals(row.id, row.tokenEnd, end);
            assertEquals(row.id, row.tokenHex, text);
            if (memo == Memoization.SAFE_FAILURES) {
                assertEquals(
                        row.id,
                        row.warmOk ? 0 : row.hits,
                        context.getPackratMemoTable().failureHits());
                assertEquals(
                        row.id,
                        row.warmOk ? row.hits : 0,
                        context.getPackratMemoTable().successHits());
            }
            var diagnostic = context.getParseFailureDiagnostics();
            String diagnostics =
                    List.of(
                                    diagnostic.getFarthestOffset(),
                                    diagnostic.getFarthestConsumedOffset(),
                                    diagnostic.getFarthestMatchedOffset(),
                                    diagnostic.getExpectedParsers(),
                                    diagnostic.getExpectedTokens(),
                                    diagnostic.getExpectedHintCandidates().stream()
                                            .map(
                                                    hint ->
                                                            List.of(
                                                                    hint.getDisplayHint(),
                                                                    hint
                                                                            .getParserQualifiedClassName(),
                                                                    hint.getParserDepth()))
                                            .toList())
                            .toString();
            String summary =
                    parsed.isSucceeded()
                            + "\t"
                            + context.position()
                            + "\t"
                            + context.matchedPosition()
                            + "\t"
                            + start
                            + "\t"
                            + end
                            + "\t"
                            + text;
            context.rollback(parser);
            assertEquals(row.id, row.consumed, context.position());
            assertEquals(row.id, row.matched, context.matchedPosition());
            assertTrue(context.getCurrent().getTokens().isEmpty());
            return new Outcome(summary, actualTree, diagnostics);
        }
    }

    @Test
    public void legacyPositionKeyConstructorKeepsTheDefaultPolicy() {
        var legacy = new PackratMemoTable.PositionKey(0, 1, TokenKind.matchOnly, false, 7);
        assertEquals(
                new PackratMemoTable.PositionKey(0, 1, TokenKind.matchOnly, false, 7, true),
                legacy);
        assertNotEquals(
                new PackratMemoTable.PositionKey(0, 1, TokenKind.matchOnly, false, 7, false),
                legacy);
    }

    @Test
    public void javaFailureCacheSeparatesResetPolicies() throws Exception {
        verifyJava(false);
    }

    @Test
    public void javaSuccessCacheSeparatesResetPolicies() throws Exception {
        verifyJava(true);
    }

    private void verifyJava(boolean success) throws Exception {
        var report =
                new ArrayList<>(List.of("case\tok\tconsumed\tmatched\tstart\tend\ttoken_utf8_hex"));
        for (Case row : cases()) {
            if (row.warmOk != success) continue;
            Outcome off = run(row, Memoization.OFF);
            assertEquals(row.id, off, run(row, Memoization.SAFE_FAILURES));
            report.add(row.id + "\t" + off.summary);
        }
        Files.write(
                Path.of(
                        "target/memo-reset-policy-java-"
                                + (success ? "success" : "failure")
                                + ".tsv"),
                report);
    }

    @Test
    public void rustExistingResetKeyAndClassicNoHitBoundaryAgreeWithTheOracle() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println(
                    "[assumption] MemoResetPolicyTest native comparison requires"
                        + " -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path output = Files.createTempDirectory("memo-reset-native-");
        try {
            Path root = Path.of("..").toAbsolutePath().normalize();
            Path library = output.resolve("libunlaxer_runtime.rlib");
            command(
                    List.of(
                            "rustc",
                            "--edition=2021",
                            "--crate-type=rlib",
                            "--crate-name=unlaxer_runtime",
                            root.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),
                            "-o",
                            library.toString()),
                    output);
            Path test = output.resolve("test");
            command(
                    List.of(
                            "rustc",
                            "--edition=2021",
                            "--test",
                            root.resolve("rust/unlaxer-runtime/tests/memo_reset_policy.rs")
                                    .toString(),
                            "--extern",
                            "unlaxer_runtime=" + library,
                            "-o",
                            test.toString()),
                    output);
            String result =
                    command(List.of(test.toString(), "--nocapture", "--test-threads=1"), output);
            List<String> observations =
                    result.lines().filter(line -> line.startsWith("MEMO\t")).toList();
            assertEquals(cases().size() * 2, observations.size());
            for (Case row : cases()) {
                String expected = run(row, Memoization.OFF).summary;
                for (String mode : List.of("OFF", "ON"))
                    assertTrue(
                            row.id + "\n" + result,
                            observations.contains(
                                    "MEMO\t" + row.id + "\t" + mode + "\t" + expected));
            }
            Files.writeString(Path.of("target/memo-reset-policy-rust.log"), result);
        } finally {
            try (var paths = Files.walk(output)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    private String command(List<String> args, Path output) throws Exception {
        Path log = Files.createTempFile(output, "run-", ".log");
        var process =
                new ProcessBuilder(args)
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        try {
            assertTrue(args.toString(), process.waitFor(60, TimeUnit.SECONDS));
            String text = Files.readString(log);
            assertEquals(text, 0, process.exitValue());
            return text;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
