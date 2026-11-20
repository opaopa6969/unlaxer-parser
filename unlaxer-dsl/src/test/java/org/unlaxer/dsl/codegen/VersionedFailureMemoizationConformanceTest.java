package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.context.*;
import org.unlaxer.dsl.runtime.ScopeStore;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.elementary.WordParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Handwritten state/rollback/hit oracle shared with the public Rust runtime. */
public class VersionedFailureMemoizationConformanceTest {
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    private static final class SafeRule extends LazyChain implements SafeFailureMemoizable {
        private final Parsers children = new Parsers(new WordParser("😀"), new WordParser("!"));

        @Override
        public Parsers getLazyParsers() {
            return children;
        }
    }

    private static String state(ParseContext c) {
        return List.of(
                        ScopeStore.currentScopeDepth(c),
                        ScopeStore.getAllDeclarations(c),
                        ScopeStore.getAllReferences(c),
                        ScopeStore.getDiagnostics(c),
                        c.captured("owned"))
                .toString();
    }

    private static void actions(
            ParseContext c,
            Parser target,
            String input,
            int[] at,
            String[] row,
            int[] failure,
            boolean enabled,
            ParseOptions.Diagnostics mode,
            int[] user,
            List<String> observations) {
        while (at[0] < input.length()) {
            char op = input.charAt(at[0]++);
            switch (op) {
                case ')', ']' -> {
                    return;
                }
                case '(', '[' -> {
                    long version = c.getMemoizationStateVersion();
                    String before = state(c);
                    c.beginPreservingMatched(target);
                    actions(c, target, input, at, row, failure, enabled, mode, user, observations);
                    if (op == '(') {
                        c.rollback(target);
                        assertEquals(version, c.getMemoizationStateVersion());
                        assertEquals(before, state(c));
                    } else c.commit(target, org.unlaxer.TokenKind.consumed);
                }
                case 'F' -> {
                    long version = c.getMemoizationStateVersion();
                    String before = state(c);
                    c.beginPreservingMatched(target);
                    assertFalse(row[0], target.parse(c).isSucceeded());
                    assertTrue(c.getCurrent().getTokens().isEmpty());
                    int cp = c.getParseFailureDiagnostics().getFarthestOffset();
                    assertEquals(
                            row[0],
                            mode == ParseOptions.Diagnostics.DETAILED_ON_FAILURE ? 0 : 1,
                            cp);
                    c.rollback(target);
                    assertEquals(0, c.position());
                    assertEquals(0, c.matchedPosition());
                    assertEquals(version, c.getMemoizationStateVersion());
                    assertEquals(before, state(c));
                    long expected = enabled ? Long.parseLong(row[2].split(",")[failure[0]]) : 0;
                    assertEquals(
                            row[0] + " F" + failure[0],
                            expected,
                            enabled ? c.getPackratMemoTable().failureHits() : 0);
                    assertEquals(0, enabled ? c.getPackratMemoTable().successHits() : 0);
                    observations.add(
                            cp
                                    + "/"
                                    + c.getParseFailureDiagnostics().getExpectedTokens()
                                    + "/"
                                    + state(c));
                    failure[0]++;
                }
                case 'E' -> ScopeStore.enter(c);
                case 'L' -> ScopeStore.leave(c);
                case 'D' -> ScopeStore.declare(c, "😀", 0);
                case 'Z' -> ScopeStore.declare(c, "", 0);
                case 'R' -> ScopeStore.addReference(c, "😀", 1, 1);
                case 'A' -> ScopeStore.addDiagnostic(c, "kept", 1, 1, ScopeStore.Severity.WARNING);
                case 'C' -> ScopeStore.clearDiagnostics(c);
                case 'B', 'S' -> {
                    Parser binding =
                            new org.unlaxer.parser.combinator.NamedCapture(
                                    "owned",
                                    new org.unlaxer.parser.combinator.PegLookahead(
                                            new WordParser("😀")));
                    if (op == 'S')
                        binding = new org.unlaxer.parser.combinator.CaptureScope(binding);
                    assertTrue(binding.parse(c).isSucceeded());
                    assertEquals(0, c.position());
                    assertEquals(0, c.matchedPosition());
                    assertEquals(
                            op == 'S' ? java.util.Optional.empty() : java.util.Optional.of(""),
                            c.captured("owned").map(org.unlaxer.CapturedValue::text));
                }
                case 'M' -> {
                    user[0] = 99;
                    c.markMemoizationStateChanged();
                }
                default -> throw new AssertionError("unknown action " + op);
            }
        }
    }

