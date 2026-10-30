package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.dsl.runtime.ScopeStore;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.*;
import org.unlaxer.parser.combinator.*;

/** 手書き仕様から作った期待値へ両言語を独立に照合する。Java の結果を oracle にしない。 */
public class LineBoundaryConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    private static String decode(String value) {
        return value.equals("~") ? "" : new String(HexFormat.of().parseHex(value), StandardCharsets.UTF_8);
    }
    private static String encode(String value) {
        return value.isEmpty() ? "~" : HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }
    private static Class<? extends Parser> type(String name) {
        return switch (name) {
            case "bol" -> StartOfLineParser.class;
            case "bof" -> StartOfSourceParser.class;
            case "terminator" -> LineTerminatorParser.class;
            case "eol" -> EndOfLineParser.class;
            default -> throw new AssertionError(name);
        };
    }
    private static String tree(Token token, boolean reduced) {
        String label = token.parser instanceof LineTerminatorParser ? "Line" : token.parser.getClass().getSimpleName();
        String children = (reduced ? token.getAstNodeChildren() : token.getOriginalChildren()).stream()
                .map(child -> tree(child, reduced)).collect(java.util.stream.Collectors.joining(","));
        return label + "/" + token.tokenKind + "@" + token.source.offsetFromRoot().value() + ":"
                + (token.source.offsetFromRoot().value() + token.source.codePointLength().value())
                + "=" + encode(token.source.sourceAsString()) + "[" + children + "]";
    }
    private static String forest(List<Token> tokens, boolean reduced) {
        List<Token> roots = tokens;
        if (reduced) {
            var copies = new TokenList();
            tokens.forEach(token -> copies.add(token.deepCopy()));
            roots = new Committed(copies).getRootToken(true).getAstNodeChildren();
        }
        String result = roots.stream().map(token -> tree(token, reduced)).collect(java.util.stream.Collectors.joining(";"));
        return result.isEmpty() ? "~" : result;
    }
    private static final class State implements TransactionalState {
        int value = 7;
        @Override public Runnable checkpoint() { int before = value; return () -> value = before; }
    }

    @Test public void javaClassicBoundariesMatchIndependentOracleThroughPublicFactories() throws Exception {
        var rows = Files.readAllLines(ROOT.resolve("conformance/line-boundary/cases.tsv")).stream()
                .filter(line -> !line.startsWith("#") && !line.isBlank()).toList();
        assertEquals(1088, rows.size());
        var failures = new ArrayList<String>();
        for (String row : rows) {
            String[] c = row.split("\t", -1);
            assertEquals(17, c.length);
            assertEquals("direct", c[8]);
            Class<? extends Parser> type = type(c[2]);
            Supplier<Parser> supplier = () -> Parser.newInstance(type);
            // Class/Supplier/static factory の4経路。同じ parser を全モードで再利用する。
            List<Parser> parsers = List.of(Parser.get(type), Parser.getSupplier(type).get(),
                    Parser.get(supplier), Parser.newInstance(type));
            assertSame(parsers.get(0), parsers.get(1));
            assertSame(parsers.get(2), Parser.get(supplier));
            assertNotSame(parsers.get(0), parsers.get(3));
            for (Parser parser : parsers) for (Memoization memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES))
                for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
                    String label = c[0] + "/" + memo + "/" + diagnostics;
                    try (var context = ParseContext.withOptions(StringSource.createRootSource(decode(c[1])),
                            ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                        context.getCurrent().setResetMatchedWithConsumed(false);
                        String input = decode(c[1]);
                        int consumed = Integer.parseInt(c[5]), matched = Integer.parseInt(c[6]);
                        int split = input.offsetByCodePoints(0, consumed), end = input.offsetByCodePoints(0, matched);
                        if (consumed > 0) assertTrue(new WordParser(input.substring(0, split)).parse(context).isSucceeded());
                        if (matched > consumed) assertTrue(new WordParser(input.substring(split, end)).parse(context, TokenKind.matchOnly, false).isSucceeded());
                        int prefixTokens = context.getCurrent().getTokens().size();
                        context.getCurrent().setResetMatchedWithConsumed(Boolean.parseBoolean(c[7]));
                        var state = new State(); context.registerTransactionalState(state);
                        ScopeStore.declare(context, "before", 0);
                        var metadata = new PortableTokenMetadata(context);
                        var marker = metadata.register(new Token(TokenKind.virtualTokenConsumed, StringSource.createDetachedSource("seed"), parser));
                        assertTrue(metadata.putExtra(marker, "value", "before"));
                        int depth = ScopeStore.currentScopeDepth(context);
                        // 入れ子の owner が失敗して同じ instance を再試行しても同じ oracle。
                        for (int attempt = 0; attempt < 2; attempt++) {
                            context.beginPreservingMatched(parser);
                            context.beginPreservingMatched(parser);
                            context.getCurrent().setResetMatchedWithConsumed(Boolean.parseBoolean(c[7]));
                            state.value = 9;
                            ScopeStore.enter(context); ScopeStore.declare(context, "trial", 0);
                            metadata.putExtra(marker, "value", "trial");
                            Parsed parsed = parser.parse(context, TokenKind.valueOf(c[3]), Boolean.parseBoolean(c[4]));
                            List<Token> tokens = context.getCurrent().getTokens();
                            var failure = context.getParseFailureDiagnostics();
                            List<String> actual = List.of(Boolean.toString(parsed.isSucceeded()),
                                    Integer.toString(context.getConsumedPosition().value()), Integer.toString(context.getMatchedPosition().value()),
                                    Integer.toString(failure.getFarthestOffset()), Boolean.toString(!failure.getExpectedTokens().isEmpty()),
                                    forest(tokens, false), forest(tokens, true));
                            var expected = new ArrayList<>(List.of(c).subList(9, 16));
                            // 末尾列は Java の既知欠陥 #861（FIRST 除外が reset 前の cursor を読む）の現状値。
                            // Rust はこの列を使わない。#861 の修正時に列を除き、主の期待値へ戻す。
                            if (diagnostics == ParseOptions.Diagnostics.DETAILED_ON_FAILURE) { expected.set(3, "0"); expected.set(4, "false"); if (!c[16].equals("~")) expected = new ArrayList<>(List.of(c[16].split("\\|", -1))); }
                            if (!expected.equals(actual)) failures.add(label + "\nexpected " + expected + "\nactual   " + actual);
                            assertEquals(label, 9, state.value);
                            assertTrue(context.captured("site").isEmpty());
                            assertEquals("trial", metadata.info(marker).orElseThrow().extra().get("value"));
                            if (parsed.isSucceeded() && parser instanceof LineTerminatorParser line) {
                                Token token = tokens.get(0);
                                assertEquals(c[14].contains("EndOfSourceParser"), line.isEndOfSource(token));
                                assertTrue(Parser.isTokenParsedBySpecifiedParser(token, type));
                                Parser.checkTokenParsedBySpecifiedParser(token, type);
                            }
                            context.capture("site", new CapturedValue("trial", CapturedValue.Provenance.GENERATED, 0, 0));
                            context.rollback(parser); context.rollback(parser);
                            assertEquals(label, consumed, context.getConsumedPosition().value());
                            assertEquals(label, matched, context.getMatchedPosition().value());
                            assertEquals(label, prefixTokens, context.getCurrent().getTokens().size());
                            assertEquals(Boolean.parseBoolean(c[7]), context.getCurrent().isResetMatchedWithConsumed());
                            assertEquals(label, depth, ScopeStore.currentScopeDepth(context));
                            assertTrue(context.captured("site").isEmpty());
                            assertEquals("before", metadata.info(marker).orElseThrow().extra().get("value"));
                            assertEquals(label, 7, state.value);
                            assertEquals(label, 1, ScopeStore.getAllDeclarations(context).size());
                            assertEquals(label, "before", ScopeStore.getAllDeclarations(context).get(0).name());
                            assertEquals(label, 1, context.getTokenStack().size());
                        }
                    }
                }
        }
        Files.write(Path.of("target/line-boundary-failures.txt"), failures);
        assertTrue(failures.stream().limit(30).collect(java.util.stream.Collectors.joining("\n")), failures.isEmpty());
    }

    @Test public void choiceLookaheadAndOuterFailureRetryReuseTheSameParser() throws Exception {
        for (String row : Files.readAllLines(ROOT.resolve("conformance/line-boundary/transactions.tsv"))) {
            if (row.startsWith("#") || row.isBlank()) continue;
            String[] c = row.split("\t"); String input = decode(c[1]); int start = Integer.parseInt(c[2]);
            Parser subject = Parser.newInstance(type(c[0]));
            Parser losing = new Chain(subject, new WordParser("\0"));
            Parser expression = switch (c[3]) {
                case "choice" -> new Choice(losing, subject);
                case "ahead" -> new Chain(new PegLookahead(subject, true), subject);
                case "nested" -> new Chain(subject);
                case "retry" -> subject;
                default -> throw new AssertionError(c[3]);
            };
            for (Memoization memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES))
                for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values())
                    try (var context = ParseContext.withOptions(StringSource.createRootSource(input),
                            ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                        if (start > 0) assertTrue(new WordParser(input.substring(0, input.offsetByCodePoints(0, start))).parse(context).isSucceeded());
                        int before = context.getCurrent().getTokens().size();
                        if (c[3].equals("retry")) {
                            assertTrue(losing.parse(context).isFailed());
                            assertEquals(start, context.getConsumedPosition().value());
                            assertEquals(start, context.getMatchedPosition().value());
                            assertEquals(before, context.getCurrent().getTokens().size());
                        }
                        Parsed result = expression.parse(context);
                        var nodes = context.getCurrent().getTokens().subList(before, context.getCurrent().getTokens().size());
                        var failure = context.getParseFailureDiagnostics();
                        assertEquals(row, Boolean.parseBoolean(c[4]), result.isSucceeded());
                        assertEquals(row, Integer.parseInt(c[5]), context.getConsumedPosition().value());
                        assertEquals(row, Integer.parseInt(c[5]), context.getMatchedPosition().value());
                        assertEquals(row, diagnostics == ParseOptions.Diagnostics.DETAILED_ON_FAILURE ? 0 : Integer.parseInt(c[6]), failure.getFarthestOffset());
                        assertEquals(row, diagnostics != ParseOptions.Diagnostics.DETAILED_ON_FAILURE && Boolean.parseBoolean(c[7]), !failure.getExpectedTokens().isEmpty());
                        assertEquals(row, c[8], forest(nodes, false));
                        assertEquals(row, c[9], forest(nodes, true));
                        assertEquals(1, context.getTokenStack().size());
                    }
        }
    }

    @Test public void lexicalBolBofEolUseTheSameIndependentCodePointBoundaries() throws Exception {
        for (String row : Files.readAllLines(ROOT.resolve("conformance/line-boundary/lexical.tsv"))) {
            if (row.startsWith("#") || row.isBlank()) continue;
            String[] c = row.split("\t"); String input = decode(c[0]);
            for (int column = 1; column <= 3; column++) {
                var op = org.unlaxer.dsl.runtime.LexicalExpression.Op.valueOf(List.of("BOL", "BOF", "EOL").get(column - 1));
                Parser parser = new org.unlaxer.dsl.runtime.LexicalTokenParser("boundary",
                        org.unlaxer.dsl.runtime.LexicalExpression.leaf(op, ""));
                var expected = java.util.Arrays.stream(c[column].split(",")).map(Integer::valueOf).toList();
                for (int position = 0; position <= input.codePointCount(0, input.length()); position++)
                    for (Memoization memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES))
                        for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values())
                            try (var context = ParseContext.withOptions(StringSource.createRootSource(input),
                                    ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                                context.consume(new CodePointLength(position));
                                Parsed result = parser.parse(context);
                                assertEquals(op + "@" + position, expected.contains(position), result.isSucceeded());
                                assertEquals(position, context.getConsumedPosition().value());
                                assertEquals(position, context.getMatchedPosition().value());
                                assertEquals(result.isSucceeded() ? 1 : 0, context.getCurrent().getTokens().size());
                                if (result.isSucceeded()) {
                                    assertEquals(position, result.getRootToken().source.offsetFromRoot().value());
                                    assertEquals(0, result.getRootToken().source.codePointLength().value());
                                }
                            }
            }
        }
    }

    private static int bodyFrames(Token token) {
        return (token.parser.getClass() == Chain.class ? 1 : 0)
                + token.getOriginalChildren().stream().mapToInt(LineBoundaryConformanceTest::bodyFrames).sum();
    }

    @Test public void lineCompositionCandidatesExposeCstAndMatchOnlyGaps() throws Exception {
        var rows = Files.readAllLines(ROOT.resolve("conformance/line-boundary/compositions.tsv"));
        for (String row : rows) {
            if (row.startsWith("#") || row.isBlank()) continue;
            String[] c = row.split("\t");
            Parser parser = c[0].equals("empty") ? new EmptyLineParser() : new WildCardLineParser();
            for (Memoization memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES))
                for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values())
                    try (var context = ParseContext.withOptions(StringSource.createRootSource(decode(c[1])),
                            ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                        Parsed parsed = parser.parse(context);
                        assertEquals(row, Boolean.parseBoolean(c[2]), parsed.isSucceeded());
                        assertEquals(row, Integer.parseInt(c[3]), context.getConsumedPosition().value());
                        assertEquals(row, Integer.parseInt(c[4]), context.getMatchedPosition().value());
                        assertEquals(row, Integer.parseInt(c[5]), context.getCurrent().getTokens().stream()
                                .mapToInt(LineBoundaryConformanceTest::bodyFrames).sum());
                        assertEquals(1, context.getTokenStack().size());
                    }
        }
    }

    @Test public void repeatChoiceCannotReplaceTheTerminatorStopInMatchOnlyMode() throws Exception {
        for (String row : Files.readAllLines(ROOT.resolve("conformance/line-boundary/repeat-choice.tsv"))) {
            if (row.startsWith("#") || row.isBlank()) continue;
            String[] c = row.split("\t");
            for (Memoization memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES))
                for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values())
                    try (var context = ParseContext.withOptions(StringSource.createRootSource(decode(c[1])),
                            ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                        Parser parser = c[0].equals("empty") ? new EmptyLineParser() : new WildCardLineParser();
                        Parsed result = parser.parse(context, TokenKind.matchOnly, false);
                        assertEquals(row, Boolean.parseBoolean(c[2]), result.isSucceeded());
                        assertEquals(row, Integer.parseInt(c[3]), context.getConsumedPosition().value());
                        assertEquals(row, Integer.parseInt(c[4]), context.getMatchedPosition().value());
                    }
        }
    }

    /** 既存 Repeat による最も近い候補との差を、Java 側の観測として固定する（#846 依存の根拠）。 */
    @Test public void charClassRepeatMatchesConsumedButNotVirtualTerminatorCommit() throws Exception {
        int rows = 0;
        for (String row : Files.readAllLines(ROOT.resolve("conformance/line-boundary/char-class.tsv"))) {
            if (row.startsWith("#") || row.isBlank()) continue;
            String[] c = row.split("\t", -1);
            assertEquals(row, 12, c.length);
            rows++;
            for (Memoization memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES))
                for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values())
                    try (var context = ParseContext.withOptions(StringSource.createRootSource(decode(c[2])),
                            ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                        Parser parser = c[0].equals("empty") ? new EmptyLineParser() : new WildCardLineParser();
                        Parsed result = parser.parse(context, TokenKind.valueOf(c[1]), false);
                        assertEquals(row, Boolean.parseBoolean(c[3]), result.isSucceeded());
                        assertEquals(row, Integer.parseInt(c[4]), context.getConsumedPosition().value());
                        assertEquals(row, Integer.parseInt(c[5]), context.getMatchedPosition().value());
                        assertEquals(row, diagnostics == ParseOptions.Diagnostics.DETAILED_ON_FAILURE ? 0 : Integer.parseInt(c[6]),
                                context.getParseFailureDiagnostics().getFarthestOffset());
                        if (!c[11].equals("-")) assertEquals(row, c[11], forest(context.getCurrent().getTokens(), false));
                        assertEquals(1, context.getTokenStack().size());
                    }
        }
        assertEquals(10, rows);
    }

    @Test public void rustPublicApiExecutesTheSameIndependentOracle() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] LineBoundaryConformanceTest requires -DrustConformance=true and Rust 1.85.0");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        String rustc = run(List.of("rustup", "which", "rustc", "--toolchain", "1.85.0")).trim();
        Path directory = temporary.newFolder().toPath(), runtime = directory.resolve("libunlaxer_runtime.rlib");
        run(List.of(rustc, "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
                ROOT.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()));
        Path executable = directory.resolve("line-boundary");
        run(List.of(rustc, "--edition=2021", "--extern", "unlaxer_runtime=" + runtime,
                ROOT.resolve("conformance/line-boundary/probe.rs").toString(), "-o", executable.toString()));
        assertTrue(run(List.of(executable.toString())).contains("line-boundary observations:"));
    }
    private String run(List<String> command) throws Exception {
        Path log = temporary.newFile().toPath();
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("timeout " + command); }
        String output = Files.readString(log);
        assertEquals(command + "\n" + output, 0, process.exitValue());
        return output;
    }
}
