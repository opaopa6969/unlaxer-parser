package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.dsl.bootstrap.SpecCorpusExecutions;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.codegen.rust.RustGrammarLowering;
import org.unlaxer.parser.Parser;

/** 元の corpus・仕様期待値・host 別の実行記録を同時に検査する (#784)。 */
public class SpecCorpusExecutionConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Path ROOT = SpecCorpusExecutions.ROOT;

    private static void requireRust() {
        if (!Boolean.getBoolean("rustConformance")) System.out.println(
            "[assumption] SpecCorpusExecutionConformanceTest requires -DrustConformance=true and cargo/rustc");
        assumeTrue(Boolean.getBoolean("rustConformance"));
    }

    @Test public void validationCorpusRunsOnEveryHost() throws Exception {
        requireRust();
        declarationDiagnosticPriority();
        Path report = Path.of("target/spec-corpus-validate.json");
        Files.deleteIfExists(report);
        var receipts = new JsonArray();
        for (var row : SpecCorpusExecutions.rows("validate")) {
            var original = SpecCorpusExecutions.original(row);
            String id = row.get("id").getAsString();
            String code = original.getAsJsonObject("expectation").get("code").getAsString();
            var grammar = UBNFMapper.parse(original.get("input").getAsString()).grammars().get(0);
            var issues = GrammarValidator.validate(grammar);
            var selected = issues.stream().filter(issue -> issue.code().equals(code)).toList();
            assertFalse(id + ": 独立の仕様診断 " + code + " が必要: " + issues, selected.isEmpty());
            assertTrue(id, selected.stream().allMatch(issue -> issue.severity().equals("ERROR")));
            if (row.has("cp")) {
                String cp = cpSuffix(row);
                assertTrue(id, selected.stream().allMatch(issue -> issue.message().endsWith(cp)));
            }
            var validatorObserved = new JsonArray();
            for (var issue : issues) {
                var observedIssue = new JsonObject();
                observedIssue.addProperty("code", issue.code());
                observedIssue.addProperty("severity", issue.severity());
                observedIssue.addProperty("message", issue.message());
                validatorObserved.add(observedIssue);
            }
            record(receipts, "java-validate", row, original, 1, validatorObserved);
            int checks = 0;
            var hostObserved = new JsonArray();
            for (String message : List.of(
                    assertThrows(IllegalArgumentException.class, () -> RustGrammarLowering.lower(grammar)).getMessage(),
                    assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(grammar)).getMessage())) {
                hostObserved.add(message);
                if (row.has("difference")) {
                    var difference = row.getAsJsonObject("difference");
                    assertTrue(id, difference.get("issue").getAsInt() > 0);
                    assertEquals(id + ": 既知差が変化した", difference.get("java-host").getAsString(), message);
                    assertFalse(id + ": 解消した既知差を削除する", message.startsWith(code));
                } else assertEquals(id, code, message.split(":", 2)[0]);
                if (row.has("cp")) assertTrue(id + ": " + message, message.endsWith(cpSuffix(row)));
                checks++;
            }
            assertEquals(id, 2, checks);
            record(receipts, "java-host-validate", row, original, checks, hostObserved);
            if (!row.has("cp")) {
                String source = original.get("input").getAsString();
                var rooted = UBNFMapper.parse(source.substring(0, source.length() - 1)
                    + " @root @mapping(Entry) Entry ::= 'z'; }").grammars().get(0);
                for (String message : List.of(
                        assertThrows(IllegalArgumentException.class, () -> RustGrammarLowering.lower(rooted)).getMessage(),
                        assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(rooted)).getMessage()))
                    assertEquals(id + " / root: " + message, code, message.split(":", 2)[0]);
            }
        }
        receipts.addAll(nativeReceipts("validate"));
        verifyAndWrite("validate", receipts, report);
    }

    private void declarationDiagnosticPriority() throws Exception {
        var cases = JsonParser.parseString(Files.readString(ROOT.resolve(
            "spec-corpus/execution/diagnostic-priority.json"))).getAsJsonArray();
        for (var raw : cases) {
            var fixture = raw.getAsJsonObject();
            String source = fixture.get("source").getAsString();
            String code = fixture.get("code").getAsString();
            for (boolean root : List.of(false, true)) {
                String input = root ? source.substring(0, source.length() - 1)
                    + " @root @mapping(Entry) Entry ::= 'z'; }" : source;
                if (root && fixture.has("rootRule")) {
                    String declaration = fixture.get("rootRule").getAsString() + " ::=";
                    input = source.replace(declaration, "@root " + declaration);
                }
                var grammar = UBNFMapper.parse(input).grammars().get(0);
                // Independent expected code, and the actual Java validator's first error.
                assertEquals(fixture.toString(), code, GrammarValidator.validate(grammar).stream()
                    .filter(issue -> issue.severity().equals("ERROR")).findFirst().orElseThrow().code());
                for (String message : List.of(
                        assertThrows(IllegalArgumentException.class, () -> RustGrammarLowering.lower(grammar)).getMessage(),
                        assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(grammar)).getMessage())) {
                    assertEquals(fixture + " / root=" + root + ": " + message, code, message.split(":", 2)[0]);
                    if (fixture.has("detail")) assertEquals(fixture.toString(),
                        code + ": Rust subset: " + fixture.get("detail").getAsString(), message);
                }
            }
        }
        var noRoot = UBNFMapper.parse("grammar G { @mapping(X) R ::= 'x'; }").grammars().get(0);
        assertEquals("Rust subset: exactly one @root is required",
            assertThrows(IllegalArgumentException.class, () -> RustGrammarLowering.lower(noRoot)).getMessage());
        assertFalse(new RustBackend().generate(UBNFMapper.parse(
            "grammar G { @root @mapping(X) R ::= 'x'; }").grammars().get(0)).isEmpty());
        var unmappedRoot = UBNFMapper.parse("grammar G { @root @leftAssoc @precedence(level=1) R ::= 'x' @left { '+' @op 'y' @right }; }").grammars().get(0);
        assertEquals("E-ASSOC-NO-MAPPING: Rust subset: root must resolve to exactly one AST node",
            assertThrows(IllegalArgumentException.class, () -> RustGrammarLowering.lower(unmappedRoot)).getMessage());
        var nodeRoot = UBNFMapper.parse("grammar G { @root @leftAssoc @precedence(level=1) R ::= Base @left { '+' @op 'y' @right }; @mapping(Base) Base ::= 'x'; }").grammars().get(0);
        assertEquals("E-ASSOC-NO-MAPPING: Rust subset: @leftAssoc requires left { op right } with scalar operands and params=[left, op, right] on R",
            assertThrows(IllegalArgumentException.class, () -> RustGrammarLowering.lower(nodeRoot)).getMessage());
        // Keep qualified-reference explanations, with the Java code first (#843).
        var qualified = UBNFMapper.parse("grammar G { @root @mapping(X) R ::= 'x' ns.Missing; }").grammars().get(0);
        assertEquals("E-RULE-UNDEFINED: Rust subset: qualified rule reference",
            assertThrows(IllegalArgumentException.class, () -> RustGrammarLowering.lower(qualified)).getMessage());
        var qualifiedNoRoot = UBNFMapper.parse("grammar G { R ::= ns.Missing; }").grammars().get(0);
        assertEquals("E-RULE-UNDEFINED: Rust subset: qualified rule reference",
            assertThrows(IllegalArgumentException.class, () -> RustGrammarLowering.lower(qualifiedNoRoot)).getMessage());
    }

    private static String cpSuffix(JsonObject row) {
        var cp = row.getAsJsonArray("cp");
        return "at CP " + cp.get(0).getAsInt() + ".." + cp.get(1).getAsInt();
    }

    @Test public void runtimeCorpusRunsOnEveryHost() throws Exception {
        requireRust();
        Path report = Path.of("target/spec-corpus-runtime.json");
        Files.deleteIfExists(report);
        var receipts = new JsonArray();
        Path runtime = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            ROOT.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()));
        for (var row : SpecCorpusExecutions.rows("runtime")) {
            var original = SpecCorpusExecutions.original(row);
            String id = row.get("id").getAsString();
            String source = original.get("input").getAsString();
            var grammar = UBNFMapper.parse(source).grammars().get(0);
            // Java は原文そのものの named entry を実行する。Rust の @root 追加と比較する。
            var generatedParser = new ParserGenerator().generate(grammar);
            int checks = 0;
            var javaObserved = new JsonArray();
            try (var loader = compileJava(generatedParser)) {
                Class<?> entry = loader.loadClass(generatedParser.packageName() + "." + generatedParser.className()
                    + "$" + row.get("entry").getAsString() + "Parser");
                Parser parser = (Parser) entry.getConstructor().newInstance();
                for (var raw : row.getAsJsonArray("samples")) {
                    var sample = raw.getAsJsonObject();
                    try (var context = ParseContext.withOptions(StringSource.createRootSource(sample.get("input").getAsString()),
                            ParseOptions.defaults().withDiagnostics(ParseOptions.Diagnostics.DETAILED))) {
                        var prefix = new JsonArray();
                        prefix.add(parser.parse(context).isSucceeded());
                        prefix.add(context.getConsumedPosition().value());
                        prefix.add(context.getMatchedPosition().value());
                        var observedSample = new JsonObject();
                        observedSample.add("input", sample.get("input"));
                        observedSample.add("prefix", prefix);
                        var failure = context.getParseFailureDiagnostics();
                        if (prefix.get(0).getAsBoolean() || failure == null) observedSample.add("diagnostic", JsonNull.INSTANCE);
                        else {
                            var observedDiagnostic = new JsonObject();
                            var expectedTokens = new JsonArray();
                            failure.getExpectedTokens().stream().sorted().forEach(expectedTokens::add);
                            observedDiagnostic.add("expected", expectedTokens);
                            observedDiagnostic.addProperty("offset", failure.getFarthestOffset());
                            observedSample.add("diagnostic", observedDiagnostic);
                        }
                        javaObserved.add(observedSample);
                        assertEquals(id + ": " + sample, sample.get("prefix"), prefix);
                        if (sample.has("hint")) {
                            var diagnostic = context.getParseFailureDiagnostics();
                            assertTrue(id + ": " + diagnostic.getExpectedTokens(),
                                diagnostic.getExpectedTokens().contains(sample.get("hint").getAsString()));
                            assertEquals(id, sample.get("hint_cp").getAsInt(), diagnostic.getFarthestOffset());
                        }
                    }
                    checks++;
                }
            }
            assertEquals(id, row.getAsJsonArray("samples").size(), checks);
            record(receipts, "java-runtime", row, original, checks, javaObserved);
            String needle = " " + row.get("entry").getAsString() + " ::=";
            assertEquals(id, 1, source.split(java.util.regex.Pattern.quote(needle), -1).length - 1);
            String rootAnnotation = row.get("add_root").getAsBoolean() ? " @root" : "";
            source = source.replace(needle, rootAnnotation + " @mapping(SpecResult)" + needle);
            Path directory = temporary.newFolder().toPath();
            Path generated = Files.createDirectory(directory.resolve("generated"));
            for (var file : new RustBackend().generate(UBNFMapper.parse(source).grammars().get(0)))
                Files.writeString(generated.resolve(file.relativePath()), file.content());
            Files.copy(ROOT.resolve("spec-corpus/execution/probe.rs"), directory.resolve("main.rs"));
            Path binary = directory.resolve("probe");
            run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + runtime,
                directory.resolve("main.rs").toString(), "-o", binary.toString()));
            checks = 0;
            var hostObserved = new JsonArray();
            for (var raw : row.getAsJsonArray("samples")) {
                var sample = raw.getAsJsonObject();
                var actual = JsonParser.parseString(run(List.of(binary.toString(), sample.get("input").getAsString()))).getAsJsonObject();
                var observedSample = new JsonObject();
                observedSample.add("input", sample.get("input"));
                observedSample.add("prefix", actual.get("prefix"));
                if (actual.get("diagnostic").isJsonNull()) observedSample.add("diagnostic", JsonNull.INSTANCE);
                else {
                    var observedDiagnostic = new JsonObject();
                    observedDiagnostic.add("expected", actual.getAsJsonObject("diagnostic").get("expected"));
                    observedDiagnostic.add("offset", actual.getAsJsonObject("diagnostic").get("offset"));
                    observedSample.add("diagnostic", observedDiagnostic);
                }
                hostObserved.add(observedSample);
                assertEquals(id + ": " + sample, sample.get("prefix"), actual.get("prefix"));
                if (sample.has("hint")) {
                    var diagnostic = actual.getAsJsonObject("diagnostic");
                    assertTrue(id + ": " + actual, diagnostic.getAsJsonArray("expected").contains(sample.get("hint")));
                    assertEquals(id, sample.get("hint_cp"), diagnostic.get("offset"));
                }
                checks++;
            }
            assertEquals(id, row.getAsJsonArray("samples").size(), checks);
            record(receipts, "java-host-runtime", row, original, checks, hostObserved);
        }
        receipts.addAll(nativeReceipts("runtime"));
        verifyAndWrite("runtime", receipts, report);
    }

    private URLClassLoader compileJava(CodeGenerator.GeneratedSource source) throws Exception {
        var unit = new SimpleJavaFileObject(URI.create("string:///" + source.packageName().replace('.', '/')
                + "/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return source.source(); }
        };
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("JDK が必要", compiler);
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            assertTrue(diagnostics.getDiagnostics().toString(), compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()),
                null, List.of(unit)).call());
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private JsonArray nativeReceipts(String scope) throws Exception {
        String output = run(List.of("cargo", "test", "--locked", "--manifest-path", ROOT.resolve("rust/Cargo.toml").toString(),
            "-p", "unlaxer-generator", "--test", "spec_corpus_execution", scope + "_corpus_on_native", "--", "--exact", "--nocapture"));
        var result = new JsonArray();
        for (String line : output.lines().toList())
            if (line.startsWith("SPEC-CORPUS ")) result.add(JsonParser.parseString(line.substring("SPEC-CORPUS ".length())));
        // cargo は存在しない --exact test でも成功するため、出力の実行 ID まで検査する。
        assertEquals(output, SpecCorpusExecutions.rows(scope).size(), result.size());
        return result;
    }
    /** 観測値も記録し、driver 自身の照合とは別に Python 検査が独立期待値で判定し直す。 */
    private static void record(JsonArray receipts, String driver, JsonObject row, JsonObject original, int checks,
            JsonArray observed) {
        var result = new JsonObject();
        result.addProperty("driver", driver);
        result.add("id", row.get("id"));
        result.add("input", original.get("input"));
        result.addProperty("checks", checks);
        result.add("observed", observed);
        receipts.add(result);
    }
    private static void verifyAndWrite(String scope, JsonArray receipts, Path report) throws Exception {
        var expected = new TreeSet<String>();
        for (var row : SpecCorpusExecutions.rows(scope)) for (var driver : row.getAsJsonArray("drivers")) {
            expected.add(driver.getAsString() + ":" + row.get("id").getAsString());
            var destination = SpecCorpusExecutions.manifest().getAsJsonObject("drivers").getAsJsonObject(driver.getAsString());
            if (!driver.getAsString().startsWith("native-")) {
                assertEquals("unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/SpecCorpusExecutionConformanceTest.java", destination.get("path").getAsString());
                assertEquals(scope.equals("validate") ? "validationCorpusRunsOnEveryHost" : "runtimeCorpusRunsOnEveryHost",
                    destination.get("test").getAsString());
            }
        }
        var executed = new TreeSet<String>();
        for (var raw : receipts) {
            var receipt = raw.getAsJsonObject();
            assertTrue("実行記録重複: " + receipt, executed.add(receipt.get("driver").getAsString() + ":" + receipt.get("id").getAsString()));
            var row = SpecCorpusExecutions.rows(scope).stream().filter(r -> r.get("id").equals(receipt.get("id"))).findFirst().orElseThrow();
            assertEquals(receipt.toString(), SpecCorpusExecutions.original(row).get("input"), receipt.get("input"));
            int checks = scope.equals("runtime") ? row.getAsJsonArray("samples").size()
                : receipt.get("driver").getAsString().equals("java-validate") ? 1 : 2;
            assertEquals(receipt.toString(), checks, receipt.get("checks").getAsInt());
        }
        assertEquals("未実行 corpus / driver", expected, executed);
        Files.writeString(report, new GsonBuilder().setPrettyPrinting().create().toJson(receipts) + "\n");
    }
    private String run(List<String> command) throws Exception {
        Path log = temporary.newFile().toPath();
        var process = new ProcessBuilder(command).directory(ROOT.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("timeout: " + command); }
        String output = Files.readString(log);
        assertEquals(command + "\n" + output, 0, process.exitValue());
        return output;
    }
}