    @Test
    public void javaStateEpochsRollbackAndWriteOnlyEventsMatchTheSharedOracle() throws Exception {
        var report = new ArrayList<>(List.of("id\tmemo\tdiagnostics\tobservations"));
        for (String text :
                Files.readAllLines(repo.resolve("conformance/stateful-failure-memo/cases.tsv"))) {
            if (text.startsWith("#") || text.isBlank()) continue;
            String[] row = text.split("\t");
            for (var mode :
                    new ParseOptions.Diagnostics[] {
                        ParseOptions.Diagnostics.DETAILED,
                        ParseOptions.Diagnostics.DETAILED_ON_FAILURE,
                        ParseOptions.Diagnostics.AUTO
                    }) {
                List<String> off = null;
                for (var memo : Memoization.values()) {
                    var target = new SafeRule();
                    try (var c =
                            ParseContext.withOptions(
                                    StringSource.createRootSource("😀?"),
                                    ParseOptions.withMemoization(memo).withDiagnostics(mode))) {
                        ScopeStore.currentScopeDepth(
                                c); // Initialize the owned empty baseline before memo keys.
                        int[] user = {7};
                        c.registerTransactionalState(
                                () -> {
                                    int saved = user[0];
                                    return () -> user[0] = saved;
                                });
                        c.getCurrent().setResetMatchedWithConsumed(false);
                        var observations = new ArrayList<String>();
                        int[] at = {0}, failure = {0};
                        actions(
                                c,
                                target,
                                row[1],
                                at,
                                row,
                                failure,
                                memo != Memoization.OFF,
                                mode,
                                user,
                                observations);
                        assertEquals(row[2].split(",").length, failure[0]);
                        assertEquals(Integer.parseInt(row[3]), ScopeStore.currentScopeDepth(c));
                        assertEquals(
                                Integer.parseInt(row[4]), ScopeStore.getAllDeclarations(c).size());
                        assertEquals(
                                Integer.parseInt(row[5]), ScopeStore.getAllReferences(c).size());
                        assertEquals(Integer.parseInt(row[6]), ScopeStore.getDiagnostics(c).size());
                        assertEquals(7, user[0]);
                        assertTrue(c.captured("owned").isEmpty());
                        for (var d : ScopeStore.getDiagnostics(c))
                            assertEquals(
                                    List.of("kept", 1, 1),
                                    List.of(d.message(), d.offset(), d.length()));
                        if (memo == Memoization.OFF) off = observations;
                        else assertEquals(row[0], off, observations);
                        report.add(row[0] + "\t" + memo + "\t" + mode + "\t" + observations);
                    }
                }
            }
        }
        assertEquals(103, report.size());
        Files.write(Path.of("target/stateful-failure-memo-java.tsv"), report);
    }

    @Test
    public void javaEpochOverflowFailsInsteadOfReusingAnEarlierVersion() throws Exception {
        try (var c = new ParseContext(StringSource.createRootSource(""))) {
            var next = ParseContext.class.getDeclaredField("nextMemoizationStateVersion");
            next.setAccessible(true);
            next.setLong(c, Long.MAX_VALUE);
            assertThrows(ArithmeticException.class, c::markMemoizationStateChanged);
            assertEquals(0, c.getMemoizationStateVersion());
        }
    }

    @Test
    public void rustStateEpochsUseTheSameIndependentFixture() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] requires -DrustConformance=true and rustc");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path dir = Files.createTempDirectory("stateful-memo");
        try {
            Path lib = dir.resolve("libunlaxer_runtime.rlib"), test = dir.resolve("oracle");
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
                            repo.resolve("rust/unlaxer-runtime/tests/stateful_failure_memo.rs")
                                    .toString(),
                            "--extern",
                            "unlaxer_runtime=" + lib,
                            "-o",
                            test.toString()));
            Files.writeString(
                    Path.of("target/stateful-failure-memo-rust.log"),
                    run(List.of(test.toString(), "--nocapture")));
        } finally {
            try (var paths = Files.walk(dir)) {
                for (var p : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.delete(p);
            }
        }
    }

    private String run(List<String> command) throws Exception {
        Path log = Files.createTempFile("stateful-memo-command", ".log");
        Process p =
                org.unlaxer.dsl.TestProcesses.builder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        try {
            assertTrue(command.toString(), p.waitFor(60, TimeUnit.SECONDS));
            String result = Files.readString(log);
            assertEquals(result, 0, p.exitValue());
            return result;
        } finally {
            if (p.isAlive()) p.destroyForcibly();
            Files.delete(log);
        }
    }
}
