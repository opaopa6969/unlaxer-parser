package org.unlaxer.context;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;
import org.unlaxer.CodePointLength;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.dsl.runtime.ScopeStore;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.source.DocumentSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Located in the DSL module to include semantic ScopeStore in the runtime oracle. */
public class FailureMemoBudgetConformanceTest {
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    private static final class Safe extends LazyChain implements SafeFailureMemoizable {
        final Parsers children = new Parsers(new WordParser("😀\r\n"), new WordParser("!"));

        @Override
        public Parsers getLazyParsers() {
            return children;
        }
    }

    private static void failure(ParseContext c, Parser parser, TokenKind kind) {
        int start = c.position(), match = c.matchedPosition();
        c.beginPreservingMatched(parser);
        assertFalse(parser.parse(c, kind, false).isSucceeded());
        assertTrue(c.getCurrent().getTokens().isEmpty());
        c.rollback(parser);
        assertEquals(start, c.position());
        assertEquals(match, c.matchedPosition());
        assertEquals(
                List.of(new ScopeStore.SymbolDiagnostic("kept", 0, 1, ScopeStore.Severity.WARNING)),
                ScopeStore.getDiagnostics(c));
    }

    @Test
    public void javaFiniteBudgetsPreserveTheIndependentEpochAndCursorOracle() throws Exception {
        Files.deleteIfExists(Path.of("target/failure-memo-budget-java.tsv"));
        var report =
                new ArrayList<>(List.of("id\tmode\treset\tmemo\tdiagnostics\tentries\tcharge"));
        for (String row :
                Files.readAllLines(repo.resolve("conformance/failure-memo-budget/cases.tsv"))) {
            if (row.startsWith("#") || row.isBlank()) continue;
            String[] f = row.split("\t");
            int times = Integer.parseInt(f[2]);
            for (String mode :
                    List.of(
                            "normal",
                            "consumed",
                            "matchOnly",
                            "virtualTokenConsumed",
                            "virtualTokenMatchOnly"))
                for (boolean reset : new boolean[] {false, true})
                    for (var diagnostics : ParseOptions.Diagnostics.values())
                        for (var memo : Memoization.values()) {
                            boolean enabled = memo != Memoization.OFF;
                            TokenKind kind =
                                    mode.equals("normal")
                                            ? TokenKind.consumed
                                            : TokenKind.valueOf(mode);
                            boolean far = f[1].equals("evict") || f[1].equals("underrun");
                            String input = (far ? "x".repeat(4000) : "") + "😀\r\n?";
                            try (var c =
                                    ParseContext.withOptions(
                                            StringSource.createRootSource(input),
                                            ParseOptions.withMemoization(memo)
                                                    .withDiagnostics(diagnostics))) {
                                var parser = new Safe();
                                var table = c.getPackratMemoTable();
                                if (enabled) {
                                    table.setWindow(64);
                                    table.setFailureBudget(
                                            Integer.parseInt(f[3]),
                                            Long.parseLong(f[4]),
                                            Long.parseLong(f[5]));
                                }
                                c.getCurrent().setResetMatchedWithConsumed(reset);
                                ScopeStore.addDiagnostic(
                                        c, "kept", 0, 1, ScopeStore.Severity.WARNING);
                                int[] user = {7};
                                c.registerTransactionalState(
                                        () -> {
                                            int saved = user[0];
                                            return () -> user[0] = saved;
                                        });
                                switch (f[1]) {
                                    case "same" -> {
                                        for (int i = 0; i < times; i++) failure(c, parser, kind);
                                    }
                                    case "epoch" -> {
                                        for (int i = 0; i < times; i++) {
                                            c.markMemoizationStateChanged();
                                            failure(c, parser, kind);
                                        }
                                    }
                                    case "rollback" -> {
                                        failure(c, parser, kind);
                                        long original = c.getMemoizationStateVersion();
                                        for (int i = 0; i < times; i++) {
                                            c.beginPreservingMatched(parser);
                                            c.markMemoizationStateChanged();
                                            failure(c, parser, kind);
                                            user[0] = 99;
                                            c.rollback(parser);
                                            assertEquals(original, c.getMemoizationStateVersion());
                                            assertEquals(7, user[0]);
                                        }
                                        failure(c, parser, kind);
                                    }
                                    case "evict", "underrun" -> {
                                        failure(c, parser, kind);
                                        c.beginPreservingMatched(parser);
                                        c.consume(new CodePointLength(4000));
                                        failure(c, parser, kind);
                                        if (enabled) {
                                            assertEquals(1, table.failureEntryCount());
                                            assertTrue(table.evictedCount() > 0);
                                        }
                                        if (f[1].equals("evict")) failure(c, parser, kind);
                                        c.rollback(parser);
                                        if (f[1].equals("underrun")) {
                                            failure(c, parser, kind);
                                            failure(c, parser, kind);
                                            if (enabled) assertTrue(table.evictionUnderruns() > 0);
                                        }
                                    }
                                    default -> throw new AssertionError(f[1]);
                                }
                                assertEquals(7, user[0]);
                                assertEquals(0, c.position());
                                assertEquals(0, c.matchedPosition());
                                assertTrue(c.getCurrent().getTokens().isEmpty());
                                assertEquals(
                                        f[0],
                                        enabled ? Integer.parseInt(f[6]) : 0,
                                        enabled ? table.failureEntryCount() : 0);
                                assertEquals(
                                        f[0],
                                        enabled ? Integer.parseInt(f[7]) : 0,
                                        enabled ? table.failureHits() : 0);
                                assertEquals(
                                        f[0],
                                        enabled ? Integer.parseInt(f[8]) : 0,
                                        enabled ? table.failureBudgetMisses() : 0);
                                long bytes = enabled ? table.failureRetainedBytes() : 0;
                                assertTrue(bytes <= Long.parseLong(f[4]));
                                assertEquals(
                                        bytes == 0, !enabled || table.failureEntryCount() == 0);
                                int at =
                                        diagnostics == ParseOptions.Diagnostics.DETAILED_ON_FAILURE
                                                ? 0
                                                : Integer.parseInt(f[9]);
                                assertEquals(
                                        f[0],
                                        at,
                                        c.getParseFailureDiagnostics().getFarthestOffset());
                                assertEquals(
                                        f[0],
                                        at == 0 ? java.util.Set.of() : java.util.Set.of("'!'"),
                                        c.getParseFailureDiagnostics().getExpectedTokens());
                                var snapshot = new DocumentSnapshot("budget", 1, input);
                                assertEquals(
                                        at == 0 ? 0 : Integer.parseInt(f[10]), snapshot.utf16(at));
                                assertEquals(
                                        at == 0 ? 0 : Integer.parseInt(f[11]), snapshot.utf8(at));
                                assertEquals(input, c.source.sourceAsString());
                                report.add(
                                        f[0]
                                                + "\t"
                                                + mode
                                                + "\t"
                                                + reset
                                                + "\t"
                                                + memo
                                                + "\t"
                                                + diagnostics
                                                + "\t"
                                                + (enabled ? table.failureEntryCount() : 0)
                                                + "\t"
                                                + bytes);
                            }
                        }
        }
        assertEquals(601, report.size());
        Files.write(Path.of("target/failure-memo-budget-java.tsv"), report);
    }

