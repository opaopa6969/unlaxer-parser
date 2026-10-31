package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.context.SemanticPredicateBindings;
import org.unlaxer.context.SemanticPredicateBindings.Candidate;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;

/**
 * #1006 named semantic predicates: actual generated Java, Java-host Rust and native Rust consumers
 * against the same handwritten oracle, plus fixture sensitivity to the mutations the oracle must catch.
 */
public class SemanticPredicateConformanceTest {
    private static final String PACKAGE = "org.example.semanticpredicate";
    private static final List<ParseOptions> OPTIONS = List.of(ParseOptions.DEFAULT,
        ParseOptions.DEFAULT.withDiagnostics(ParseOptions.Diagnostics.DETAILED),
        ParseOptions.DEFAULT.withDiagnostics(ParseOptions.Diagnostics.DETAILED_ON_FAILURE),
        ParseOptions.withMemoization(Memoization.SAFE_FAILURES),
        ParseOptions.withMemoization(Memoization.SAFE_FAILURES).withDiagnostics(ParseOptions.Diagnostics.DETAILED_ON_FAILURE));

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void semanticPredicatesPreserveIndependentOracles() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc/cargo)",
            Boolean.getBoolean("rustConformance"));

        Path runtime = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib",
            "--crate-name=unlaxer_runtime", repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),
            "-o", runtime.toString()), "", false));
        Path nativeTarget = temporary.getRoot().toPath().resolve("native-target");
        success(run(List.of("cargo", "build", "--locked", "--manifest-path",
            repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator", "--target-dir",
            nativeTarget.toString()), "", false));
        Path nativeGenerator = nativeTarget.resolve("debug/unlaxer").toAbsolutePath();

        Path fixtures = repo.resolve("unlaxer-dsl/src/test/resources/semantic-predicate");
        JsonArray corpus = JsonParser.parseString(Files.readString(fixtures.resolve("corpus.json"))).getAsJsonArray();
        var report = new ArrayList<>(List.of("fixture\tcase\tinput_json\texpected_prefix\tjava_prefix"
            + "\texpected_candidates\tjava_candidates\texpected_ast\tjava_ast\tjava_host_rust\tnative_rust"));
        int fixtureIndex = 0;
        int executedCases = 0;
        var mismatches = new ArrayList<String>();
        for (JsonElement fixtureElement : corpus) {
            JsonObject fixture = fixtureElement.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            String source = fixture.get("grammar").getAsString();
            GrammarDecl grammar = UBNFMapper.parse(source).grammars().get(0);
            GrammarValidator.validateOrThrow(grammar);
            List<RustBackend.GeneratedFile> javaFrontend = new RustBackend().generate(grammar);

            Path fixtureDir = Files.createDirectories(temporary.getRoot().toPath().resolve("fixture-" + fixtureIndex++));
            Path ubnf = fixtureDir.resolve(grammar.name() + ".ubnf");
            Files.writeString(ubnf, source);
            Path javaHost = Files.createDirectories(fixtureDir.resolve("java-host/generated"));
            for (var file : javaFrontend) Files.writeString(javaHost.resolve(file.relativePath()), file.content());
            Path nativeHost = fixtureDir.resolve("native-host/generated");
            success(run(List.of(nativeGenerator.toString(), "generate", "--grammar", ubnf.toString(),
                "--output", nativeHost.toString()), "", true));
            for (var file : javaFrontend) {
                assertArrayEquals(name + " native frontend byte parity: " + file.relativePath(),
                    file.content().getBytes(StandardCharsets.UTF_8), Files.readAllBytes(nativeHost.resolve(file.relativePath())));
            }

            JsonArray cases = fixture.getAsJsonArray("cases");
            String framed = String.join("\n", cases.asList().stream().map(row -> {
                JsonObject value = row.getAsJsonObject();
                return hex(value.get("input").getAsString()) + " " + (value.get("bindings").isJsonNull()
                    ? "-" : hex(spec(value.getAsJsonObject("bindings"))));
            }).toList()) + "\n";
            // Each host is compiled and executed separately; byte parity alone is not the oracle.
            var rustLines = new LinkedHashMap<String, List<String>>();
            for (Path host : List.of(javaHost.getParent(), nativeHost.getParent())) {
                Files.writeString(host.resolve("main.rs"), rustProbe());
                success(run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + runtime,
                    host.resolve("main.rs").toString(), "-o", host.resolve("probe").toString()), "", false));
                ProcessResult result = run(List.of(host.resolve("probe").toString()), framed, false);
                success(result);
                List<String> lines = result.output().lines().toList();
                assertEquals(name + " " + host.getFileName() + " Rust result count", cases.size(), lines.size());
                rustLines.put(host.getFileName().toString(), lines);
            }

            try (URLClassLoader loader = compileJava(grammar)) {
                Parser parser = (Parser) loader.loadClass(PACKAGE + "." + grammar.name() + "Parsers")
                    .getMethod("getRootParser").invoke(null);
                Class<?> mapper = loader.loadClass(PACKAGE + "." + grammar.name() + "Mapper");
                for (int i = 0; i < cases.size(); i++) {
                  JsonObject row = cases.get(i).getAsJsonObject();
                  try {
                    String input = row.get("input").getAsString();
                    String context = name + "/" + row.get("id").getAsString();
                    JsonObject bindingSpec = row.get("bindings").isJsonNull() ? null : row.getAsJsonObject("bindings");

                    var seen = new ArrayList<Candidate>();
                    JsonArray javaPrefix = prefix(parser, input, bindings(bindingSpec, seen), ParseOptions.DEFAULT);
                    assertEquals(context + " Java memo prefix parity", javaPrefix, prefix(parser, input,
                        bindings(bindingSpec, new ArrayList<>()), ParseOptions.withMemoization(Memoization.SAFE_FAILURES)));
                    assertEquals(context + " independent prefix cursor oracle", row.get("prefix"), javaPrefix);

                    seen.clear();
                    Optional<?> diagnostic = diagnose(mapper, input, bindings(bindingSpec, seen), ParseOptions.DEFAULT);
                    JsonArray javaCandidates = candidates(seen);
                    assertEquals(context + " independent candidate text/CP span oracle", row.get("candidates"), javaCandidates);
                    for (ParseOptions options : OPTIONS) {
                        assertEquals(context + " Java diagnostic mode/memo parity " + options, describe(diagnostic),
                            describe(diagnose(mapper, input, bindings(bindingSpec, new ArrayList<>()), options)));
                    }
                    boolean accepted = row.get("accepted").getAsBoolean();
                    assertEquals(context + " Java full-input acceptance", accepted, diagnostic.isEmpty());
                    if (row.has("diagnostic")) assertDiagnostic(context + " Java", row.getAsJsonObject("diagnostic"), describe(diagnostic));
                    JsonElement javaAst = JsonNull.INSTANCE;
                    if (accepted) {
                        for (ParseOptions options : OPTIONS) {
                            Object mapped = mapper.getMethod("parseWithPredicatesAndSourceMap", String.class, Map.class, ParseOptions.class)
                                .invoke(null, input, bindings(bindingSpec, new ArrayList<>()), options);
                            JsonElement ast = canonical(mapped.getClass().getMethod("ast").invoke(mapped), mapped);
                            assertEquals(context + " Java AST all fields/node spans " + options, row.get("ast"), ast);
                            javaAst = ast;
                        }
                    }
                    for (var host : rustLines.entrySet()) {
                        String rustContext = context + " " + host.getKey();
                        JsonObject rust = JsonParser.parseString(host.getValue().get(i)).getAsJsonObject();
                        assertEquals(rustContext + " prefix cursor", row.get("prefix"), rust.get("prefix"));
                        assertEquals(rustContext + " candidate text/CP span", row.get("candidates"), rust.get("candidates"));
                        assertEquals(rustContext + " full-input acceptance", accepted, !rust.get("ast").isJsonNull());
                        if (accepted) assertEquals(rustContext + " AST all fields/node spans", row.get("ast"), rust.get("ast"));
                        if (row.has("diagnostic")) assertDiagnostic(rustContext, row.getAsJsonObject("diagnostic"), rust.get("diagnostic"));
                    }
                    executedCases++;
                    report.add(name + "\t" + row.get("id").getAsString() + "\t" + row.get("input") + "\t" + row.get("prefix")
                        + "\t" + javaPrefix + "\t" + row.get("candidates") + "\t" + javaCandidates + "\t" + row.get("ast")
                        + "\t" + javaAst + "\t" + rustLines.get("java-host").get(i) + "\t" + rustLines.get("native-host").get(i));
                  } catch (AssertionError mismatch) {
                    // Report every case: one disagreement must not hide the other hosts' results.
                    mismatches.add(mismatch.getMessage() + "\n    java-host: " + rustLines.get("java-host").get(i)
                        + "\n    native-host: " + rustLines.get("native-host").get(i));
                  }
                }
                assertFixtureSensitivity(name, cases, mapper);
            }
            assertPredicateRemovalIsDetected(name, source, cases);
        }
        assertTrue(String.join("\n", mismatches), mismatches.isEmpty());
        assertEquals("executed corpus cases", 38, executedCases);

        JsonArray invalid = JsonParser.parseString(Files.readString(fixtures.resolve("invalid.json"))).getAsJsonArray();
        for (var element : invalid) {
            JsonObject row = element.getAsJsonObject();
            var grammar = UBNFMapper.parse(row.get("grammar").getAsString()).grammars().get(0);
            String name = row.get("name").getAsString();
            // The first error, not any error: frontends must agree on which refusal wins (B3).
            assertEquals(name + " independent first invalid code", row.get("code").getAsString(),
                GrammarValidator.validateWithoutClassLoading(grammar).stream()
                    .filter(issue -> issue.severity().equals("ERROR")).findFirst().orElseThrow().code());
            var javaGenerator = assertThrows(name + " Java generator refusal", IllegalArgumentException.class,
                () -> new ParserGenerator().generate(grammar));
            assertTrue(name + ": " + javaGenerator.getMessage(), javaGenerator.getMessage().contains(row.get("nativeMessage").getAsString()));
            var javaHost = assertThrows(name + " Java frontend Rust lowering refusal", IllegalArgumentException.class,
                () -> new RustBackend().generate(grammar));
            assertTrue(name + ": " + javaHost.getMessage(), javaHost.getMessage().contains(row.get("nativeMessage").getAsString()));
            Path source = temporary.getRoot().toPath().resolve("invalid-" + name + ".ubnf");
            Files.writeString(source, row.get("grammar").getAsString());
            var nativeResult = run(List.of(nativeGenerator.toString(), "generate", "--grammar", source.toString(),
                "--output", temporary.getRoot().toPath().resolve("invalid-output").toString()), "", true);
            assertNotEquals(name + " native generation refusal", 0, nativeResult.code());
            assertTrue(name + ": " + nativeResult.output(), nativeResult.output().contains(row.get("nativeMessage").getAsString()));
            report.add("invalid\t" + name + "\t\t" + row.get("code") + "\t" + nativeResult.output().strip().replace('\n', ' '));
        }
        assertEquals("invalid grammars", 24, invalid.size());

        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.write(target.resolve("rust-semantic-predicate.tsv"), report, StandardCharsets.UTF_8);
    }

    /** The oracle must reject an implementation that ignores false or hides missing/lost registries. */
    private void assertFixtureSensitivity(String fixture, JsonArray cases, Class<?> mapper) throws Exception {
        int ignoredFalse = 0;
        for (var element : cases) {
            JsonObject row = element.getAsJsonObject();
            if (row.get("bindings").isJsonNull()) continue;
            String input = row.get("input").getAsString();
            JsonObject spec = row.getAsJsonObject("bindings");
            var accepting = new JsonObject();
            for (String name : spec.keySet()) accepting.addProperty(name, "const:true");
            if (!sameOutcome(row, mapper, input, bindings(accepting, new ArrayList<>()))) ignoredFalse++;
            if (row.has("diagnostic") && row.getAsJsonObject("diagnostic").get("kind").getAsString().equals("predicate_missing")) {
                var masked = new JsonObject();
                for (String name : SemanticPredicates.requirements(UBNFMapper.parse(grammarOf(fixture)).grammars().get(0))) {
                    masked.addProperty(name, spec.has(name) ? spec.get(name).getAsString() : "const:false");
                }
                assertFalse(fixture + "/" + row.get("id") + " missing registry masked as false must be detected",
                    sameOutcome(row, mapper, input, bindings(masked, new ArrayList<>())));
            }
            if (row.has("diagnostic") && row.getAsJsonObject("diagnostic").get("kind").getAsString().equals("syntax")) {
                assertFalse(fixture + "/" + row.get("id") + " retry without the original registry must be detected",
                    sameOutcome(row, mapper, input, Map.of()));
            }
        }
        assertTrue(fixture + " ignoring false must change at least one oracle", ignoredFalse > 0);
    }

    private void assertPredicateRemovalIsDetected(String fixture, String source, JsonArray cases) throws Exception {
        GrammarDecl stripped = UBNFMapper.parse(source.replaceAll("@predicate\\('[^']*'\\)", "")).grammars().get(0);
        int changed = 0;
        try (URLClassLoader loader = compileJava(stripped)) {
            Class<?> mapper = loader.loadClass(PACKAGE + "." + stripped.name() + "Mapper");
            for (var element : cases) {
                JsonObject row = element.getAsJsonObject();
                String input = row.get("input").getAsString();
                Optional<?> diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class, ParseOptions.class)
                    .invoke(null, input, ParseOptions.DEFAULT);
                boolean accepted = diagnostic.isEmpty();
                JsonElement ast = JsonNull.INSTANCE;
                if (accepted) {
                    Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
                    ast = canonical(mapped.getClass().getMethod("ast").invoke(mapped), mapped);
                }
                if (accepted != row.get("accepted").getAsBoolean() || (accepted && !ast.equals(row.get("ast")))) changed++;
            }
        }
        assertTrue(fixture + " removing @predicate must change at least one oracle", changed > 0);
    }

    private final Map<String, String> grammars = new LinkedHashMap<>();
    private String grammarOf(String fixture) throws Exception {
        if (grammars.isEmpty()) {
            for (var element : JsonParser.parseString(Files.readString(repo.resolve(
                    "unlaxer-dsl/src/test/resources/semantic-predicate/corpus.json"))).getAsJsonArray()) {
                grammars.put(element.getAsJsonObject().get("name").getAsString(), element.getAsJsonObject().get("grammar").getAsString());
            }
        }
        return grammars.get(fixture);
    }

    private boolean sameOutcome(JsonObject row, Class<?> mapper, String input,
            Map<String, Predicate<Candidate>> predicates) throws Exception {
        Optional<?> diagnostic = diagnose(mapper, input, predicates, ParseOptions.DEFAULT);
        if (diagnostic.isEmpty() != row.get("accepted").getAsBoolean()) return false;
        if (diagnostic.isPresent()) {
            JsonObject expected = row.getAsJsonObject("diagnostic");
            JsonObject actual = describe(diagnostic).getAsJsonObject();
            return expected.get("kind").equals(actual.get("kind")) && expected.get("offset").equals(actual.get("offset"));
        }
        Object mapped = mapper.getMethod("parseWithPredicatesAndSourceMap", String.class, Map.class, ParseOptions.class)
            .invoke(null, input, predicates, ParseOptions.DEFAULT);
        return row.get("ast").equals(canonical(mapped.getClass().getMethod("ast").invoke(mapped), mapped));
    }

    private static void assertDiagnostic(String context, JsonObject expected, JsonElement actualElement) {
        JsonObject actual = actualElement.getAsJsonObject();
        assertEquals(context + " diagnostic kind", expected.get("kind"), actual.get("kind"));
        assertEquals(context + " diagnostic CP offset", expected.get("offset"), actual.get("offset"));
        assertTrue(context + " diagnostic expected " + actual, actual.getAsJsonArray("expected").asList()
            .contains(expected.get("expected")));
    }

    private static Optional<?> diagnose(Class<?> mapper, String input, Map<String, Predicate<Candidate>> predicates,
            ParseOptions options) throws Exception {
        if (predicates == null) {
            return (Optional<?>) mapper.getMethod("diagnose", String.class, ParseOptions.class).invoke(null, input, options);
        }
        return (Optional<?>) mapper.getMethod("diagnoseWithPredicates", String.class, Map.class, ParseOptions.class)
            .invoke(null, input, predicates, options);
    }

    private static JsonElement describe(Optional<?> diagnostic) throws Exception {
        if (diagnostic.isEmpty()) return JsonNull.INSTANCE;
        Object value = diagnostic.get();
        var result = new JsonObject();
        result.addProperty("kind", (String) value.getClass().getMethod("kind").invoke(value));
        result.addProperty("offset", (Integer) value.getClass().getMethod("offset").invoke(value));
        var expected = new JsonArray();
        for (Object hint : (List<?>) value.getClass().getMethod("expected").invoke(value)) expected.add(hint.toString());
        result.add("expected", expected);
        return result;
    }

    private static JsonArray candidates(List<Candidate> seen) {
        var result = new JsonArray();
        seen.stream().distinct().sorted(Comparator.comparingInt(Candidate::start).thenComparingInt(Candidate::end)
                .thenComparing(Candidate::text)).forEach(candidate -> {
            var row = new JsonArray();
            row.add(candidate.text());
            row.add(candidate.start());
            row.add(candidate.end());
            result.add(row);
        });
        return result;
    }

    private static String spec(JsonObject bindings) {
        return String.join(";", bindings.keySet().stream().map(name -> name + "=" + bindings.get(name).getAsString()).toList());
    }

    /** Same pure callbacks as the Rust probe; every evaluation is recorded for the candidate oracle. */
    private static Map<String, Predicate<Candidate>> bindings(JsonObject spec, List<Candidate> seen) {
        if (spec == null) return null;
        var result = new LinkedHashMap<String, Predicate<Candidate>>();
        for (String name : spec.keySet()) {
            String rule = spec.get(name).getAsString();
            result.put(name, candidate -> {
                seen.add(candidate);
                return evaluate(rule, candidate.text());
            });
        }
        return result;
    }

    private static boolean evaluate(String rule, String text) {
        String[] parts = rule.split(":", 2);
        String arguments = parts.length > 1 ? parts[1] : "";
        switch (parts[0]) {
            case "const": return Boolean.parseBoolean(arguments);
            case "range": {
                String[] bounds = arguments.split(":");
                Long value = digits(text);
                return value != null && value >= Long.parseLong(bounds[0]) && value <= Long.parseLong(bounds[1]);
            }
            case "rangeStripped": return evaluate("range:" + arguments, text.strip());
            case "oneOf": return List.of(arguments.split("\\|")).contains(text);
            case "ordered": {
                String[] sides = text.replaceAll("!$", "").split("-", 2);
                Long low = digits(sides[0]);
                Long high = digits(sides[1]);
                return low != null && high != null && low <= high;
            }
            case "even": {
                Long value = digits(text);
                return value != null && value % 2 == 0;
            }
            case "error": throw new IllegalArgumentException("application detail");
            case "panic": throw new IllegalStateException("probe panic");
            default: throw new AssertionError(rule);
        }
    }

    private static Long digits(String text) {
        if (text.isEmpty() || text.length() > 18 || !text.chars().allMatch(c -> c >= '0' && c <= '9')) return null;
        return Long.parseLong(text);
    }

    private static JsonArray prefix(Parser parser, String input, Map<String, Predicate<Candidate>> predicates,
            ParseOptions options) {
        var result = new JsonArray();
        try (var context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
            if (predicates != null) SemanticPredicateBindings.install(context, predicates);
            var parsed = parser.parse(context);
            result.add(parsed.isSucceeded());
            if (parsed.isSucceeded()) {
                String prefixText = input.substring(0, input.offsetByCodePoints(0, context.getConsumedPosition().value()));
                assertEquals("raw selected Java CST source", prefixText, parsed.getConsumed().source.toString());
            }
            result.add(context.getConsumedPosition().value());
            result.add(context.getMatchedPosition().value());
        }
        return result;
    }

    private URLClassLoader compileJava(GrammarDecl grammar) throws Exception {
        var sources = new ArrayList<GeneratedSource>();
        for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
            sources.add(generator.generate(grammar));
        }
        var units = sources.stream().map(source -> new SimpleJavaFileObject(
            URI.create("string:///" + source.packageName().replace('.', '/') + "/" + source.className() + ".java"),
            JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source.source();
                }
            }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("tests require a JDK, not a JRE", compiler);
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17", "-classpath", System.getProperty("java.class.path") + File.pathSeparator,
                    "-d", output.toString()), null, units).call();
            assertTrue(diagnostics.getDiagnostics().toString(), compiled);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private JsonObject canonical(Object ast, Object mapped) throws Exception {
        var result = new JsonObject();
        result.addProperty("type", ast.getClass().getSimpleName());
        int[] span = (int[]) ((Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class)
            .invoke(mapped, ast)).orElseThrow();
        var position = new JsonArray();
        position.add(span[0]);
        position.add(span[1]);
        result.add("span", position);
        var fields = new JsonObject();
        for (var component : ast.getClass().getRecordComponents()) {
            fields.add(component.getName(), canonicalValue(component.getAccessor().invoke(ast), mapped));
        }
        result.add("fields", fields);
        return result;
    }

    private JsonElement canonicalValue(Object value, Object mapped) throws Exception {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Optional<?> optional) return canonicalValue(optional.orElse(null), mapped);
        if (value instanceof List<?> list) {
            var result = new JsonArray();
            for (Object item : list) result.add(canonicalValue(item, mapped));
            return result;
        }
        return canonical(value, mapped);
    }

    private static String hex(String value) {
        return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String rustProbe() {
        return """
            mod generated;
            use std::io::{self, BufRead};
            use std::sync::{Arc, Mutex};
            use unlaxer_runtime::semantic_predicates::{Bindings, Candidate, PredicateError};
            use unlaxer_runtime::{json_string, Diagnostics, Memoization, ParseContext, ParseOptions};
            type Seen = Arc<Mutex<Vec<(usize, usize, String)>>>;
            fn decode(encoded: &str) -> String {
                let bytes = (0..encoded.len()).step_by(2)
                    .map(|i| u8::from_str_radix(&encoded[i..i + 2], 16).unwrap()).collect();
                String::from_utf8(bytes).unwrap()
            }
            fn digits(text: &str) -> Option<u64> {
                (!text.is_empty() && text.len() <= 18 && text.bytes().all(|b| b.is_ascii_digit()))
                    .then(|| text.parse().unwrap())
            }
            fn evaluate(rule: &str, candidate: &Candidate<'_>) -> Result<bool, PredicateError> {
                let (kind, arguments) = rule.split_once(':').unwrap_or((rule, ""));
                let text = candidate.text;
                match kind {
                    "const" => Ok(arguments == "true"),
                    "range" => {
                        let (low, high) = arguments.split_once(':').unwrap();
                        Ok(digits(text).is_some_and(|value| value >= low.parse().unwrap() && value <= high.parse().unwrap()))
                    }
                    "rangeStripped" => evaluate(&format!("range:{arguments}"), &Candidate { text: text.trim(), span: candidate.span }),
                    "oneOf" => Ok(arguments.split('|').any(|value| value == text)),
                    "ordered" => {
                        let (low, high) = text.strip_suffix('!').unwrap_or(text).split_once('-').unwrap();
                        Ok(matches!((digits(low), digits(high)), (Some(low), Some(high)) if low <= high))
                    }
                    "even" => Ok(digits(text).is_some_and(|value| value % 2 == 0)),
                    "error" => Err(PredicateError),
                    "panic" => panic!("probe panic"),
                    _ => unreachable!("{rule}"),
                }
            }
            fn bindings(spec: &str, seen: &Seen) -> Bindings {
                let mut bindings = Bindings::new();
                for entry in spec.split(';').filter(|entry| !entry.is_empty()) {
                    let (name, rule) = entry.split_once('=').unwrap();
                    let (rule, seen) = (rule.to_owned(), Arc::clone(seen));
                    bindings = bindings.with_fallible(name, move |candidate| {
                        seen.lock().unwrap().push((candidate.span.start, candidate.span.end, candidate.text.to_owned()));
                        evaluate(&rule, candidate)
                    }).unwrap();
                }
                bindings
            }
            fn full(input: &str, bindings: &Option<Bindings>, options: ParseOptions) -> Result<unlaxer_runtime::Tree, unlaxer_runtime::ParseDiagnostic> {
                match bindings {
                    Some(bindings) => generated::parser::parse_tree_detailed_with_semantic_predicates(input, bindings, options),
                    None => generated::parser::parse_tree_detailed_with_options(input, options),
                }
            }
            fn prefix(input: &str, bindings: &Option<Bindings>, options: ParseOptions) -> (bool, usize, usize) {
                let mut context = match bindings {
                    Some(bindings) => ParseContext::with_semantic_predicates(input, bindings, options),
                    None => ParseContext::with_options(input, options),
                };
                let result = generated::parser::parse_context(&mut context);
                if let Ok(matched) = &result {
                    let expected: String = input.chars().take(context.position()).collect();
                    assert_eq!(context.text(matched.span), Some(expected.as_str()));
                }
                (result.is_ok(), context.position(), context.matched_position())
            }
            fn main() {
                let default_hook = std::panic::take_hook();
                std::panic::set_hook(Box::new(move |info| {
                    if info.payload().downcast_ref::<&str>() != Some(&"probe panic") {
                        default_hook(info);
                    }
                }));
                for line in io::stdin().lock().lines() {
                    let line = line.unwrap();
                    let (input, spec) = line.split_once(' ').unwrap();
                    let input = decode(input);
                    let seen: Seen = Arc::default();
                    let bindings = (spec != "-").then(|| bindings(&decode(spec), &seen));
                    let (ok, position, matched) = prefix(&input, &bindings, ParseOptions::default());
                    assert_eq!((ok, position, matched), prefix(&input, &bindings,
                        ParseOptions::with_memoization(Memoization::SafeFailures)));
                    seen.lock().unwrap().clear();
                    let reference = full(&input, &bindings, ParseOptions::default());
                    let mut candidates = seen.lock().unwrap().clone();
                    candidates.sort();
                    candidates.dedup();
                    let diagnostic = match &reference { Ok(_) => "null".to_owned(), Err(error) => error.canonical_json() };
                    let ast = match &reference {
                        Ok(tree) => generated::mapper::map(tree).unwrap().canonical_json(),
                        Err(_) => "null".to_owned(),
                    };
                    for options in [
                        ParseOptions::default().with_diagnostics(Diagnostics::Detailed),
                        ParseOptions::default().with_diagnostics(Diagnostics::DetailedOnFailure),
                        ParseOptions::with_memoization(Memoization::SafeFailures),
                        ParseOptions::with_memoization(Memoization::SafeFailures).with_diagnostics(Diagnostics::DetailedOnFailure),
                    ] {
                        let repeated = full(&input, &bindings, options);
                        let repeated_diagnostic = match &repeated { Ok(_) => "null".to_owned(), Err(error) => error.canonical_json() };
                        assert_eq!(diagnostic, repeated_diagnostic, "{options:?}");
                        if let Ok(tree) = repeated {
                            assert_eq!(ast, generated::mapper::map(&tree).unwrap().canonical_json(), "{options:?}");
                        }
                    }
                    let candidates = candidates.iter()
                        .map(|(start, end, text)| format!("[{},{},{}]", json_string(text), start, end))
                        .collect::<Vec<_>>().join(",");
                    println!(r#"{{"prefix":[{},{},{}],"candidates":[{}],"ast":{},"diagnostic":{}}}"#,
                        ok, position, matched, candidates, ast, diagnostic);
                }
            }
            """;
    }

    private record ProcessResult(int code, String output) {}

    private ProcessResult run(List<String> command, String input, boolean withoutJava) throws Exception {
        Path log = temporary.newFile().toPath();
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        if (withoutJava) {
            builder.environment().put("PATH", "");
            builder.environment().put("JAVA_HOME", "/nonexistent-unlaxer-java");
        }
        Process process = builder.start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(300, TimeUnit.SECONDS)) {
                terminateProcessTree(process);
                fail("process timed out: " + command);
            }
            return new ProcessResult(process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) terminateProcessTree(process);
        }
    }

    private void terminateProcessTree(Process process) throws InterruptedException {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (int i = descendants.size() - 1; i >= 0; i--) descendants.get(i).destroy();
        process.destroy();
        process.waitFor(2, TimeUnit.SECONDS);
        for (int i = descendants.size() - 1; i >= 0; i--) {
            ProcessHandle descendant = descendants.get(i);
            if (descendant.isAlive()) descendant.destroyForcibly();
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    private void success(ProcessResult result) {
        assertEquals(result.output(), 0, result.code());
    }
}
