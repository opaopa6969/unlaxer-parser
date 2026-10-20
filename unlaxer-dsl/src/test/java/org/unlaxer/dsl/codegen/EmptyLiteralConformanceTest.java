package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.Gson;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;

/**
 * Independent UBNF {@code ''} corpus (#713): Java generated parsers, then the Rust files from
 * the Java and native frontends, each compiled and executed separately.
 */
public class EmptyLiteralConformanceTest {
    private static final String PACKAGE = "org.example.emptyliteral";
    private static final List<String> KEYS = List.of("prefix", "classic", "diagnostic", "ast", "cst");

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void javaGeneratedParsersMatchTheSharedOracle() throws Exception {
        var mismatches = new ArrayList<String>();
        var report = new ArrayList<>(List.of("fixture\tcase\tinput_json\tjava"));
        for (JsonElement fixtureElement : corpus()) {
            JsonObject fixture = fixtureElement.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            GrammarDecl grammar = UBNFMapper.parse(fixture.get("grammar").getAsString()).grammars().get(0);
            GrammarValidator.validateOrThrow(grammar);
            try (URLClassLoader loader = compileJava(grammar)) {
                Class<?> parsers = loader.loadClass(PACKAGE + "." + name + "Parsers");
                Parser parser = (Parser) parsers.getMethod("getRootParser").invoke(null);
                Class<?> mapper = loader.loadClass(PACKAGE + "." + name + "Mapper");
                Set<String> rules = grammar.rules().stream().map(rule -> rule.name() + "Parser")
                    .collect(Collectors.toSet());
                for (JsonElement caseElement : fixture.getAsJsonArray("cases")) {
                    JsonObject row = caseElement.getAsJsonObject();
                    String input = row.get("input").getAsString();
                    JsonObject actual = javaObservation(parser, parsers, rules, mapper, input);
                    compare(name + "/" + row.get("id").getAsString() + " Java", row, true, actual, mismatches);
                    report.add(name + "\t" + row.get("id").getAsString() + "\t" + row.get("input") + "\t" + actual);
                }
            }
        }
        write("empty-literal-java.tsv", report);
        assertEquals(String.join("\n", mismatches), List.of(), mismatches);
    }