    @Test
    public void rustFiniteBudgetsUseTheSameIndependentOracle() throws Exception {
        // Invalidate the previous run before an assumption, compiler error, or timeout.
        Path evidence = Path.of("target/failure-memo-budget-rust.tsv").toAbsolutePath();
        Path log = Path.of("target/failure-memo-budget-rust.log");
        Files.deleteIfExists(evidence);
        Files.deleteIfExists(log);
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] requires -DrustConformance=true and rustc");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path dir =
                Files.createTempDirectory(Path.of("target"), "failure-budget-runtime")
                        .toAbsolutePath();
        try {
            Path binary = dir.resolve("runtime-tests");
            run(
                    List.of(
                            "rustc",
                            "--edition=2021",
                            "--test",
                            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),
                            "-o",
                            binary.toString()),
                    Map.of(),
                    log);
            run(List.of(binary.toString(), "failure_budget_"), Map.of(), log);
            // Keep the full suite and the isolated oracle from master. Only publish the
            // staged TSV after both processes succeed; stdout remains diagnostic evidence.
            Path staged = dir.resolve("evidence.tsv");
            run(
                    List.of(
                            binary.toString(),
                            "--exact",
                            "memo_retention_tests::failure_budget_shared_oracle_bounds_epochs_and_preserves_real_hits",
                            "--nocapture"),
                    Map.of("UNLAXER_TEST_FAILURE_BUDGET_EVIDENCE", staged.toString()),
                    log);
            assertEquals(601, Files.readAllLines(staged).size());
            Files.move(staged, evidence, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            try (var paths = Files.walk(dir)) {
                for (var p : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.delete(p);
            }
        }
    }

    private void run(List<String> command, Map<String, String> environment, Path log) throws Exception {
        Files.writeString(
                log,
                "\n$ " + String.join(" ", command) + "\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        var builder = org.unlaxer.dsl.TestProcesses.builder(command);
        builder.environment().remove("UNLAXER_TEST_FAILURE_BUDGET_EVIDENCE");
        builder.environment().remove("UNLAXER_TEST_SUCCESS_BUDGET_EVIDENCE");
        builder.environment().putAll(environment);
        var p =
                builder.redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                        .start();
        try {
            assertTrue(command.toString(), p.waitFor(60, TimeUnit.SECONDS));
            assertEquals(Files.readString(log), 0, p.exitValue());
        } finally {
            if (p.isAlive()) {
                p.destroyForcibly();
                assertTrue("process did not terminate: " + command, p.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }
}
