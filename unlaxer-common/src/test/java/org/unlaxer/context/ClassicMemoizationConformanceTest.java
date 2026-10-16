package org.unlaxer.context;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;
import org.unlaxer.CodePointLength;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.WordParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** The independent Classic oracle exercises real successes/failures, never an inert ON flag. */
public class ClassicMemoizationConformanceTest {
    static final class SafeChoice extends LazyChoice implements SafeSuccessMemoizable {
        final Parsers children;

        SafeChoice(Parser child) {
            children = new Parsers(child);
        }

        @Override
        public Parsers getLazyParsers() {
            return children;
        }
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

    private static Parser w(String text) {
        return new WordParser(text);
    }

    private static Parser scenario(String name) {
        return switch (name) {
            case "number" -> new org.unlaxer.parser.elementary.NumberParser();
            case "identifier" -> new org.unlaxer.parser.clang.IdentifierParser();
            case "quoted" -> new org.unlaxer.parser.elementary.SingleQuotedParser();
            case "word" -> w("a");
            case "word-b" -> w("b");
            case "match-chain" -> new MatchOnly(new Chain(w("a"), w("b")));
            case "consume" -> new DoConsumePropagationStopper(w("a"));
            case "stop-invert" -> new InvertMatchPropagationStopper(w("a"));
            case "double-invert" -> new NotPropagatableSource(new NotPropagatableSource(w("a")));
            case "optional" -> new org.unlaxer.parser.combinator.Optional(w("a"));
            case "repeat" -> new ZeroOrMore(w("a"));
            case "family" -> w("👨‍👩‍👧‍👦");
            case "eof" -> new org.unlaxer.parser.elementary.EndOfSourceParser();
            case "zero" -> new Repeat(w("x"), 0, 0);
            case "nonordered" -> new NonOrdered(w("b"), w("a"));
            case "projection" -> new Chain(w("b"), w("b"));
            case "capture" ->
                    new Chain(
                            w("a"),
                            w("b")); // CST/position oracle; immutable fields checked in Rust
            // and generated tests.
            default -> throw new AssertionError(name);
        };
    }

    private static String signature(Token token) {
        return token.tokenKind
                + ":"
                + token.source.offsetFromRoot().value()
                + ":"
                + token.source.codePointLength().value()
                + ":"
                + hex(token.source.sourceAsString());
    }

    private static String tree(Token token) {
        return signature(token)
                + token.getOriginalChildren().stream()
                        .map(ClassicMemoizationConformanceTest::tree)
                        .toList()
                + "/"
                + token.filteredChildren.stream()
                        .map(ClassicMemoizationConformanceTest::tree)
                        .toList();
    }

    private static String diagnostics(ParseContext context) {
        var d = context.getParseFailureDiagnostics();
        return List.of(
                        d.getFarthestOffset(),
                        d.getFarthestConsumedOffset(),
                        d.getFarthestMatchedOffset(),
                        d.getExpectedTokens(),
                        d.getExpectedParsers(),
                        d.getExpectedHintCandidates().stream()
                                .map(
                                        h ->
                                                List.of(
                                                        h.getDisplayHint(),
                                                        h.getParserQualifiedClassName(),
                                                        h.getParserDepth()))
                                .toList())
                .toString();
    }

    private List<String> rows() throws Exception {
        return Files.readAllLines(Path.of("../conformance/classic-memo/cases.tsv")).stream()
                .filter(l -> !l.startsWith("#") && !l.isBlank())
                .toList();
    }

