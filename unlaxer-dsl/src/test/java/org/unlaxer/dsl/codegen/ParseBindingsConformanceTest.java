package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import com.google.gson.*;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.parser.combinator.Not;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.PortabilityCheck;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;

/** One grammar and authored observations, compiled and executed in both hosts. */
public class ParseBindingsConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();
    private final Path example = repo.resolve("examples/parse-composition");

    @Test public void dictionariesComposeWithGeneratedParsers() throws Exception {
        boolean rust = Boolean.getBoolean("rustConformance");
        if (!rust) System.out.println("[assumption] Rust parse bindings require -DrustConformance=true");
        JsonArray corpus = JsonParser.parseString(Files.readString(
            repo.resolve("spec-corpus/parse-bindings/runtime.json"))).getAsJsonArray();
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        Path nativeGenerator = repo.resolve("rust/target/debug/unlaxer");
        if (rust) {
            success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
                repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), "", false));
            success(run(List.of("cargo", "build", "--locked", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(),
                "-p", "unlaxer-generator"), "", false));
        }
        List<String> report = new ArrayList<>(List.of("fixture\tcase\texpected\tjava\trust"));
        for (var fixtureValue : corpus) {
            var fixture = fixtureValue.getAsJsonObject();
            String source = Files.readString(example.resolve("dictionary.ubnf"))
                .replace("WORD @value END;", fixture.get("body").getAsString() + ";");
            assertTrue(PortabilityCheck.check(source).portable());
            var grammar = UBNFMapper.parse(source).grammars().get(0);
            List<JsonElement> observations = List.of();
            if (rust) {
                Path folder = temporary.newFolder().toPath();
                Path ubnf = folder.resolve("Dictionary.ubnf"); Files.writeString(ubnf, source);
                Path generated = folder.resolve("generated");
                success(run(List.of(nativeGenerator.toString(), "generate", "--grammar", ubnf.toString(),
                    "--output", generated.toString()), "", true));
                for (var file : new RustBackend().generate(grammar)) assertEquals(file.relativePath(), file.content(),
                    Files.readString(generated.resolve(file.relativePath())));
                Files.copy(example.resolve("rust/dictionary.rs"), folder.resolve("dictionary.rs"));
                Files.writeString(folder.resolve("main.rs"), rustProbe(fixture.getAsJsonArray("cases")));
                success(run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + library,
                    folder.resolve("main.rs").toString(), "-o", folder.resolve("probe").toString()), "", false));
                var output = run(List.of(folder.resolve("probe").toString()), "", true); success(output);
                observations = output.output().lines().map(JsonParser::parseString).toList();
                assertEquals(fixture.getAsJsonArray("cases").size(), observations.size());
            }
            try (var loader = compileJava(grammar)) {
                var parser = (Parser) loader.loadClass("example.bindings.DictionaryParsers").getMethod("getRootParser").invoke(null);
                var adapter = (Parser) loader.loadClass("example.bindings.DictionaryParser").getConstructor().newInstance();
                var mapper = loader.loadClass("example.bindings.DictionaryMapper");
                int index = 0;
                for (var rowValue : fixture.getAsJsonArray("cases")) {
                    var row = rowValue.getAsJsonObject();
                    String input = row.get("input").getAsString();
                    Map<String, List<String>> bindings = new HashMap<>();
                    row.getAsJsonObject("bindings").entrySet().forEach(e -> bindings.put(e.getKey(), new ArrayList<>(
                        e.getValue().getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList())));
                    JsonObject actual = new JsonObject();
                    try (var context = ParseContext.withBindings(StringSource.createRootSource(input), bindings, ParseOptions.DEFAULT)) {
                        // Mutating both containers after creation must not affect this session.
                        bindings.values().forEach(List::clear); bindings.clear();
                        JsonArray ahead = new JsonArray();
                        ahead.add(new Not(adapter).parse(context).isSucceeded());
                        ahead.add(context.position()); ahead.add(context.matchedPosition()); actual.add("ahead", ahead);
                        var parsed = parser.parse(context);
                        JsonArray prefix = new JsonArray(); prefix.add(parsed.isSucceeded());
                        prefix.add(context.position()); prefix.add(context.matchedPosition()); actual.add("prefix", prefix);
                        actual.add("ast", JsonNull.INSTANCE);
                        JsonArray values = new JsonArray(); actual.add("values", values);
                        if (parsed.isSucceeded()) {
                            var token = parsed.getRootToken(false);
                            for (var committed : context.getCurrent().getTokens()) if (committed.parser == parser) { token = committed; break; }
                            var mapped = mapper.getMethod("mapParsedTokenWithSourceMap", org.unlaxer.Token.class).invoke(null, token);
                            actual.add("ast", canonical(mapped.getClass().getMethod("ast").invoke(mapped), mapped));
                            captures(token, context, values);
                        }
                    }
                    var expected = row.getAsJsonObject("expected");
                    String label = fixture.get("name") + "/" + row.get("name");
                    assertEquals(label + " Java", expected, actual);
                    if (rust) {
                        assertEquals(label + " Rust", expected, observations.get(index));
                        report.add(label + "\t" + row.get("name") + "\t" + expected + "\t" + actual + "\t" + observations.get(index));
                    }
                    index++;
                }
            }
        }
        if (rust) Files.write(Path.of("target/rust-parse-bindings.tsv"), report);
    }

    private void captures(org.unlaxer.Token token, ParseContext context, JsonArray output) throws Exception {
        if (token.parser.getClass().getSimpleName().equals("__CaptureSite")) {
            int start = token.source.offsetFromRoot().value();
            int end = start + token.source.codePointLength().value();
            String raw = token.source.sourceAsString();
            var value = new JsonObject(); value.addProperty("raw", raw);
            List<String> normalized = context.bindingValues("normalized." + raw);
            value.addProperty("normalized", normalized.isEmpty() ? raw : normalized.get(0));
            JsonArray tags = new JsonArray(); context.bindingValues("tags." + raw).forEach(tags::add); value.add("tags", tags);
            JsonArray span = new JsonArray(); span.add(start); span.add(end); value.add("span", span);
            output.add(value);
        }
        for (var child : token.getOriginalChildren()) captures(child, context, output);
    }

    private String owned(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return "String::from_utf8(vec![" + java.util.stream.IntStream.range(0, bytes.length)
            .mapToObj(i -> Integer.toString(Byte.toUnsignedInt(bytes[i])))
            .collect(java.util.stream.Collectors.joining(",")) + "]).unwrap()";
    }
    private String rustProbe(JsonArray cases) {
        StringBuilder calls = new StringBuilder();
        for (var entry : cases) {
            var row = entry.getAsJsonObject();
            calls.append("{ let source = ").append(owned(row.get("input").getAsString())).append("; let mut bindings = BTreeMap::new();\n");
            for (var e : row.getAsJsonObject("bindings").entrySet()) {
                calls.append("bindings.insert(").append(owned(e.getKey())).append(", vec![");
                for (var value : e.getValue().getAsJsonArray()) calls.append(owned(value.getAsString())).append(',');
                calls.append("]);\n");
            }
            calls.append("observe(&source, bindings); }\n");
        }
        return """
            mod generated;
            mod dictionary;
            use dictionary::word_token;
            use std::collections::BTreeMap;
            use unlaxer_runtime::{ParseContext, ParseOptions, Expr, json_string};
            fn observe(source: &str, mut bindings: BTreeMap<String, Vec<String>>) {
                let mut context = ParseContext::with_bindings(source, bindings.clone(), ParseOptions::default());
                bindings.values_mut().for_each(Vec::clear); bindings.clear();
                let ahead = context.parse(&Expr::Custom(word_token).not_ahead()).is_ok();
                let ahead = format!("[{},{},{}]", ahead, context.position(), context.matched_position());
                let result = generated::parser::parse_context(&mut context);
                let prefix = format!("[{},{},{}]", result.is_ok(), context.position(), context.matched_position());
                let mut values = vec![];
                let ast = if let Ok(result) = result {
                    let tree = context.tree(result.root_node().unwrap()).unwrap();
                    for capture in &tree.nodes[tree.root].captures {
                        let raw = context.text(capture.span).unwrap();
                        let normalized = context.binding_values(&format!("normalized.{raw}"));
                        let normalized = normalized.first().map(String::as_str).unwrap_or(raw);
                        let tags = context.binding_values(&format!("tags.{raw}")).iter().map(|t| json_string(t)).collect::<Vec<_>>().join(",");
                        values.push(format!(r#"{{"raw":{},"normalized":{},"tags":[{}],"span":[{},{}]}}"#,
                            json_string(raw), json_string(normalized), tags, capture.span.start, capture.span.end));
                    }
                    generated::mapper::map(&tree).unwrap().canonical_json()
                } else { "null".into() };
                println!(r#"{{"ahead":{},"prefix":{},"ast":{},"values":[{}]}}"#, ahead, prefix, ast, values.join(","));
            }
            fn main() {
            """ + calls + "}\n";
    }
    private URLClassLoader compileJava(GrammarDecl grammar) throws Exception {
        List<JavaFileObject> units = new ArrayList<>();
        for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
            var source = generator.generate(grammar);
            units.add(new SimpleJavaFileObject(URI.create("string:///" + source.packageName().replace('.', '/')
                + "/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignored) { return source.source(); }
            });
        }
        String provider = Files.readString(example.resolve("java/DictionaryParser.java"));
        units.add(new SimpleJavaFileObject(URI.create("string:///example/bindings/DictionaryParser.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return provider; }
        });
        Path output = temporary.newFolder().toPath();
        var compiler = ToolProvider.getSystemJavaCompiler();
        StringWriter diagnostics = new StringWriter();
        try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(diagnostics, files, null,
                List.of("--release", "17", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()),
                null, units).call();
            assertTrue(diagnostics.toString(), compiled);
        }
        return new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader());
    }

    private JsonElement canonical(Object value, Object mapped) throws Exception {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Optional<?> optional) return canonical(optional.orElse(null), mapped);
        if (value instanceof List<?> values) {
            JsonArray result = new JsonArray();
            for (Object item : values) result.add(canonical(item, mapped));
            return result;
        }
        JsonObject result = new JsonObject();
        result.addProperty("type", value.getClass().getSimpleName());
        int[] span = (int[]) ((Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class)
            .invoke(mapped, value)).orElseThrow();
        JsonArray position = new JsonArray();
        position.add(span[0]); position.add(span[1]); result.add("span", position);
        JsonObject fields = new JsonObject();
        for (var component : value.getClass().getRecordComponents()) fields.add(component.getName(),
            canonical(component.getAccessor().invoke(value), mapped));
        result.add("fields", fields);
        return result;
    }

    private record Run(int code, String output) {}
    private Run run(List<String> command, String input, boolean noJava) throws Exception {
        Path log = temporary.newFile().toPath();
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        if (noJava) { builder.environment().put("PATH", ""); builder.environment().put("JAVA_HOME", "/missing-java"); }
        Process process = builder.start();
        try {
            try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            assertTrue("process timeout: " + command, process.waitFor(60, TimeUnit.SECONDS));
            return new Run(process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
    private void success(Run result) { assertEquals(result.output(), 0, result.code()); }
}
