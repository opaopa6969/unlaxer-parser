package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.WordParser;

/** #881: paired hand-built public grammars, without generated adapter lowering. */
public class MidParseDiagnosticsConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();
    private final Path corpus = repo.resolve("conformance/chain-reset-after-failure");

    private static List<String> data(Path path) throws Exception {
        return Files.readAllLines(path).stream().filter(s -> !s.isBlank() && !s.startsWith("#")).toList();
    }

    private static final class SafeChain extends Chain implements org.unlaxer.context.SafeSuccessMemoizable {
        private static final long serialVersionUID = 1L;
        SafeChain(Parser... children) { super(children); }
    }

    private static String state(ParseContext context, boolean success) {
        return success + "\t" + context.position() + "\t" + context.matchedPosition()
                + "\t" + context.getCurrent().isResetMatchedWithConsumed()
                + "\t" + context.getParseFailureDiagnostics().getFarthestOffset()
                + "\t" + (context.getPackratMemoTable() == null ? 0 : context.getPackratMemoTable().failureHits())
                + "\t" + (context.getPackratMemoTable() == null ? 0 : context.getPackratMemoTable().successHits());
    }

    private static String observe(String[] c, boolean classic, boolean initial, Memoization memo,
            ParseOptions.Diagnostics mode, boolean read) {
        var middle = new ArrayList<Integer>();
        Parser observer = new WordParser("z") {
            private static final long serialVersionUID = 1L;
            @Override public Token getToken(ParseContext context, TokenKind kind, boolean invert) {
                if (read) {
                    int cp = context.getParseFailureDiagnostics().getFarthestOffset();
                    // Repeated reads must not alter even the next intermediate observation.
                    assertEquals(cp, context.getParseFailureDiagnostics().getFarthestOffset());
                    middle.add(cp);
                }
                return super.getToken(context, kind, invert);
            }
        };
        Parser head = new WordParser(c[1]);
        head = switch (c[3]) {
            case "frame" -> new NonOrdered(head);
            case "ahead" -> new Chain(new MatchOnly(new WordParser(c[1] + "z")), head);
            case "failure-before" -> new Choice(new WordParser("!"), head);
            case "failure-at-head" -> new Chain(head,
                    new org.unlaxer.parser.combinator.Optional(new WordParser("!")));
            case "failure-ahead" -> new Choice(new Chain(new WordParser(c[1] + "z"), new WordParser("!")), head);
            default -> head;
        };
        Parser parser = c[3].equals("suffix-failure")
                ? new Chain(head, new Chain(observer), new WordParser("!"))
                : new Chain(head, new Chain(observer));
        try (var context = ParseContext.withOptions(StringSource.createRootSource(c[2]),
                ParseOptions.withMemoization(memo).withDiagnostics(mode))) {
            context.getCurrent().setResetMatchedWithConsumed(initial);
            boolean success = classic ? parser.parse(context, TokenKind.consumed, false).isSucceeded()
                    : parser.parse(context).isSucceeded();
            String result = state(context, success);
            assertEquals(String.join("/", c) + "/" + classic + "/" + initial + "/" + memo + "/" + mode, read ? 1 : 0, middle.size());
            assertEquals(1, context.getTokenStack().size());
            Parser follower = new NonOrdered(new MatchOnly(new WordParser("b")));
            result += "\t" + state(context, follower.parse(context).isSucceeded());
            // A memo-safe failed rule is retried on the same context, with the observer disabled/enabled.
            Parser failed = new SafeChain(new WordParser("!"), new WordParser("?"));
            for (int i = 0; i < 3; i++) {
                assertTrue(failed.parse(context).isFailed());
                result += "\t" + state(context, false);
            }
            assertEquals(1, context.getTokenStack().size());
            return (read ? middle.get(0) : -1) + "\t" + result;
        }
    }

    @Test public void javaObservationsMatchOracleAndReadsAreTransparent() throws Exception {
        var actual = new ArrayList<String>();
        for (String row : data(corpus.resolve("mid-parse-inputs.tsv"))) {
            String[] c = row.split("\t");
            for (boolean classic : List.of(false, true)) for (boolean initial : List.of(false, true))
                for (var memo : Memoization.values()) for (var mode : ParseOptions.Diagnostics.values()) {
                String label = c[0] + "\t" + classic + "\t" + initial + "\t" + memo.ordinal() + "\t" + mode.ordinal();
                String read = observe(c, classic, initial, memo, mode, true);
                String silent = observe(c, classic, initial, memo, mode, false);
                assertEquals(label + "/read transparency", silent.substring(silent.indexOf('\t')),
                        read.substring(read.indexOf('\t')));
                actual.add(label + "\t" + read);
            }
        }
        assertEquals(384, actual.size());
        var withoutMemo = new java.util.HashMap<String, List<String>>();
        for (String row : actual) {
            String[] cells = row.split("\t");
            String key = cells[0] + "/" + cells[1] + "/" + cells[2] + "/" + cells[4];
            var semantic = new ArrayList<String>();
            semantic.add(cells[5]);
            for (int start = 6; start < cells.length; start += 7)
                semantic.addAll(Arrays.asList(cells).subList(start, start + 5));
            if (cells[3].equals("0")) withoutMemo.put(key, semantic);
            else assertEquals(key + "/memo transparency", withoutMemo.get(key), semantic);
            assertEquals("real failure hits", cells[3].equals("0") ? "0" : "2", cells[39]);
        }
        Files.write(Path.of("target/mid-parse-java.tsv"), actual);
        assertEquals(data(corpus.resolve("mid-parse-java.tsv")), actual);
    }

    /** Only the intermediate CP can differ. The table contains every row, including equal rows. */
    private List<String> rustOracle() throws Exception {
        List<String> java = data(corpus.resolve("mid-parse-java.tsv"));
        List<String> differences = data(corpus.resolve("mid-parse-observations.tsv"));
        assertEquals(java.size(), differences.size());
        var expected = new ArrayList<String>();
        for (int i = 0; i < java.size(); i++) {
            String[] row = java.get(i).split("\t"), diff = differences.get(i).split("\t");
            assertEquals(7, diff.length);
            assertEquals(String.join("\t", Arrays.copyOf(row, 5)), String.join("\t", Arrays.copyOf(diff, 5)));
            assertEquals(row[5], diff[5]);
            assertTrue("Rust intermediate CP must be a lower bound: " + differences.get(i),
                    Integer.parseInt(diff[6]) <= Integer.parseInt(diff[5]));
            assertTrue(Integer.parseInt(diff[6]) >= 0);
            if (row[0].contains("failure-before") || row[0].contains("failure-at-head") || row[0].contains("failure-ahead"))
                assertEquals("actual failures agree even during parsing", diff[5], diff[6]);
            row[5] = diff[6];
            expected.add(String.join("\t", row));
        }
        return expected;
    }

    @Test public void rustMatchesIntermediateBoundsAndExactFinalOracle() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] MidParseDiagnosticsConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path runtime = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
                repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()));
        Path main = temporary.getRoot().toPath().resolve("main.rs"), binary = temporary.getRoot().toPath().resolve("probe");
        Files.writeString(main, """
                #[path = "%s"] mod probe;
                fn main() { for row in probe::observations() { println!("{row}"); } }
                """.formatted(repo.resolve("rust/unlaxer-runtime/tests/support/mid_parse_diagnostics.rs")));
        run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + runtime, main.toString(), "-o", binary.toString()));
        List<String> actual = run(List.of(binary.toString())).lines().toList();
        Files.write(Path.of("target/mid-parse-rust.tsv"), actual);
        List<String> expected = rustOracle();
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertEquals("row " + i, expected.get(i), actual.get(i));
    }

    private String run(List<String> command) throws Exception {
        Path log = temporary.newFile().toPath();
        var process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            assertTrue("timeout: " + command, process.waitFor(180, TimeUnit.SECONDS));
            assertEquals(command + "\n" + Files.readString(log), 0, process.exitValue());
            return Files.readString(log);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