    @Test
    public void javaRealHitsPreserveTheSharedSourceCursorCstAndRollbackOracle() throws Exception {
        var report = new ArrayList<String>();
        report.add("id\tmemo\tdiagnostics\tresult");
        for (String row : rows()) {
            String[] f = row.split("\t");
            for (ParseOptions.Diagnostics mode : ParseOptions.Diagnostics.values())
                for (boolean observed : new boolean[] {false, true}) {
                    String off = null;
                    for (Memoization memo : Memoization.values()) {
                        var parser = new SafeChoice(scenario(f[2]));
                        var snapshot =
                                new org.unlaxer.source.DocumentSnapshot(
                                        "classic-memo", 1, decode(f[1]));
                        try (var context =
                                ParseContext.withOptions(
                                        StringSource.createRootSource(decode(f[1])),
                                        ParseOptions.withMemoization(memo).withDiagnostics(mode))) {
                            var metadata = new PortableTokenMetadata(context);
                            context.getCurrent().setResetMatchedWithConsumed(false);
                            context.consume(new CodePointLength(Integer.parseInt(f[5])));
                            context.matchOnly(
                                    new CodePointLength(
                                            Integer.parseInt(f[6]) - Integer.parseInt(f[5])));
                            context.getCurrent()
                                    .setResetMatchedWithConsumed(Boolean.parseBoolean(f[7]));
                            var oldIds = new ArrayList<PortableTokenMetadata.NodeId>();
                            String first = null;
                            for (int attempt = 0; attempt < 3; attempt++) {
                                if (observed) context.startTrialRecording();
                                context.beginPreservingMatched(parser);
                                Parsed parsed =
                                        parser.parse(
                                                context,
                                                TokenKind.valueOf(f[3]),
                                                Boolean.parseBoolean(f[4]));
                                assertEquals(
                                        f[0], Boolean.parseBoolean(f[8]), parsed.isSucceeded());
                                assertEquals(f[0], Integer.parseInt(f[9]), context.position());
                                assertEquals(
                                        f[0], Integer.parseInt(f[10]), context.matchedPosition());
                                assertEquals(
                                        Integer.parseInt(f[15]),
                                        snapshot.utf16(context.position()));
                                assertEquals(
                                        Integer.parseInt(f[16]), snapshot.utf8(context.position()));
                                assertEquals(
                                        Integer.parseInt(f[17]),
                                        snapshot.utf16(context.matchedPosition()));
                                assertEquals(
                                        Integer.parseInt(f[18]),
                                        snapshot.utf8(context.matchedPosition()));
                                String structure = "-";
                                PortableTokenMetadata.NodeId id = null;
                                if (parsed.isSucceeded()) {
                                    Token root = context.getCurrent().getTokens().get(0);
                                    assertEquals(f[0], TokenKind.valueOf(f[3]), root.tokenKind);
                                    assertEquals(
                                            f[0],
                                            Integer.parseInt(f[11]),
                                            root.source.offsetFromRoot().value());
                                    assertEquals(
                                            f[0],
                                            Integer.parseInt(f[12]),
                                            root.source.codePointLength().value());
                                    assertEquals(f[0], f[13], hex(root.source.sourceAsString()));
                                    int start = root.source.offsetFromRoot().value(),
                                            end = start + root.source.codePointLength().value();
                                    assertEquals(Integer.parseInt(f[19]), snapshot.utf16(start));
                                    assertEquals(Integer.parseInt(f[20]), snapshot.utf8(start));
                                    assertEquals(Integer.parseInt(f[21]), snapshot.utf16(end));
                                    assertEquals(Integer.parseInt(f[22]), snapshot.utf8(end));
                                    assertEquals(
                                            f[0],
                                            f[14],
                                            hex(
                                                    parsed.getOriginalTokens().stream()
                                                            .map(t -> t.source.sourceAsString())
                                                            .collect(
                                                                    java.util.stream.Collectors
                                                                            .joining())));
                                    structure = tree(root);
                                    id = metadata.register(root);
                                    for (var old : oldIds)
                                        assertFalse(metadata.info(old).isPresent());
                                    assertTrue(metadata.info(id).orElseThrow().extra().isEmpty());
                                    metadata.putExtra(id, "changed", "outside memo");
                                    root.filteredChildren.clear();
                                } else {
                                    assertTrue(context.getCurrent().getTokens().isEmpty());
                                    assertEquals(
                                            f[0],
                                            mode == ParseOptions.Diagnostics.DETAILED_ON_FAILURE
                                                    ? 0
                                                    : Integer.parseInt(f[23]),
                                            context.getParseFailureDiagnostics()
                                                    .getFarthestOffset());
                                }
                                String observation =
                                        parsed.isSucceeded()
                                                + "/"
                                                + context.position()
                                                + "/"
                                                + context.matchedPosition()
                                                + "/"
                                                + structure
                                                + "/"
                                                + diagnostics(context)
                                                + "/"
                                                + context.getTrialHistory();
                                if (first == null) first = observation;
                                else assertEquals(f[0], first, observation);
                                context.rollback(parser);
                                assertEquals(Integer.parseInt(f[5]), context.position());
                                assertEquals(Integer.parseInt(f[6]), context.matchedPosition());
                                assertTrue(context.getCurrent().getTokens().isEmpty());
                                if (id != null) {
                                    assertFalse(metadata.info(id).isPresent());
                                    oldIds.add(id);
                                }
                                if (memo == Memoization.SAFE_FAILURES) {
                                    assertEquals(
                                            f[0],
                                            !observed && Boolean.parseBoolean(f[8]) ? attempt : 0,
                                            context.getPackratMemoTable().successHits());
                                    assertEquals(
                                            f[0],
                                            !observed && !Boolean.parseBoolean(f[8]) ? attempt : 0,
                                            context.getPackratMemoTable().failureHits());
                                }
                            }
                            if (memo == Memoization.OFF) off = first;
                            else assertEquals(f[0], off, first);
                            report.add(
                                    f[0]
                                            + "\t"
                                            + memo
                                            + "\t"
                                            + mode
                                            + "/observer="
                                            + observed
                                            + "\t"
                                            + first);
                        }
                    }
                }
        }
        Files.write(Path.of("target/classic-memo-java.tsv"), report);
    }

