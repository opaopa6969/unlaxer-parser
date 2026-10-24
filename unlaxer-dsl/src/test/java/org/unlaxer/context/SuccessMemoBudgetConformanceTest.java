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
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] requires -DrustConformance=true and rustc");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path dir = Files.createTempDirectory("success-budget-runtime");
        try {
            Path binary = dir.resolve("runtime-tests");
            run(
                    List.of(
                            "rustc",
                            "--edition=2021",
                            "--test",
                            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),
                            "-o",
                            binary.toString()));
            // #789: the oracle rows go to their own file. libtest's progress lines share
            // stdout with the test threads, so the .log is kept only for diagnosis.
            Path evidence = Path.of("target/success-memo-budget-rust.tsv").toAbsolutePath();
            Files.deleteIfExists(evidence);
            Files.writeString(
                    Path.of("target/success-memo-budget-rust.log"),
                    run(
                            List.of(binary.toString(), "success_budget_", "--nocapture"),
                            Map.of(
                                    "UNLAXER_TEST_SUCCESS_BUDGET_EVIDENCE",
                                    evidence.toString())));
            assertEquals(601, Files.readAllLines(evidence).size());
        } finally {
            try (var paths = Files.walk(dir)) {
                for (var p : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.delete(p);
            }
        }
    }

    private String run(List<String> command) throws Exception {
        return run(command, Map.of());
    }

    private String run(List<String> command, Map<String, String> environment) throws Exception {
        Path log = Files.createTempFile("success-budget-command", ".log");
        var builder = new ProcessBuilder(command);
        builder.environment().putAll(environment);
        var p = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(command.toString(), p.waitFor(60, TimeUnit.SECONDS));
            String text = Files.readString(log);
            assertEquals(text, 0, p.exitValue());
            return text;
        } finally {
            if (p.isAlive()) p.destroyForcibly();
            Files.delete(log);
        }
    }
}
