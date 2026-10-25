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
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.source.DocumentSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Located in the DSL module to include semantic ScopeStore in the runtime oracle. */
public class SuccessMemoBudgetConformanceTest {
    private static final String TEXT = "👨‍👩‍👧‍👦\r\n";
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    private static final class Safe extends LazyChoice implements SafeSuccessMemoizable {
        final Parsers children =
                new Parsers(
                        new org.unlaxer.parser.combinator.Chain(
                                new WordParser(TEXT), new WordParser("!")),
                        new WordParser(TEXT));

        @Override
        public Parsers getLazyParsers() {
            return children;
        }
    }

    private static void begin(ParseContext c, Parser parser) {
        if (c.getCurrent().isResetMatchedWithConsumed()) c.begin(parser);
        else c.beginPreservingMatched(parser);
    }

    private static void success(ParseContext c, Parser parser, TokenKind kind) {
        int start = c.position(), matched = c.matchedPosition();
        begin(c, parser);
        var parsed = parser.parse(c, kind, false);
        assertTrue(parsed.isSucceeded());
        assertEquals(start + (kind.isConsumed() ? 9 : 0), c.position());
        assertEquals(start + 9, c.matchedPosition());
        var root = c.getCurrent().getTokens().get(0);
        assertEquals(TEXT, root.source.sourceAsString());
        assertEquals(start, root.source.offsetFromRoot().value());
        assertEquals(9, root.source.codePointLength().value());
        assertEquals(kind, root.tokenKind);
        assertEquals(1, parsed.getOriginalTokens().size());
        assertEquals(TEXT, parsed.getOriginalTokens().get(0).source.sourceAsString());
        assertFalse(root.getExtraObject(org.unlaxer.Name.of("changed")).isPresent());
        assertFalse(
                root.getOriginalChildren()
                        .get(0)
                        .getExtraObject(org.unlaxer.Name.of("changed"))
                        .isPresent());
        root.getOriginalChildren()
                .get(0)
                .putExtraObject(org.unlaxer.Name.of("changed"), "outside snapshot");
        root.getOriginalChildren().clear();
        root.filteredChildren.clear();
        root.putExtraObject(org.unlaxer.Name.of("changed"), "outside snapshot");
        ScopeStore.declare(c, "outer", start);
        assertTrue(ScopeStore.isDeclared(c, "outer"));
        c.rollback(parser);
        assertFalse(ScopeStore.isDeclared(c, "outer"));
        assertEquals(start, c.position());
        assertEquals(matched, c.matchedPosition());
        assertTrue(c.getCurrent().getTokens().isEmpty());
        assertEquals(
                List.of(new ScopeStore.SymbolDiagnostic("kept", 0, 1, ScopeStore.Severity.WARNING)),
                ScopeStore.getDiagnostics(c));
    }

    @Test
    public void javaSuccessBudgetsPreserveTheIndependentEpochAndSnapshotOracle() throws Exception {
        Files.deleteIfExists(Path.of("target/success-memo-budget-java.tsv"));
        var report =
                new ArrayList<>(List.of("id\tmode\treset\tmemo\tdiagnostics\tentries\tcharge"));
        for (String row :
                Files.readAllLines(repo.resolve("conformance/success-memo-budget/cases.tsv"))) {
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
                            String input = (far ? TEXT + "x".repeat(4000 - 9) : "") + TEXT + "?";
                            try (var c =
                                    ParseContext.withOptions(
                                            StringSource.createRootSource(input),
                                            ParseOptions.withMemoization(memo)
                                                    .withDiagnostics(diagnostics))) {
                                var parser = new Safe();
                                var table = c.getPackratMemoTable();
                                if (enabled) {
                                    table.setWindow(64);
                                    table.setSuccessBudget(
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
                                        for (int i = 0; i < times; i++) success(c, parser, kind);
                                    }
                                    case "epoch" -> {
                                        for (int i = 0; i < times; i++) {
                                            c.markMemoizationStateChanged();
                                            success(c, parser, kind);
                                        }
                                    }
                                    case "rollback" -> {
                                        success(c, parser, kind);
                                        long original = c.getMemoizationStateVersion();
                                        for (int i = 0; i < times; i++) {
                                            begin(c, parser);
                                            c.markMemoizationStateChanged();
                                            success(c, parser, kind);
                                            user[0] = 99;
                                            c.rollback(parser);
                                            assertEquals(original, c.getMemoizationStateVersion());
                                            assertEquals(7, user[0]);
                                        }
                                        success(c, parser, kind);
                                    }
                                    case "evict", "underrun" -> {
                                        success(c, parser, kind);
                                        begin(c, parser);
                                        c.consume(new CodePointLength(4000));
                                        success(c, parser, kind);
                                        if (enabled) {
                                            assertEquals(1, table.successEntryCount());
                                            assertTrue(table.evictedCount() > 0);
                                        }
                                        if (f[1].equals("evict")) success(c, parser, kind);
                                        c.rollback(parser);
                                        if (f[1].equals("underrun")) {
                                            success(c, parser, kind);
                                            success(c, parser, kind);
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
                                        enabled ? table.successEntryCount() : 0);
                                assertEquals(
                                        f[0],
                                        enabled ? Integer.parseInt(f[7]) : 0,
                                        enabled ? table.successHits() : 0);
                                assertEquals(
                                        f[0],
                                        enabled ? Integer.parseInt(f[8]) : 0,
                                        enabled ? table.successBudgetMisses() : 0);
                                long bytes = enabled ? table.successRetainedBytes() : 0;
                                assertTrue(bytes <= Long.parseLong(f[4]));
                                assertEquals(
                                        bytes == 0, !enabled || table.successEntryCount() == 0);
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
                                                + (enabled ? table.successEntryCount() : 0)
                                                + "\t"
                                                + bytes);
                            }
                        }
        }
        assertEquals(601, report.size());
        Files.write(Path.of("target/success-memo-budget-java.tsv"), report);
    }

    @Test
    public void rustSuccessBudgetsUseTheSameIndependentOracle() throws Exception {
        // Invalidate the previous run before an assumption, compiler error, or timeout.
        Path evidence = Path.of("target/success-memo-budget-rust.tsv").toAbsolutePath();
        Path log = Path.of("target/success-memo-budget-rust.log");
        Files.deleteIfExists(evidence);
        Files.deleteIfExists(log);
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] requires -DrustConformance=true and rustc");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path dir =
                Files.createTempDirectory(Path.of("target"), "success-budget-runtime")
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
            run(List.of(binary.toString(), "success_budget_"), Map.of(), log);
            // Keep the full suite and the isolated oracle from master. Only publish the
            // staged TSV after both processes succeed; stdout remains diagnostic evidence.
            Path staged = dir.resolve("evidence.tsv");
            run(
                    List.of(
                            binary.toString(),
                            "--exact",
                            "memo_retention_tests::success_budget_shared_oracle_bounds_epochs_and_preserves_real_hits",
                            "--nocapture"),
                    Map.of("UNLAXER_TEST_SUCCESS_BUDGET_EVIDENCE", staged.toString()),
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
        var builder = new ProcessBuilder(command);
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