    @Test
    public void javaModeInvertAndResetKeysAreDistinctAndSameKeysActuallyHit() {
        var parser = new SafeChoice(w("a"));
        try (var c =
                ParseContext.withOptions(
                        StringSource.createRootSource("a?"),
                        ParseOptions.withMemoization(Memoization.SAFE_FAILURES))) {
            int successes = 0, failures = 0;
            for (var kind : TokenKind.values())
                for (boolean invert : new boolean[] {false, true})
                    for (boolean reset : new boolean[] {false, true}) {
                        c.getCurrent().setResetMatchedWithConsumed(reset);
                        for (boolean repeated : new boolean[] {false, true}) {
                            c.beginPreservingMatched(parser);
                            assertEquals(!invert, parser.parse(c, kind, invert).isSucceeded());
                            c.rollback(parser);
                            if (repeated) {
                                if (invert) failures++;
                                else successes++;
                            }
                            assertEquals(successes, c.getPackratMemoTable().successHits());
                            assertEquals(failures, c.getPackratMemoTable().failureHits());
                        }
                    }
            assertEquals(8, successes);
            assertEquals(8, failures);
        }
    }

    @Test
    public void rustRealHitsUseTheSameIndependentOracle() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println(
                    "[assumption] ClassicMemoizationConformanceTest requires -DrustConformance=true"
                            + " and rustc");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path dir = Files.createTempDirectory("classic-memo-test");
        try {
            Path repo = Path.of("..").toAbsolutePath().normalize();
            Path lib = dir.resolve("libunlaxer_runtime.rlib");
            Path binary = dir.resolve("oracle");
            run(
                    List.of(
                            "rustc",
                            "--edition=2021",
                            "--crate-type=rlib",
                            "--crate-name=unlaxer_runtime",
                            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),
                            "-o",
                            lib.toString()));
            run(
                    List.of(
                            "rustc",
                            "--edition=2021",
                            "--test",
                            repo.resolve("rust/unlaxer-runtime/tests/classic_memo.rs").toString(),
                            "--extern",
                            "unlaxer_runtime=" + lib,
                            "-o",
                            binary.toString()));
            Files.writeString(
                    Path.of("target/classic-memo-rust.log"),
                    run(List.of(binary.toString(), "--nocapture")));
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.delete(p);
            }
        }
    }

    private String run(List<String> command) throws Exception {
        Path log = Files.createTempFile("classic-memo-process", ".log");
        Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        try {
            assertTrue(command.toString(), process.waitFor(60, TimeUnit.SECONDS));
            String output = Files.readString(log);
            assertEquals(output, 0, process.exitValue());
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            Files.delete(log);
        }
    }
}
