package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.dsl.PortabilityCheck;
import org.unlaxer.dsl.bootstrap.ParameterizedRules;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.impact.ApiImpact;
import org.unlaxer.parser.Parser;

/**
 * First-order parameterized rules (#701) against {@code spec-corpus/parameterized-rules}.
 *
 * <p>The corpus is the oracle: a hand-written expanded grammar per accepted case, hand-written
 * canonical keys for instance names, positioned rejections, and hand-written accept/reject,
 * code-point spans and AST values per input. Java frontend and generators, the Java-hosted Rust
 * backend and the native Rust generator are each compared with it; the Rust consumers built
 * from both hosts are compiled and run.
 */
public class ParameterizedRuleConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final List<ParseOptions.Diagnostics> MODES = List.of(ParseOptions.Diagnostics.AUTO,
        ParseOptions.Diagnostics.DETAILED, ParseOptions.Diagnostics.DETAILED_ON_FAILURE);

    private static JsonArray cases() throws Exception {
        return JsonParser.parseString(Files.readString(ROOT.resolve("spec-corpus/parameterized-rules/cases.json")))
            .getAsJsonArray();
    }

    private static boolean frontendRejection(JsonObject row) {
        return row.has("error") && row.getAsJsonObject("error").get("stage").getAsString().equals("frontend");
    }

    private static List<CodeGenerator> generators() {
        return List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator(), new EvaluatorGenerator(21));
    }

    @Test public void javaFrontendExpandsToTheHandWrittenGrammarAndKeepsSourceSpans() throws Exception {
        int accepted = 0, rejected = 0, validated = 0;
        for (var raw : cases()) {
            var row = raw.getAsJsonObject();
            String name = row.get("name").getAsString(), source = row.get("grammar").getAsString();
            if (frontendRejection(row)) {
                var expected = row.getAsJsonObject("error");
                var error = assertThrows(name, ParameterizedRules.ExpansionException.class, () -> UBNFMapper.parse(source));
                var snapshotError = assertThrows(name, ParameterizedRules.ExpansionException.class,
                    () -> UBNFMapper.parseWithSource(source));
                assertEquals(name, error.getMessage(), snapshotError.getMessage());
                assertEquals(name, expected.get("message").getAsString(), error.diagnostic());
                assertEquals(name, expected.get("code").getAsString(), error.code());
                assertEquals(name, List.of(expected.get("start").getAsInt(), expected.get("end").getAsInt(),
                        expected.get("line").getAsInt(), expected.get("column").getAsInt()),
                    List.of(error.start(), error.end(), error.line(), error.column()));
                var report = PortabilityCheck.check(source);
                assertFalse(name, report.portable());
                assertEquals(name, "unavailable", report.structure());
                assertEquals(name, List.of(new PortabilityCheck.Diagnostic(error.code(),
                    new UBNFSourceSnapshot.Span(error.start(), error.end()), error.diagnostic())), report.diagnostics());
                rejected++;
                continue;
            }
            var actual = UBNFMapper.parse(source);
            assertEquals(name, UBNFMapper.parse(row.get("expanded").getAsString()), actual);
            var snapshot = UBNFMapper.parseWithSource(source);
            assertEquals(name, actual, snapshot.ast());
            GrammarDecl grammar = snapshot.ast().grammars().get(0);
            for (RuleDecl rule : grammar.rules()) assertTrue(name + "/" + rule.name(), snapshot.spanOf(rule).isPresent());
            for (var item : row.has("spans") ? row.getAsJsonArray("spans") : new JsonArray()) {
                var span = item.getAsJsonObject();
                RuleDecl rule = grammar.rules().stream()
                    .filter(r -> r.name().equals(span.get("rule").getAsString())).findFirst().orElseThrow();
                var range = span.getAsJsonArray("span");
                assertEquals(name, new UBNFSourceSnapshot.Span(range.get(0).getAsInt(), range.get(1).getAsInt()),
                    snapshot.spanOf(rule).orElseThrow());
                assertEquals(name, UBNFSourceSnapshot.Kind.SYNTHETIC, snapshot.originOf(rule).orElseThrow().kind());
            }
            for (var item : row.getAsJsonArray("instances")) {
                String instance = item.getAsJsonObject().get("name").getAsString();
                assertTrue(name + "/" + instance, grammar.rules().stream()
                    .anyMatch(rule -> rule.name().equals(instance) && rule.annotations().isEmpty()));
            }
            if (row.get("status").getAsString().equals("reject")) {
                String code = row.getAsJsonObject("error").get("code").getAsString();
                var issues = GrammarValidator.validateWithoutClassLoading(grammar);
                assertTrue(name + " " + issues, issues.stream().anyMatch(issue -> issue.code().equals(code)));
                for (CodeGenerator generator : generators()) {
                    var error = assertThrows(name, IllegalArgumentException.class, () -> generator.generate(grammar));
                    assertTrue(name + " " + error, error.getMessage().contains(code));
                }
                validated++;
                continue;
            }
            assertTrue(name + " " + GrammarValidator.validateWithoutClassLoading(grammar),
                GrammarValidator.validateWithoutClassLoading(grammar).stream()
                    .noneMatch(issue -> issue.severity().equals("ERROR")));
            assertTrue(name + " " + PortabilityCheck.check(source), PortabilityCheck.check(source).portable());
            for (String target : List.of("java", "rust")) {
                var report = ApiImpact.compare(target, row.get("expanded").getAsString(), source);
                assertTrue(name + " " + report.toJson(), report.ok());
                assertFalse(name + " " + report.toJson(), report.hasChanges());
            }
            accepted++;
        }
        assertEquals(List.of(10, 2, 23), List.of(accepted, validated, rejected));
    }

    /**
     * Chain length times body nesting at the inline nesting limit, in every split, on a
     * thread smaller than the JVM default (review of #708: the product used to overflow).
     */
    @Test public void theInlineNestingLimitBoundsNestedChainsOnASmallStack() throws Exception {
        int limit = ParameterizedRules.MAX_EXPANDED_NESTING;
        var outcome = new java.util.concurrent.atomic.AtomicReference<Object>();
        var thread = new Thread(null, () -> {
            try {
                var results = new ArrayList<Object>();
                for (int groups : new int[] {0, 1, 3, 7, 15, 30, 62, 126}) {
                    int n = (limit - 2) / (1 + groups);
                    results.add(UBNFMapper.parse(chain(n, groups, 0)).grammars().get(0).rules().size() == 2 + n);
                    int levels = 1 + n * (1 + groups) + 2;
                    if (levels > limit) {
                        var error = assertThrows(ParameterizedRules.ExpansionException.class,
                            () -> UBNFMapper.parse(chain(n, groups, 1)));
                        results.add(error.diagnostic().equals("E-PARAM-LIMIT: expansion of rule C" + n + " nests "
                            + levels + " levels; the limit is 128"));
                    }
                }
                outcome.set(results);
            } catch (Throwable error) { outcome.set(error); }
        }, "parameterized-chain", 512 * 1024);
        thread.start();
        thread.join(120_000);
        assertTrue(String.valueOf(outcome.get()), outcome.get() instanceof List<?> list
            && list.size() >= 8 && list.stream().allMatch(Boolean.TRUE::equals));
    }

    private static String chain(int n, int groups, int innermost) {
        StringBuilder source = new StringBuilder("grammar G { Root ::= C0('a'); ");
        for (int k = 0; k < n; k++) source.append("C").append(k).append("(x) ::= ").append("( ".repeat(groups))
            .append("C").append(k + 1).append("(x)").append(" )".repeat(groups)).append(" ; ");
        return source.append("C").append(n).append("(x) ::= ").append("( ".repeat(innermost)).append("x")
            .append(" )".repeat(innermost)).append(" ; }").toString();
    }

    @Test public void grammarsWithoutDefinitionsKeepTheirPreviousParse() throws Exception {
        // Reference followed by a group, as before; a later definition is what reserves NAME(.
        var plain = UBNFMapper.parse("grammar G { Root ::= Foo('a') CAPTUREX('b'); Foo ::= 'c'; CAPTUREX ::= 'd'; }");
        assertEquals(4, ((org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody) plain.grammars().get(0).rules().get(0).body())
            .alternatives().get(0).elements().size());
        var error = assertThrows(IllegalArgumentException.class,
            () -> UBNFMapper.parse("grammar G { Root ::= F ('a'); F (x) ::= x; }"));
        assertFalse(error instanceof ParameterizedRules.ExpansionException);
        // Calls are scoped to the grammar that defines Name(params): B keeps F + group.
        var two = UBNFMapper.parse(Files.readString(
            ROOT.resolve("rust/unlaxer-ubnf/tests/fixtures/positive/parameterized-rules-two-grammars.ubnf")));
        assertEquals(2, two.grammars().get(0).rules().size());
        var elements = ((org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody) two.grammars().get(1).rules().get(0).body())
            .alternatives().get(0).elements();
        assertEquals(new org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement("F"), elements.get(0).element());
        assertTrue(elements.get(1).element() instanceof org.unlaxer.dsl.bootstrap.UBNFAST.GroupElement);
    }

    @Test public void javaGeneratedParsersEqualTheHandExpansionAndMatchExpectedValues() throws Exception {
        var compiler = org.unlaxer.dsl.Java21TestSupport.compiler();
        var report = new ArrayList<>(List.of("fixture\tinput\tmemo\tdiagnostics\tstatus"));
        for (var raw : cases()) {
            var row = raw.getAsJsonObject();
            if (!row.get("status").getAsString().equals("accept")) continue;
            String name = row.get("name").getAsString();
            var grammar = UBNFMapper.parse(row.get("grammar").getAsString()).grammars().get(0);
            var expanded = UBNFMapper.parse(row.get("expanded").getAsString()).grammars().get(0);
            var units = new ArrayList<JavaFileObject>();
            for (CodeGenerator generator : generators()) {
                var source = generator.generate(grammar);
                assertEquals(name + "/" + source.className(), generator.generate(expanded).source(), source.source());
                units.add(new SimpleJavaFileObject(URI.create("string:///paramprobe/" + source.className() + ".java"),
                        JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignore) { return source.source(); }
                });
            }
            Path classes = temporary.newFolder().toPath();
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
                boolean compiled = compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "21", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                    null, units).call();
                assertTrue(name + " " + diagnostics.getDiagnostics(), compiled);
            }
            try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
                var parser = (Parser) loader.loadClass("paramprobe.ParamParsers").getMethod("getRootParser").invoke(null);
                var mapper = loader.loadClass("paramprobe.ParamMapper");
                for (var item : row.getAsJsonArray("inputs")) {
                    var input = item.getAsJsonObject();
                    String text = input.get("input").getAsString();
                    for (var memo : Memoization.values()) for (var mode : MODES) {
                        var actual = new JsonObject();
                        try (var context = ParseContext.withOptions(StringSource.createRootSource(text),
                                ParseOptions.withMemoization(memo).withDiagnostics(mode))) {
                            var parsed = parser.parse(context);
                            if (parsed.isFailed()) {
                                assertEquals(0, context.getConsumedPosition().value());
                                actual.addProperty("status", "syntax");
                                int offset = context.getParseFailureDiagnostics().getFarthestOffset();
                                if (mode == ParseOptions.Diagnostics.DETAILED_ON_FAILURE) {
                                    try (var detailed = ParseContext.withOptions(StringSource.createRootSource(text),
                                            ParseOptions.withMemoization(memo).withDiagnostics(ParseOptions.Diagnostics.DETAILED))) {
                                        assertTrue(parser.parse(detailed).isFailed());
                                        offset = detailed.getParseFailureDiagnostics().getFarthestOffset();
                                    }
                                }
                                actual.addProperty("offset", offset);
                            } else if (context.getConsumedPosition().value() != text.codePointCount(0, text.length())) {
                                actual.addProperty("status", "syntax");
                                actual.addProperty("offset", context.getConsumedPosition().value());
                            } else {
                                var token = context.getCurrent().getTokens().stream()
                                    .filter(candidate -> candidate.parser == parser).findFirst().orElseThrow();
                                Object mapped;
                                try {
                                    mapped = mapper.getMethod("mapParsedTokenWithSourceMap", org.unlaxer.Token.class)
                                        .invoke(null, token);
                                } catch (InvocationTargetException failure) {
                                    throw new AssertionError(name + "/" + text, failure.getCause());
                                }
                                actual.addProperty("status", "ok");
                                actual.add("value", observe(mapped.getClass().getMethod("ast").invoke(mapped), mapped));
                            }
                        }
                        assertEquals(name + "/" + text + "/" + memo + "/" + mode, input.get("expected"), actual);
                        report.add(name + "\t" + input.get("input") + "\t" + memo + "\t" + mode + "\t" + actual.get("status"));
                    }
                }
            }
        }
        Files.write(Path.of("target/parameterized-rules-java.tsv"), report);
    }

    private JsonElement observe(Object value, Object mapped) throws Exception {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Optional<?> item) return item.isPresent() ? observe(item.get(), mapped) : JsonNull.INSTANCE;
        if (value instanceof List<?> values) {
            var array = new JsonArray();
            for (Object item : values) array.add(observe(item, mapped));
            return array;
        }
        int[] offsets = (int[]) ((Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class)
            .invoke(mapped, value)).orElseThrow();
        var span = new JsonArray();
        span.add(offsets[0]);
        span.add(offsets[1]);
        var result = new JsonObject();
        result.addProperty("type", value.getClass().getName().split("ParamAST\\$")[1].replace("$", "__"));
        result.add("span", span);
        var fields = new JsonObject();
        for (var field : value.getClass().getRecordComponents())
            fields.add(field.getName(), observe(field.getAccessor().invoke(value), mapped));
        result.add("fields", fields);
        return result;
    }

    private static final String PROBE = String.join("\n",
        "#![allow(dead_code, unused_imports)]",
        "mod generated;",
        "use std::io::BufRead;",
        "use unlaxer_runtime::{Diagnostics, Memoization, ParseOptions};",
        "fn main() {",
        "    for line in std::io::stdin().lock().lines() {",
        "        let hex = line.unwrap();",
        "        let input = String::from_utf8((0..hex.len()).step_by(2).map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap()).collect()).unwrap();",
        "        for memo in [Memoization::Off, Memoization::SafeFailures] {",
        "            for diagnostics in [Diagnostics::Auto, Diagnostics::Detailed, Diagnostics::DetailedOnFailure] {",
        "                let result = match generated::parser::parse_tree_with_options(&input, ParseOptions::with_memoization(memo).with_diagnostics(diagnostics)) {",
        "                    Ok(tree) => {",
        "                        assert_eq!(tree.nodes[tree.root].span, unlaxer_runtime::Span { start: 0, end: input.chars().count() });",
        "                        let ast = generated::mapper::map(&tree).unwrap();",
        "                        serde_json::json!({\"status\": \"ok\", \"value\": serde_json::from_str::<serde_json::Value>(&ast.canonical_json()).unwrap()})",
        "                    }",
        "                    Err(error) => serde_json::json!({\"status\": \"syntax\", \"offset\": error.offset}),",
        "                };",
        "                println!(\"{}\", result);",
        "            }",
        "        }",
        "    }",
        "}",
        "");

    @Test public void bothRustHostsEmitTheHandExpansionAndRunExpectedValues() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] ParameterizedRuleConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        run(List.of("cargo", "build", "--locked", "--quiet", "--manifest-path", ROOT.resolve("rust/Cargo.toml").toString(),
            "-p", "unlaxer-generator"), "", 0);
        Path unlaxer = ROOT.resolve("rust/target/debug/unlaxer");
        var report = new ArrayList<>(List.of("fixture\thost\tinputs\tstatus"));
        for (var raw : cases()) {
            var row = raw.getAsJsonObject();
            String name = row.get("name").getAsString(), source = row.get("grammar").getAsString();
            Path dir = temporary.newFolder().toPath(), grammarFile = dir.resolve("param.ubnf");
            Files.writeString(grammarFile, source);
            if (frontendRejection(row)) {
                var expected = row.getAsJsonObject("error");
                String output = run(List.of(unlaxer.toString(), "generate", "--grammar", grammarFile.toString(),
                    "--output", dir.resolve("out").toString()), "", -1);
                String diagnostic = expected.get("line").getAsInt() + ":" + expected.get("column").getAsInt() + ": "
                    + expected.get("message").getAsString();
                assertTrue(name + " " + output, output.contains(diagnostic));
                assertFalse(name, Files.exists(dir.resolve("out/parser.rs")));
                var check = JsonParser.parseString(run(List.of(unlaxer.toString(), "check", "--target", "rust",
                    "--grammar", grammarFile.toString(), "--format", "json"), "", 3).trim()).getAsJsonObject();
                assertEquals(name, "unavailable", check.get("structure").getAsString());
                var only = check.getAsJsonArray("diagnostics").get(0).getAsJsonObject();
                assertEquals(name, expected.get("code").getAsString(), only.get("code").getAsString());
                assertEquals(name, expected.get("message").getAsString(), only.get("subject").getAsString());
                assertEquals(name, expected.get("start").getAsInt(), only.getAsJsonObject("span").get("start").getAsInt());
                assertEquals(name, expected.get("end").getAsInt(), only.getAsJsonObject("span").get("end").getAsInt());
                report.add(name + "\tnative\t0\trejected");
                continue;
            }
            var grammar = UBNFMapper.parse(source).grammars().get(0);
            Path expandedFile = dir.resolve("expanded.ubnf");
            Files.writeString(expandedFile, row.get("expanded").getAsString());
            if (row.get("status").getAsString().equals("reject")) {
                String code = row.getAsJsonObject("error").get("code").getAsString();
                for (Path file : List.of(grammarFile, expandedFile)) {
                    String output = run(List.of(unlaxer.toString(), "generate", "--grammar", file.toString(),
                        "--output", dir.resolve("out").toString()), "", -1);
                    assertTrue(name + " " + output, output.contains(code));
                }
                assertTrue(name, assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(grammar))
                    .getMessage().contains(code));
                report.add(name + "\tboth\t0\trejected");
                continue;
            }
            run(List.of(unlaxer.toString(), "generate", "--grammar", grammarFile.toString(), "--output",
                dir.resolve("native").toString()), "", 0);
            run(List.of(unlaxer.toString(), "generate", "--grammar", expandedFile.toString(), "--output",
                dir.resolve("native-expanded").toString()), "", 0);
            Path java = Files.createDirectories(dir.resolve("java"));
            var files = new RustBackend().generate(grammar);
            assertEquals(5, files.size());
            for (var file : files) {
                String nativeContent = Files.readString(dir.resolve("native").resolve(file.relativePath()));
                assertEquals(name + "/" + file.relativePath(), file.content(), nativeContent);
                assertEquals(name + "/" + file.relativePath(), nativeContent,
                    Files.readString(dir.resolve("native-expanded").resolve(file.relativePath())));
                Files.writeString(java.resolve(file.relativePath()), file.content());
            }
            var impact = JsonParser.parseString(run(List.of(unlaxer.toString(), "impact", "--target", "rust", "--before",
                expandedFile.toString(), "--after", grammarFile.toString(), "--format", "json"), "", 0).trim()).getAsJsonObject();
            assertTrue(name + " " + impact, impact.get("ok").getAsBoolean());
            assertFalse(name + " " + impact, impact.get("hasChanges").getAsBoolean());
            Files.writeString(dir.resolve("main.rs"), PROBE.replace("mod generated;", "#[path = \"java/mod.rs\"] mod generated;"));
            Files.writeString(dir.resolve("main-native.rs"), PROBE.replace("mod generated;", "#[path = \"native/mod.rs\"] mod generated;"));
            Files.writeString(dir.resolve("Cargo.toml"), "[package]\nname=\"parameterized-rules-probe\"\nversion=\"0.1.0\"\n"
                + "edition=\"2021\"\n[[bin]]\nname=\"probe\"\npath=\"main.rs\"\n[[bin]]\nname=\"probe-native\"\n"
                + "path=\"main-native.rs\"\n[dependencies]\nunlaxer-runtime={path=\"" + ROOT.resolve("rust/unlaxer-runtime")
                + "\"}\nserde_json=\"1\"\n");
            Path target = ROOT.resolve("rust/target/parameterized-rules-probe");
            run(List.of("cargo", "build", "--quiet", "--offline", "--manifest-path", dir.resolve("Cargo.toml").toString(),
                "--target-dir", target.toString()), "", 0);
            var inputs = row.getAsJsonArray("inputs");
            StringBuilder stdin = new StringBuilder();
            for (var item : inputs)
                stdin.append(HexFormat.of().formatHex(item.getAsJsonObject().get("input").getAsString()
                    .getBytes(StandardCharsets.UTF_8))).append('\n');
            for (String binary : List.of("probe", "probe-native")) {
                var lines = run(List.of(target.resolve("debug/" + binary).toString()), stdin.toString(), 0).lines().toList();
                assertEquals(name, inputs.size() * 6, lines.size());
                for (int i = 0; i < lines.size(); i++) {
                    var input = inputs.get(i / 6).getAsJsonObject();
                    assertEquals(name + "/" + binary + "/" + input.get("input") + "/" + (i % 6), input.get("expected"),
                        JsonParser.parseString(lines.get(i)));
                }
                report.add(name + "\t" + binary + "\t" + inputs.size() + "\tchecked");
            }
        }
        Files.write(Path.of("target/parameterized-rules-rust.tsv"), report);
    }

    /** A negative {@code expected} accepts any failing exit status. */
    private String run(List<String> command, String input, int expected) throws Exception {
        Path output = temporary.newFile().toPath();
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("CARGO_INCREMENTAL", "0");
        var process = builder.start();
        try {
            process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue(command.toString(), process.waitFor(300, TimeUnit.SECONDS));
            String result = Files.readString(output);
            if (expected < 0) assertNotEquals(command + "\n" + result, 0, process.exitValue());
            else assertEquals(command + "\n" + result, expected, process.exitValue());
            return result;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