    @Test public void bothRustFrontendsMatchTheSharedOracle() throws Exception {
        if (false == Boolean.getBoolean("rustConformance")) {
            System.out.println("[assumption] EmptyLiteralConformanceTest Rust hosts skipped:"
                + " requires -DrustConformance=true and rustc/cargo on PATH.");
        }
        assumeTrue("enable with -DrustConformance=true (requires rustc/cargo)",
            Boolean.getBoolean("rustConformance"));
        Path root = temporary.getRoot().toPath();
        Path runtime = root.resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()), "", false));
        Path nativeTarget = root.resolve("native-target");
        success(run(List.of("cargo", "build", "--locked", "--manifest-path",
            repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator", "--target-dir",
            nativeTarget.toString()), "", false));
        Path nativeGenerator = nativeTarget.resolve("debug/unlaxer").toAbsolutePath();

        var mismatches = new ArrayList<String>();
        var report = new ArrayList<>(List.of("fixture\tcase\tinput_json\tjava_host_rust\tnative_host_rust"));
        int index = 0;
        for (JsonElement fixtureElement : corpus()) {
            JsonObject fixture = fixtureElement.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            String source = fixture.get("grammar").getAsString();
            GrammarDecl grammar = UBNFMapper.parse(source).grammars().get(0);
            List<RustBackend.GeneratedFile> javaFrontend = new RustBackend().generate(grammar);
            assertEquals(name + " five Rust modules", 5, javaFrontend.size());
            Path directory = Files.createDirectory(root.resolve("fixture-" + index++));
            Path ubnf = directory.resolve(name + ".ubnf");
            Files.writeString(ubnf, source);
            Path nativeGenerated = directory.resolve("native-generated");
            success(run(List.of(nativeGenerator.toString(), "generate", "--grammar", ubnf.toString(),
                "--output", nativeGenerated.toString()), "", true));
            Path generated = Files.createDirectory(directory.resolve("generated"));
            for (var file : javaFrontend) {
                assertArrayEquals(name + " native frontend byte parity: " + file.relativePath(),
                    file.content().getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(nativeGenerated.resolve(file.relativePath())));
                Files.writeString(generated.resolve(file.relativePath()), file.content());
            }
            // Byte parity is necessary but not sufficient: each host's files run in their own binary.
            List<List<String>> hosts = new ArrayList<>();
            for (String host : List.of("generated", "native-generated")) {
                Path main = directory.resolve(host + "-main.rs");
                Files.writeString(main, rustProbe().replace("mod generated;",
                    "#[path = \"" + host + "/mod.rs\"] mod generated;"));
                Path binary = directory.resolve(host + "-probe");
                success(run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + runtime,
                    main.toString(), "-o", binary.toString()), "", false));
                JsonArray cases = fixture.getAsJsonArray("cases");
                String framed = cases.asList().stream().map(row -> HexFormat.of().formatHex(
                    row.getAsJsonObject().get("input").getAsString().getBytes(StandardCharsets.UTF_8)) + "\n")
                    .collect(Collectors.joining());
                ProcessResult result = run(List.of(binary.toString()), framed, false);
                success(result);
                List<String> lines = result.output().lines().toList();
                assertEquals(name + " " + host + " result count", cases.size(), lines.size());
                hosts.add(lines);
            }
            JsonArray cases = fixture.getAsJsonArray("cases");
            for (int i = 0; i < cases.size(); i++) {
                JsonObject row = cases.get(i).getAsJsonObject();
                String label = name + "/" + row.get("id").getAsString();
                compare(label + " Java-host Rust", row, false,
                    JsonParser.parseString(hosts.get(0).get(i)).getAsJsonObject(), mismatches);
                compare(label + " native-host Rust", row, false,
                    JsonParser.parseString(hosts.get(1).get(i)).getAsJsonObject(), mismatches);
                report.add(name + "\t" + row.get("id").getAsString() + "\t" + row.get("input") + "\t"
                    + hosts.get(0).get(i) + "\t" + hosts.get(1).get(i));
            }
        }
        write("rust-empty-literal.tsv", report);
        assertEquals(String.join("\n", mismatches), List.of(), mismatches);
    }

    private JsonArray corpus() throws Exception {
        return JsonParser.parseString(Files.readString(repo.resolve("spec-corpus/empty-literal/corpus.json")))
            .getAsJsonArray();
    }

    /** A host-specific key records an existing display difference; it never relaxes the shared one. */
    private void compare(String label, JsonObject row, boolean java, JsonObject actual, List<String> mismatches) {
        if (actual.has("variance")) mismatches.add(label + " memo/diagnostics variance: " + actual.get("variance"));
        for (String key : KEYS) {
            String override = (java ? "java" : "rust") + Character.toUpperCase(key.charAt(0)) + key.substring(1);
            JsonElement expected = row.has(override) ? row.get(override) : row.get(key);
            if (expected == null) {
                mismatches.add(label + " missing oracle " + key + "; actual " + actual.get(key));
            } else if (false == expected.equals(actual.get(key))) {
                mismatches.add(label + " " + key + ": expected " + expected + " actual " + actual.get(key));
            }
        }
    }

    private JsonObject javaObservation(Parser parser, Class<?> parsers, Set<String> rules, Class<?> mapper,
            String input) throws Exception {
        var result = new JsonObject();
        var variance = new ArrayList<String>();
        JsonArray prefix = null;
        JsonArray classic = null;
        for (Memoization memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES)) {
            ParseOptions options = ParseOptions.withMemoization(memo);
            JsonArray memoPrefix;
            try (var context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
                memoPrefix = cursors(parser.parse(context).isSucceeded(), context);
            }
            var memoClassic = new JsonArray();
            for (TokenKind kind : List.of(TokenKind.consumed, TokenKind.matchOnly)) {
                for (boolean invert : new boolean[] {false, true}) {
                    try (var context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
                        memoClassic.add(cursors(parser.parse(context, kind, invert).isSucceeded(), context));
                    }
                }
            }
            if (prefix == null) {
                prefix = memoPrefix;
                classic = memoClassic;
            } else if (false == prefix.equals(memoPrefix) || false == classic.equals(memoClassic)) {
                variance.add("memo " + memo + " cursors " + memoPrefix + " " + memoClassic);
            }
        }
        result.add("prefix", prefix);
        result.add("classic", classic);
        JsonElement diagnostic = null;
        for (Memoization memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES)) {
            for (ParseOptions.Diagnostics policy : ParseOptions.Diagnostics.values()) {
                ParseOptions options = ParseOptions.withMemoization(memo).withDiagnostics(policy);
                Optional<?> found = (Optional<?>) mapper.getMethod("diagnose", String.class, ParseOptions.class)
                    .invoke(null, input, options);
                JsonElement value = found.isPresent() ? diagnosticJson(found.get()) : JsonNull.INSTANCE;
                if (diagnostic == null) diagnostic = value;
                else if (false == diagnostic.equals(value)) variance.add(memo + "/" + policy + " " + value);
            }
        }
        result.add("diagnostic", diagnostic);
        JsonElement ast = JsonNull.INSTANCE;
        JsonElement cst = JsonNull.INSTANCE;
        if (diagnostic.isJsonNull()) {
            Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
            ast = canonical(mapped.getClass().getMethod("ast").invoke(mapped), mapped);
            try (var context = new ParseContext(StringSource.createRootSource(input))) {
                List<String> nodes = cst(parser.parse(context).getRootToken(), parsers, rules);
                cst = new JsonPrimitive(String.join(",", nodes));
            }
        }
        result.add("ast", ast);
        result.add("cst", cst);
        if (false == variance.isEmpty()) result.addProperty("variance", String.join("; ", variance));
        return result;
    }

    private static JsonArray cursors(boolean succeeded, ParseContext context) {
        var result = new JsonArray();
        result.add(succeeded);
        result.add(context.getConsumedPosition().value());
        result.add(context.getMatchedPosition().value());
        return result;
    }

    /** Rule-level CST: rule tokens with code-point spans; helper and terminal tokens are transparent. */
    private static List<String> cst(Token token, Class<?> parsers, Set<String> rules) {
        var children = new ArrayList<String>();
        for (Token child : token.getOriginalChildren()) children.addAll(cst(child, parsers, rules));
        Class<?> type = token.parser.getClass();
        if (type.getEnclosingClass() != parsers || false == rules.contains(type.getSimpleName())) return children;
        int start = token.source.offsetFromRoot().value();
        String name = type.getSimpleName();
        String node = name.substring(0, name.length() - "Parser".length()) + "[" + start + ","
            + (start + token.source.codePointLength().value()) + ")";
        return List.of(children.isEmpty() ? node : node + "{" + String.join(",", children) + "}");
    }

    private static JsonObject diagnosticJson(Object value) throws Exception {
        var result = new JsonObject();
        for (String property : List.of("kind", "offset", "expected", "farthestOffset", "farthestExpected")) {
            result.add(property, new Gson().toJsonTree(value.getClass().getMethod(property).invoke(value)));
        }
        return result;
    }

    private static JsonElement canonical(Object value, Object mapped) throws Exception {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Optional<?> optional) return canonical(optional.orElse(null), mapped);
        if (value instanceof List<?> values) {
            var array = new JsonArray();
            for (Object item : values) array.add(canonical(item, mapped));
            return array;
        }
        var node = new JsonObject();
        node.addProperty("type", value.getClass().getSimpleName());
        int[] span = (int[]) ((Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class)
            .invoke(mapped, value)).orElseThrow();
        var pair = new JsonArray();
        pair.add(span[0]);
        pair.add(span[1]);
        node.add("span", pair);
        var fields = new JsonObject();
        for (var component : value.getClass().getRecordComponents()) {
            fields.add(component.getName(), canonical(component.getAccessor().invoke(value), mapped));
        }
        node.add("fields", fields);
        return node;
    }

    private URLClassLoader compileJava(GrammarDecl grammar) throws Exception {
        var sources = new ArrayList<GeneratedSource>();
        for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
            sources.add(generator.generate(grammar));
        }
        var units = sources.stream().map(source -> new SimpleJavaFileObject(
            URI.create("string:///" + source.packageName().replace('.', '/') + "/" + source.className() + ".java"),
            JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return source.source(); }
        }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("tests require a JDK, not a JRE", compiler);
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(null, manager, diagnostics, List.of("--release", "17",
                "-classpath", System.getProperty("java.class.path") + File.pathSeparator, "-d", output.toString()),
                null, units).call();
            assertTrue(diagnostics.getDiagnostics().toString(), compiled);
        }
        return new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader());
    }

    private static String rustProbe() {
        return """
            mod generated;
            use std::io::{self, BufRead};
            use unlaxer_runtime::token::TokenKind;
            use unlaxer_runtime::{json_string, Diagnostics, Memoization, ParseContext, ParseOptions, Tree};
            fn cursors(ok: bool, context: &ParseContext<'_>) -> String {
                format!("[{},{},{}]", ok, context.position(), context.matched_position())
            }
            // Rule-level CST: rule nodes with code-point spans; other nodes are transparent.
            fn cst(tree: &Tree, id: usize, out: &mut Vec<String>) {
                let node = &tree.nodes[id];
                let mut children = Vec::new();
                for &child in &node.children {
                    cst(tree, child, &mut children);
                }
                match generated::parser::grammar().get(node.rule) {
                    Some(rule) => {
                        let text = format!("{}[{},{})", rule.name, node.span.start, node.span.end);
                        out.push(if children.is_empty() { text } else { format!("{}{{{}}}", text, children.join(",")) });
                    }
                    None => out.extend(children),
                }
            }
            fn main() {
                let memos = [Memoization::Off, Memoization::SafeFailures];
                let modes = [(TokenKind::Consumed, false), (TokenKind::Consumed, true),
                    (TokenKind::MatchOnly, false), (TokenKind::MatchOnly, true)];
                for line in io::stdin().lock().lines() {
                    let encoded = line.unwrap();
                    let bytes = (0..encoded.len()).step_by(2)
                        .map(|i| u8::from_str_radix(&encoded[i..i + 2], 16).unwrap()).collect();
                    let input = String::from_utf8(bytes).unwrap();
                    let mut variance = Vec::new();
                    let mut cursor_results: Option<(String, String)> = None;
                    for memo in memos {
                        let options = ParseOptions::with_memoization(memo);
                        let mut context = ParseContext::with_options(&input, options);
                        let ok = generated::parser::parse_context(&mut context).is_ok();
                        let prefix = cursors(ok, &context);
                        let classic = modes.iter().map(|&(kind, invert)| {
                            let mut context = ParseContext::with_options(&input, options);
                            let ok = context.parse_with_token_mode(&generated::parser::GeneratedParser, kind, invert).is_ok();
                            cursors(ok, &context)
                        }).collect::<Vec<_>>().join(",");
                        match &cursor_results {
                            None => cursor_results = Some((prefix, classic)),
                            Some((p, c)) if *p != prefix || *c != classic =>
                                variance.push(format!("memo {:?} cursors {} {}", memo, prefix, classic)),
                            Some(_) => {}
                        }
                    }
                    let mut diagnostic: Option<String> = None;
                    for memo in memos {
                        for policy in [Diagnostics::Auto, Diagnostics::Detailed, Diagnostics::DetailedOnFailure] {
                            let options = ParseOptions::with_memoization(memo).with_diagnostics(policy);
                            let value = match generated::parser::parse_tree_detailed_with_options(&input, options) {
                                Ok(_) => "null".to_owned(),
                                Err(error) => error.canonical_json(),
                            };
                            match &diagnostic {
                                None => diagnostic = Some(value),
                                Some(first) if *first != value => variance.push(format!("{:?}/{:?} {}", memo, policy, value)),
                                Some(_) => {}
                            }
                        }
                    }
                    let (ast, cst_text) = match generated::parser::parse_tree_detailed(&input) {
                        Ok(tree) => {
                            let ast = generated::mapper::map(&tree).unwrap().canonical_json();
                            let mut nodes = Vec::new();
                            cst(&tree, tree.root, &mut nodes);
                            (ast, json_string(&nodes.join(",")))
                        }
                        Err(_) => ("null".to_owned(), "null".to_owned()),
                    };
                    let (prefix, classic) = cursor_results.unwrap();
                    let variance = if variance.is_empty() { String::new() }
                        else { format!(",\\"variance\\":{}", json_string(&variance.join("; "))) };
                    println!("{{\\"prefix\\":{},\\"classic\\":[{}],\\"diagnostic\\":{},\\"ast\\":{},\\"cst\\":{}{}}}",
                        prefix, classic, diagnostic.unwrap(), ast, cst_text, variance);
                }
            }
            """;
    }

    private static void write(String name, List<String> rows) throws Exception {
        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.write(target.resolve(name), rows, StandardCharsets.UTF_8);
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
            if (false == process.waitFor(300, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                fail("process timed out: " + command);
            }
            return new ProcessResult(process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void success(ProcessResult result) {
        assertEquals(result.output(), 0, result.code());
    }
}
