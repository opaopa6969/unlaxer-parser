package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.context.*;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.WordParser;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Real pinned Tiny parsers, a handwritten corpus, and the independent Rust implementation. */
public class TinyClassicModeConformanceTest {
    private static String decode(String s) {
        return s.equals("~") ? "" : new String(HexFormat.of().parseHex(s), StandardCharsets.UTF_8);
    }

    private static void leaves(org.unlaxer.Token token, List<String> output) {
        if (token.getOriginalChildren().isEmpty())
            output.add(
                    token.tokenKind
                            + "@"
                            + token.source.offsetFromRoot().value()
                            + ":"
                            + token.source.codePointLength().value()
                            + ":"
                            + encode(token.source.sourceAsString()));
        else for (var child : token.getOriginalChildren()) leaves(child, output);
    }

    private static String encode(String s) {
        return s.isEmpty() ? "~" : HexFormat.of().formatHex(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void chainFailureResetAndOuterRollbackAgreeWithPinnedParsers() throws Exception {
        assumeTrue("requires -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        String classes = System.getProperty("tinyexpression.classes");
        assumeTrue("requires pinned tinyexpression.classes", classes != null && !classes.isBlank());
        Path oracle = Path.of(classes).toRealPath();
        var rows = Files.readAllLines(Path.of("../conformance/nullable-separation/chain-reset.tsv"))
                .stream().filter(row -> !row.startsWith("#") && !row.isBlank()).toList();
        assertEquals(24, rows.size());
        var report = new ArrayList<String>();
        try (var loader = new URLClassLoader(new URL[] {oracle.toUri().toURL()}, getClass().getClassLoader())) {
            for (int mi = 0; mi < Memoization.values().length; mi++)
                for (int di = 0; di < ParseOptions.Diagnostics.values().length; di++)
                    for (String row : rows) {
                        String[] cells = row.split("\t");
                        Parser parser;
                        if (cells[0].equals("capture-value")) {
                            parser = org.unlaxer.parser.combinator.CaptureBoundaryParser.value(new WordParser("a"));
                        } else {
                            String name = cells[0].equals("code-start") ? "CodeStartParser" : "CodeEndParser";
                            Class<?> type = Class.forName("org.unlaxer.tinyexpression.parser.javalang." + name, true, loader);
                            assertEquals(oracle, Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
                            parser = (Parser) type.getConstructor().newInstance();
                        }
                        var diagnostics = new ParseOptions.Diagnostics[] {
                                ParseOptions.Diagnostics.DETAILED, ParseOptions.Diagnostics.DETAILED_ON_FAILURE, ParseOptions.Diagnostics.AUTO};
                        try (var context = ParseContext.withOptions(StringSource.createRootSource("?"),
                                ParseOptions.withMemoization(Memoization.values()[mi]).withDiagnostics(diagnostics[di]))) {
                            context.getCurrent().setResetMatchedWithConsumed(Boolean.parseBoolean(cells[2]));
                            context.beginPreservingMatched(parser);
                            assertTrue(row, parser.parse(context, TokenKind.valueOf(cells[1]), false).isFailed());
                            assertEquals(row, Boolean.parseBoolean(cells[3]), context.getCurrent().isResetMatchedWithConsumed());
                            assertEquals(row, 0, context.getConsumedPosition().value());
                            assertEquals(row, 0, context.getMatchedPosition().value());
                            context.rollback(parser);
                            assertEquals(row, Boolean.parseBoolean(cells[4]), context.getCurrent().isResetMatchedWithConsumed());
                            assertEquals(row, 1, context.getTokenStack().size());
                            assertTrue(row, context.getCurrent().getTokens().isEmpty());
                        }
                        report.add(mi + "\t" + di + "\t" + row);
                    }
        }
        Files.write(Path.of("target/chain-reset-java.tsv"), report);
    }

    @Test
    public void pinnedParsersShareClassicModesAndPositions() throws Exception {
        assumeTrue("requires -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        String classes = System.getProperty("tinyexpression.classes");
        assumeTrue("requires pinned tinyexpression.classes", classes != null && !classes.isBlank());
        Path oracle = Path.of(classes).toRealPath(), repo = Path.of("..").toRealPath();
        var rows =
                Files.readAllLines(repo.resolve("conformance/tiny-classic-modes/cases.tsv"))
                        .stream()
                        .filter(s -> !s.startsWith("#") && !s.isBlank())
                        .toList();
        assertEquals(744, rows.size());
        var report = new ArrayList<String>();
        try (var loader =
                new URLClassLoader(
                        new URL[] {oracle.toUri().toURL()}, getClass().getClassLoader())) {
            for (int mi = 0; mi < Memoization.values().length; mi++)
                for (int di = 0; di < ParseOptions.Diagnostics.values().length; di++)
                    for (String row : rows) {
                        var memo = Memoization.values()[mi];
                        var diagnostic =
                                new ParseOptions.Diagnostics[] {
                                            ParseOptions.Diagnostics.DETAILED,
                                            ParseOptions.Diagnostics.DETAILED_ON_FAILURE,
                                            ParseOptions.Diagnostics.AUTO
                                        }
                                        [di];
                        String[] c = row.split("\t", -1);
                        String name =
                                switch (c[2].contains("-")
                                        ? c[2].substring(c[2].indexOf('-') + 1)
                                        : c[2]) {
                                    case "start" -> "CodeStartParser";
                                    case "end" -> "CodeEndParser";
                                    case "long" -> "LongCodeBlockParser";
                                    default -> throw new AssertionError(c[2]);
                                };
                        Class<?> type =
                                Class.forName(
                                        "org.unlaxer.tinyexpression.parser.javalang." + name,
                                        true,
                                        loader);
                        assertEquals(
                                "the pinned class must win over test doubles",
                                oracle,
                                Path.of(
                                                type.getProtectionDomain()
                                                        .getCodeSource()
                                                        .getLocation()
                                                        .toURI())
                                        .toRealPath());
                        Parser parser = (Parser) type.getConstructor().newInstance();
                        if (c[2].startsWith("match-"))
                            parser = new org.unlaxer.parser.combinator.MatchOnly(parser);
                        if (c[2].startsWith("consume-"))
                            parser =
                                    new org.unlaxer.parser.combinator.DoConsumePropagationStopper(
                                            parser);
                        if (c[2].startsWith("stopinvert-"))
                            parser =
                                    new org.unlaxer.parser.combinator.InvertMatchPropagationStopper(
                                            parser);
                        if (c[2].startsWith("invert-"))
                            parser =
                                    new org.unlaxer.parser.combinator.NotPropagatableSource(parser);
                        try (var ctx =
                                ParseContext.withOptions(
                                        StringSource.createRootSource(decode(c[1])),
                                        ParseOptions.withMemoization(memo)
                                                .withDiagnostics(diagnostic))) {
                            ctx.getCurrent().setResetMatchedWithConsumed(false);
                            for (int col : new int[] {5, 6})
                                if (!c[col].equals("~"))
                                    assertTrue(
                                            new WordParser(decode(c[col]))
                                                    .parse(
                                                            ctx,
                                                            col == 5
                                                                    ? TokenKind.consumed
                                                                    : TokenKind.matchOnly,
                                                            false)
                                                    .isSucceeded());
                            ctx.getCurrent()
                                    .setResetMatchedWithConsumed(Boolean.parseBoolean(c[7]));
                            var terminal = new ArrayList<String>();
                            var parsed =
                                    parser.parse(
                                            ctx,
                                            TokenKind.valueOf(c[3]),
                                            Boolean.parseBoolean(c[4]));
                            var actual =
                                    new ArrayList<>(
                                            List.of(
                                                    Boolean.toString(parsed.isSucceeded()),
                                                    Integer.toString(
                                                            ctx.getConsumedPosition().value()),
                                                    Integer.toString(
                                                            ctx.getMatchedPosition().value())));
                            if (parsed.isSucceeded()) {
                                var token = parsed.getRootToken();
                                leaves(token, terminal);
                                assertEquals(
                                        c[2].startsWith("match-")
                                                ? TokenKind.matchOnly
                                                : c[2].startsWith("consume-")
                                                        ? TokenKind.consumed
                                                        : TokenKind.valueOf(c[3]),
                                        token.tokenKind);
                                actual.addAll(
                                        List.of(
                                                encode(token.source.sourceAsString()),
                                                Integer.toString(
                                                        token.source.offsetFromRoot().value()),
                                                Integer.toString(
                                                        token.source.codePointLength().value())));
                            } else actual.addAll(List.of("-", "-", "-"));
                            assertEquals(
                                    c[0] + "/" + memo + "/" + diagnostic,
                                    List.of(c).subList(8, c.length),
                                    actual);
                            assertEquals(1, ctx.getTokenStack().size());
                            var failure = ctx.getParseFailureDiagnostics();
                            report.add(
                                    c[0]
                                            + "\t"
                                            + mi
                                            + "\t"
                                            + di
                                            + "\t"
                                            + String.join("\t", actual)
                                            + "\t"
                                            + failure.getFarthestOffset()
                                            + "\t"
                                            + !failure.getExpectedTokens().isEmpty()
                                            + "\t"
                                            + (terminal.isEmpty()
                                                    ? "~"
                                                    : String.join(";", terminal)));
                        }
                    }
        }
        var threadFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread =
                new Thread(
                        null,
                        () -> {
                            try (var loader =
                                    new URLClassLoader(
                                            new URL[] {oracle.toUri().toURL()},
                                            getClass().getClassLoader())) {
                                for (String name :
                                        List.of(
                                                "CodeStartParser",
                                                "CodeEndParser",
                                                "LongCodeBlockParser")) {
                                    Parser nested =
                                            (Parser)
                                                    Class.forName(
                                                                    "org.unlaxer.tinyexpression.parser.javalang."
                                                                            + name,
                                                                    true,
                                                                    loader)
                                                            .getConstructor()
                                                            .newInstance();
                                    for (int i = 0; i < 1500; i++)
                                        nested =
                                                new org.unlaxer.parser.combinator.MatchOnly(nested);
                                    Parser parser = nested;
                                    try (var ctx =
                                            ParseContext.withOptions(
                                                    StringSource.createRootSource(
                                                            "````x:A\ntext\n````"),
                                                    ParseOptions.withMemoization(
                                                            Memoization.OFF))) {
                                        var failure =
                                                assertThrows(
                                                        ParseLimitException.class,
                                                        () -> parser.parse(ctx));
                                        assertEquals(ParseLimitException.CODE, failure.code());
                                        assertEquals(0, ctx.getConsumedPosition().value());
                                        assertEquals(0, ctx.getMatchedPosition().value());
                                    }
                                }
                            } catch (Throwable failure) {
                                threadFailure.set(failure);
                            }
                        },
                        "tiny-mode-budget",
                        2L * 1024 * 1024);
        thread.start();
        thread.join(30000);
        assertFalse("resource guard must terminate", thread.isAlive());
        if (threadFailure.get() != null) throw new AssertionError(threadFailure.get());
        Path target = Path.of("target/tiny-classic-mode").toAbsolutePath();
        Files.createDirectories(target);
        Path runtime = target.resolve("libunlaxer_runtime.rlib"), exe = target.resolve("oracle");
        run(
                List.of(
                        "rustc",
                        "--edition=2021",
                        "--crate-type=rlib",
                        "--crate-name=unlaxer_runtime",
                        repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),
                        "-o",
                        runtime.toString()),
                target.resolve("compile-runtime.log"));
        run(
                List.of(
                        "rustc",
                        "--edition=2021",
                        repo.resolve("rust/unlaxer-runtime/tests/tiny_code_modes.rs").toString(),
                        "--extern",
                        "unlaxer_runtime=" + runtime,
                        "-o",
                        exe.toString()),
                target.resolve("compile-oracle.log"));
        Path rust = target.resolve("rust.tsv");
        run(List.of(exe.toString()), rust);
        Files.write(Path.of("target/tiny-classic-java.tsv"), report);
        var nativeRows = Files.readAllLines(rust);
        assertEquals(report.size(), nativeRows.size());
        for (int i = 0; i < report.size(); i++)
            assertEquals("row " + i, report.get(i), nativeRows.get(i));
    }

    private void run(List<String> args, Path log) throws Exception {
        var process =
                new ProcessBuilder(args)
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("timeout " + args);
        }
        assertEquals(Files.readString(log), 0, process.exitValue());
    }
}
