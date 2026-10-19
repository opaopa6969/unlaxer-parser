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
import org.unlaxer.CapturedValue;
import org.unlaxer.StringSource;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.dsl.PortabilityCheck;
import org.unlaxer.dsl.bootstrap.ParameterizedRules;
import org.unlaxer.dsl.bootstrap.UBNFAST;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.impact.ApiImpact;
import org.unlaxer.parser.Parser;

/**
 * Value parameters of parameterized rules (#704) against {@code spec-corpus/call-by-value}.
 *
 * <p>The corpus is the oracle: each accepted grammar has a hand-written equivalent spelled with
 * CAPTURE_SCOPE / CAPTURE / SAME_AS, whose identifier capture names {@code captures} maps from
 * the generated names (which UBNF cannot spell); hand-written canonical keys for instance names;
 * positioned rejections; and hand-written results, failure offsets and context state per input.
 * The Java frontend and generators, the Java-hosted Rust backend and the native Rust generator
 * are each compared with it, and the parsers generated from both the sugar and the hand-written
 * grammar are run in all six memo / diagnostic settings.
 */
public class CallByValueConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final List<ParseOptions.Diagnostics> MODES = List.of(ParseOptions.Diagnostics.AUTO,
        ParseOptions.Diagnostics.DETAILED, ParseOptions.Diagnostics.DETAILED_ON_FAILURE);

    private static JsonArray cases() throws Exception {
        return JsonParser.parseString(Files.readString(ROOT.resolve("spec-corpus/call-by-value/cases.json")))
            .getAsJsonArray();
    }

    private static Map<String, String> captures(JsonObject row) {
        Map<String, String> names = new LinkedHashMap<>();
        if (row.has("captures"))
            for (var entry : row.getAsJsonObject("captures").entrySet()) names.put(entry.getKey(), entry.getValue().getAsString());
        return names;
    }

    /** Independent of the frontend's implementation. */
    private static long fnv1a64(String key) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static List<CodeGenerator> generators() {
        return List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator(), new EvaluatorGenerator(21));
    }

    @Test public void javaFrontendExpandsToTheHandWrittenCaptureGrammar() throws Exception {
        int accepted = 0, rejected = 0;
        for (var raw : cases()) {
            var row = raw.getAsJsonObject();
            String name = row.get("name").getAsString(), source = row.get("grammar").getAsString();
            if (row.has("error")) {
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
            var names = captures(row);
            var actual = UBNFMapper.parse(source);
            assertEquals(name, UBNFMapper.parse(row.get("expanded").getAsString()), rename(actual, names));
            for (String generated : names.keySet())
                assertTrue(name + "/" + generated, actual.toString().contains("name=" + generated));
            var snapshot = UBNFMapper.parseWithSource(source);
            assertEquals(name, actual, snapshot.ast());
            GrammarDecl grammar = snapshot.ast().grammars().get(0);
            for (RuleDecl rule : grammar.rules()) assertTrue(name + "/" + rule.name(), snapshot.spanOf(rule).isPresent());
            for (var item : row.getAsJsonArray("spans")) {
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
                String key = item.getAsJsonObject().get("key").getAsString();
                assertEquals(name, key.substring(0, key.indexOf('(')) + "_" + String.format("%016x", fnv1a64(key)), instance);
                assertTrue(name + "/" + instance, grammar.rules().stream()
                    .anyMatch(rule -> rule.name().equals(instance) && rule.annotations().isEmpty()));
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
        assertEquals(List.of(8, 12), List.of(accepted, rejected));
    }

    /** Each SAME_AS keeps the span of the use it replaces; the wrappers take the body's span. */
    @Test public void valueUsesKeepTheirSourceOrigins() {
        String source = "grammar G { Root ::= F('a' 'b'); F(=x) ::= x '-' x; }";
        var snapshot = UBNFMapper.parseWithSource(source);
        RuleDecl instance = snapshot.ast().grammars().get(0).rules().get(1);
        assertEquals(new UBNFSourceSnapshot.Span(source.indexOf("F(=x)"), source.length() - 2), snapshot.spanOf(instance).orElseThrow());
        var scope = (UBNFAST.CaptureScopeElement) ((UBNFAST.ChoiceBody) instance.body()).alternatives().get(0).elements().get(0).element();
        int body = source.indexOf("x '-' x");
        assertEquals(new UBNFSourceSnapshot.Origin(new UBNFSourceSnapshot.Span(body, body + 7), UBNFSourceSnapshot.Kind.SYNTHETIC),
            snapshot.originOf(scope).orElseThrow());
        var elements = ((UBNFAST.ChoiceBody) scope.body()).alternatives().get(0).elements();
        var capture = (UBNFAST.OwnedCaptureElement) elements.get(0).element();
        assertEquals("F(=x)", capture.name());
        var argument = ((UBNFAST.ChoiceBody) capture.body()).alternatives().get(0).elements().get(0).element();
        assertTrue(argument instanceof UBNFAST.GroupElement);
        assertEquals(new UBNFSourceSnapshot.Span(source.indexOf("'a' 'b'"), source.indexOf("'a' 'b'") + 7),
            snapshot.spanOf(argument).orElseThrow());
        for (int i : new int[] {1, 3}) {
            var use = elements.get(i).element();
            assertEquals(new UBNFAST.ReplayElement("F(=x)"), use);
            int at = body + (i == 1 ? 0 : 6);
            assertEquals(new UBNFSourceSnapshot.Origin(new UBNFSourceSnapshot.Span(at, at + 1), UBNFSourceSnapshot.Kind.SYNTHETIC),
                snapshot.originOf(use).orElseThrow());
        }
    }

    @Test public void theElementLimitCountsTheCaptureWrappersExactly() {
        int size = (ParameterizedRules.MAX_EXPANDED_ELEMENTS - 4) / 2;
        assertEquals(2, UBNFMapper.parse(elements(size)).grammars().get(0).rules().size());
        var error = assertThrows(ParameterizedRules.ExpansionException.class, () -> UBNFMapper.parse(elements(size + 1)));
        assertEquals("E-PARAM-LIMIT: expanded parameterized rules exceed 65536 elements", error.diagnostic());
    }

    private static String elements(int size) {
        return "grammar G { Root ::= F(" + String.join(" ", Collections.nCopies(size, "'a'")) + "); F(=x) ::= 'b'; }";
    }

    /** C{k} is created at 1 + 2k open bodies and nests 3 more: C0..C62 fit, C63 is refused. */
    @Test public void valueChainsStopAtTheInlineNestingLimitOnASmallStack() throws Exception {
        int n = (ParameterizedRules.MAX_EXPANDED_NESTING - 4) / 2;
        var outcome = new java.util.concurrent.atomic.AtomicReference<Object>();
        var thread = new Thread(null, () -> {
            try {
                boolean fits = UBNFMapper.parse(valueChain(n)).grammars().get(0).rules().size() == n + 2;
                var error = assertThrows(ParameterizedRules.ExpansionException.class, () -> UBNFMapper.parse(valueChain(n + 1)));
                outcome.set(List.of(fits, error.diagnostic().equals("E-PARAM-LIMIT: expansion of rule C" + (n + 1)
                    + " nests " + (2 * (n + 1) + 4) + " levels; the limit is 128")));
            } catch (Throwable error) { outcome.set(error); }
        }, "call-by-value-chain", 512 * 1024);
        thread.start();
        thread.join(120_000);
        assertEquals(String.valueOf(outcome.get()), List.of(true, true), outcome.get());
    }

    private static String valueChain(int n) {
        StringBuilder source = new StringBuilder("grammar G { Root ::= C0('a'); ");
        for (int k = 0; k < n; k++) source.append("C").append(k).append("(=x) ::= C").append(k + 1).append("(x); ");
        return source.append("C").append(n).append("(=x) ::= x; }").toString();
    }

    /** A value parameter replays one element, so doubling its uses does not grow arguments. */
    @Test public void valueUsesDoNotMultiplyArgumentSize() {
        StringBuilder source = new StringBuilder("grammar G { Root ::= D40('a'); D0(=x) ::= x x; ");
        for (int k = 1; k <= 40; k++) source.append("D").append(k).append("(=x) ::= D").append(k - 1).append("(x x); ");
        assertEquals(42, UBNFMapper.parse(source.append("}").toString()).grammars().get(0).rules().size());
    }

    @Test public void generatedCaptureNamesCannotBeWritten() {
        for (String source : List.of("grammar G { Root ::= CAPTURE(F(=x), 'a'); }", "grammar G { Root ::= SAME_AS(F(=x)); }")) {
            var error = assertThrows(source, IllegalArgumentException.class, () -> UBNFMapper.parse(source));
            assertFalse(source, error instanceof ParameterizedRules.ExpansionException);
        }
    }

    @Test public void javaGeneratedParsersEqualTheHandWrittenGrammarAndMatchExpectedValues() throws Exception {
        var report = new ArrayList<>(List.of("fixture\tgrammar\tinput\tmemo\tdiagnostics\tstatus"));
        for (var raw : cases()) {
            var row = raw.getAsJsonObject();
            if (row.has("error")) continue;
            String name = row.get("name").getAsString();
            var names = captures(row);
            var grammar = UBNFMapper.parse(row.get("grammar").getAsString()).grammars().get(0);
            var hand = UBNFMapper.parse(row.get("expanded").getAsString()).grammars().get(0);
            var sugarUnits = new ArrayList<JavaFileObject>();
            var handUnits = new ArrayList<JavaFileObject>();
            for (CodeGenerator generator : generators()) {
                var source = generator.generate(grammar);
                var expected = generator.generate(hand);
                assertEquals(name + "/" + source.className(), expected.source(), generator.generate(renameGrammar(grammar, names)).source());
                assertEquals(name + "/" + source.className(), expected.source(), renameLiterals(source.source(), names));
                sugarUnits.add(unit(source.className(), source.source()));
                handUnits.add(unit(expected.className(), expected.source()));
            }
            run(name, "sugar", row, sugarUnits, names.keySet(), report);
            run(name, "hand", row, handUnits, new LinkedHashSet<>(names.values()), report);
        }
        Files.write(Path.of("target/call-by-value-java.tsv"), report);
    }

    private static JavaFileObject unit(String className, String source) {
        return new SimpleJavaFileObject(URI.create("string:///cbvprobe/" + className + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        };
    }

    /** Compiles one generated parser set and checks every input in all six settings. */
    private void run(String name, String kind, JsonObject row, List<JavaFileObject> units, Set<String> sentinels,
            List<String> report) throws Exception {
        Path classes = temporary.newFolder().toPath();
        var compiler = org.unlaxer.dsl.Java21TestSupport.compiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            boolean compiled = compiler.getTask(null, manager, diagnostics,
                List.of("--release", "21", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                null, units).call();
            assertTrue(name + " " + diagnostics.getDiagnostics(), compiled);
        }
        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            var parser = (Parser) loader.loadClass("cbvprobe.CbvParsers").getMethod("getRootParser").invoke(null);
            var mapper = loader.loadClass("cbvprobe.CbvMapper");
            for (var item : row.getAsJsonArray("inputs")) {
                var input = item.getAsJsonObject();
                String text = input.get("input").getAsString();
                for (var memo : Memoization.values()) for (var mode : MODES) {
                    String label = name + "/" + kind + "/" + text + "/" + memo + "/" + mode;
                    var result = new JsonObject();
                    var state = new JsonObject();
                    try (var context = ParseContext.withOptions(StringSource.createRootSource(text),
                            ParseOptions.withMemoization(memo).withDiagnostics(mode))) {
                        // An outer binding under every expansion name must survive the parse.
                        for (String sentinel : sentinels)
                            context.capture(sentinel, new CapturedValue("", CapturedValue.Provenance.INPUT, 0, 0));
                        var parsed = parser.parse(context);
                        state.addProperty("success", parsed.isSucceeded());
                        state.addProperty("consumed", context.getConsumedPosition().value());
                        state.addProperty("matched", context.getMatchedPosition().value());
                        for (String sentinel : sentinels)
                            assertEquals(label + "/" + sentinel, "", context.captured(sentinel).orElseThrow().text());
                        if (parsed.isFailed()) {
                            result.addProperty("status", "syntax");
                            int offset = context.getParseFailureDiagnostics().getFarthestOffset();
                            if (mode == ParseOptions.Diagnostics.DETAILED_ON_FAILURE) {
                                try (var detailed = ParseContext.withOptions(StringSource.createRootSource(text),
                                        ParseOptions.withMemoization(memo).withDiagnostics(ParseOptions.Diagnostics.DETAILED))) {
                                    assertTrue(parser.parse(detailed).isFailed());
                                    offset = detailed.getParseFailureDiagnostics().getFarthestOffset();
                                }
                            }
                            result.addProperty("offset", offset);
                        } else if (context.getConsumedPosition().value() != text.codePointCount(0, text.length())) {
                            result.addProperty("status", "syntax");
                            result.addProperty("offset", context.getConsumedPosition().value());
                        } else {
                            var token = context.getCurrent().getTokens().stream()
                                .filter(candidate -> candidate.parser == parser).findFirst().orElseThrow();
                            Object mapped;
                            try {
                                mapped = mapper.getMethod("mapParsedTokenWithSourceMap", org.unlaxer.Token.class).invoke(null, token);
                            } catch (InvocationTargetException failure) {
                                throw new AssertionError(label, failure.getCause());
                            }
                            result.addProperty("status", "ok");
                            result.add("value", observe(mapped.getClass().getMethod("ast").invoke(mapped), mapped));
                        }
                    }
                    assertEquals(label, input.get("expected"), result);
                    assertEquals(label, input.get("state"), state);
                    report.add(name + "\t" + kind + "\t" + input.get("input") + "\t" + memo + "\t" + mode + "\t" + result.get("status"));
                }
            }
        }
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
        result.addProperty("type", value.getClass().getName().split("CbvAST\\$")[1].replace("$", "__"));
        result.add("span", span);
        var fields = new JsonObject();
        for (var field : value.getClass().getRecordComponents())
            fields.add(field.getName(), observe(field.getAccessor().invoke(value), mapped));
        result.add("fields", fields);
        return result;
    }

    // =========================================================================
    // Generated capture names to the identifiers of the hand-written grammar
    // =========================================================================

    private static String maskFingerprint(String generated) {
        return generated.replaceAll("#preferred-schema:[0-9a-f]+", "#preferred-schema:*");
    }

    private static String renameLiterals(String generated, Map<String, String> names) {
        for (var entry : names.entrySet())
            generated = generated.replace("\"" + entry.getKey() + "\"", "\"" + entry.getValue() + "\"");
        return generated;
    }

    private static UBNFAST.UBNFFile rename(UBNFAST.UBNFFile file, Map<String, String> names) {
        return new UBNFAST.UBNFFile(file.grammars().stream().map(grammar -> renameGrammar(grammar, names)).toList());
    }

    private static GrammarDecl renameGrammar(GrammarDecl grammar, Map<String, String> names) {
        return new GrammarDecl(grammar.name(), grammar.imports(), grammar.settings(), grammar.tokens(), grammar.rules().stream()
            .map(rule -> new RuleDecl(rule.annotations(), rule.name(), body(rule.body(), names))).toList());
    }

    private static UBNFAST.RuleBody body(UBNFAST.RuleBody body, Map<String, String> names) {
        if (body instanceof UBNFAST.ChoiceBody choice)
            return new UBNFAST.ChoiceBody(choice.alternatives().stream().map(sequence -> sequence(sequence, names)).toList());
        return sequence((UBNFAST.SequenceBody) body, names);
    }

    private static UBNFAST.SequenceBody sequence(UBNFAST.SequenceBody sequence, Map<String, String> names) {
        return new UBNFAST.SequenceBody(sequence.elements().stream().map(element -> new UBNFAST.AnnotatedElement(
            atomic(element.element(), names), element.captureName(), element.typeofConstraint())).toList());
    }

    private static UBNFAST.AtomicElement atomic(UBNFAST.AtomicElement element, Map<String, String> names) {
        if (element instanceof UBNFAST.OwnedCaptureElement capture)
            return new UBNFAST.OwnedCaptureElement(names.getOrDefault(capture.name(), capture.name()), body(capture.body(), names));
        if (element instanceof UBNFAST.ReplayElement replay)
            return new UBNFAST.ReplayElement(names.getOrDefault(replay.name(), replay.name()));
        if (element instanceof UBNFAST.CaptureScopeElement scope) return new UBNFAST.CaptureScopeElement(body(scope.body(), names));
        if (element instanceof UBNFAST.GroupElement group) return new UBNFAST.GroupElement(body(group.body(), names));
        if (element instanceof UBNFAST.OptionalElement optional) return new UBNFAST.OptionalElement(body(optional.body(), names));
        if (element instanceof UBNFAST.RepeatElement repeat) return new UBNFAST.RepeatElement(body(repeat.body(), names));
        if (element instanceof UBNFAST.OneOrMoreElement more) return new UBNFAST.OneOrMoreElement(atomic(more.body(), names));
        if (element instanceof UBNFAST.BoundedRepeatElement bounded)
            return new UBNFAST.BoundedRepeatElement(atomic(bounded.body(), names), bounded.min(), bounded.max());
        if (element instanceof UBNFAST.SeparatedElement separated)
            return new UBNFAST.SeparatedElement(atomic(separated.element(), names), atomic(separated.separator(), names));
        return element;
    }

    // =========================================================================
    // Rust: both hosts, byte for byte, and real consumers
    // =========================================================================

    private static final String PROBE = String.join("\n",
        "#![allow(dead_code, unused_imports)]",
        "mod generated;",
        "use std::io::BufRead;",
        "use unlaxer_runtime::{Diagnostics, Expr, Memoization, ParseContext, ParseOptions};",
        "fn main() {",
        "    let sentinels: Vec<&'static str> = std::env::args().skip(1).map(|name| &*Box::leak(name.into_boxed_str())).collect();",
        "    let root = generated::parser::rules().iter().position(|rule| rule.name == \"Root\").unwrap();",
        "    for line in std::io::stdin().lock().lines() {",
        "        let hex = line.unwrap();",
        "        let input = String::from_utf8((0..hex.len()).step_by(2).map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap()).collect()).unwrap();",
        "        for memo in [Memoization::Off, Memoization::SafeFailures] {",
        "            for diagnostics in [Diagnostics::Auto, Diagnostics::Detailed, Diagnostics::DetailedOnFailure] {",
        "                let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);",
        "                let result = match generated::parser::parse_tree_with_options(&input, options) {",
        "                    Ok(tree) => {",
        "                        assert_eq!(tree.nodes[tree.root].span, unlaxer_runtime::Span { start: 0, end: input.chars().count() });",
        "                        let ast = generated::mapper::map(&tree).unwrap();",
        "                        serde_json::json!({\"status\": \"ok\", \"value\": serde_json::from_str::<serde_json::Value>(&ast.canonical_json()).unwrap()})",
        "                    }",
        "                    Err(error) => serde_json::json!({\"status\": \"syntax\", \"offset\": error.offset}),",
        "                };",
        "                let mut context = ParseContext::with_options(&input, options);",
        "                // An outer binding under every expansion name must survive the parse.",
        "                for name in &sentinels {",
        "                    context.parse(&Expr::Literal(\"unused\").repeat_java(0, Some(0)).capture_owned(name)).unwrap();",
        "                }",
        "                let success = context.parse_shared_grammar(generated::parser::grammar(), root, false).is_ok();",
        "                for name in &sentinels {",
        "                    assert_eq!(context.captured_text(name), Some(\"\"), \"{name}\");",
        "                }",
        "                let state = serde_json::json!({\"success\": success, \"consumed\": context.position(), \"matched\": context.matched_position()});",
        "                println!(\"{}\", serde_json::json!({\"expected\": result, \"state\": state}));",
        "            }",
        "        }",
        "    }",
        "}",
        "");

    @Test public void bothRustHostsEmitTheHandWrittenGrammarAndRunExpectedValues() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] CallByValueConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        run(List.of("cargo", "build", "--locked", "--quiet", "--manifest-path", ROOT.resolve("rust/Cargo.toml").toString(),
            "-p", "unlaxer-generator"), "", 0);
        Path unlaxer = ROOT.resolve("rust/target/debug/unlaxer");
        var report = new ArrayList<>(List.of("fixture\thost\tinputs\tstatus"));
        for (var raw : cases()) {
            var row = raw.getAsJsonObject();
            String name = row.get("name").getAsString(), source = row.get("grammar").getAsString();
            Path dir = temporary.newFolder().toPath(), grammarFile = dir.resolve("cbv.ubnf");
            Files.writeString(grammarFile, source);
            if (row.has("error")) {
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
            var names = captures(row);
            var grammar = UBNFMapper.parse(source).grammars().get(0);
            Path expandedFile = dir.resolve("expanded.ubnf");
            Files.writeString(expandedFile, row.get("expanded").getAsString());
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
                // The preferred-snapshot fingerprint hashes the parser source, which spells the
                // capture names, so only it is masked after renaming.
                assertEquals(name + "/" + file.relativePath(),
                    maskFingerprint(Files.readString(dir.resolve("native-expanded").resolve(file.relativePath()))),
                    maskFingerprint(renameLiterals(nativeContent, names)));
                Files.writeString(java.resolve(file.relativePath()), file.content());
            }
            var impact = JsonParser.parseString(run(List.of(unlaxer.toString(), "impact", "--target", "rust", "--before",
                expandedFile.toString(), "--after", grammarFile.toString(), "--format", "json"), "", 0).trim()).getAsJsonObject();
            assertTrue(name + " " + impact, impact.get("ok").getAsBoolean());
            assertFalse(name + " " + impact, impact.get("hasChanges").getAsBoolean());
            Files.writeString(dir.resolve("main.rs"), PROBE.replace("mod generated;", "#[path = \"java/mod.rs\"] mod generated;"));
            Files.writeString(dir.resolve("main-native.rs"), PROBE.replace("mod generated;", "#[path = \"native/mod.rs\"] mod generated;"));
            Files.writeString(dir.resolve("Cargo.toml"), "[package]\nname=\"call-by-value-probe\"\nversion=\"0.1.0\"\n"
                + "edition=\"2021\"\n[[bin]]\nname=\"probe\"\npath=\"main.rs\"\n[[bin]]\nname=\"probe-native\"\n"
                + "path=\"main-native.rs\"\n[dependencies]\nunlaxer-runtime={path=\"" + ROOT.resolve("rust/unlaxer-runtime")
                + "\"}\nserde_json=\"1\"\n");
            Path target = ROOT.resolve("rust/target/call-by-value-probe");
            run(List.of("cargo", "build", "--quiet", "--offline", "--manifest-path", dir.resolve("Cargo.toml").toString(),
                "--target-dir", target.toString()), "", 0);
            var inputs = row.getAsJsonArray("inputs");
            StringBuilder stdin = new StringBuilder();
            for (var item : inputs)
                stdin.append(HexFormat.of().formatHex(item.getAsJsonObject().get("input").getAsString()
                    .getBytes(StandardCharsets.UTF_8))).append('\n');
            for (String binary : List.of("probe", "probe-native")) {
                var command = new ArrayList<>(List.of(target.resolve("debug/" + binary).toString()));
                command.addAll(names.keySet());
                var lines = run(command, stdin.toString(), 0).lines().toList();
                assertEquals(name, inputs.size() * 6, lines.size());
                for (int i = 0; i < lines.size(); i++) {
                    var input = inputs.get(i / 6).getAsJsonObject();
                    var expected = new JsonObject();
                    expected.add("expected", input.get("expected"));
                    expected.add("state", input.get("state"));
                    assertEquals(name + "/" + binary + "/" + input.get("input") + "/" + (i % 6), expected,
                        JsonParser.parseString(lines.get(i)));
                }
                report.add(name + "\t" + binary + "\t" + inputs.size() + "\tchecked");
            }
        }
        Files.write(Path.of("target/call-by-value-rust.tsv"), report);
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
