package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.*;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
import org.unlaxer.dsl.bootstrap.UBNFModuleLoader;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;

/** Generated lexical programs: independent Java/Rust codegen, cursors, AST spans and legacy oracles. */
public class DeclarativeTokenConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void authoringCatalogExamplesAgreeInJavaAndRust() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println(
            "[assumption] catalog conformance requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), "", false));
        success(run(List.of("cargo", "build", "--locked", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(),
            "-p", "unlaxer-generator"), "", false));
        Path nativeGenerator = repo.resolve("rust/target/debug/unlaxer");
        for (var item : JsonParser.parseString(org.unlaxer.dsl.tooling.AuthoringCatalog.json()).getAsJsonObject().getAsJsonArray("examples")) {
            var fixture = item.getAsJsonObject();
            Path directory = temporary.newFolder().toPath();
            Path source = directory.resolve("example.ubnf");
            Files.writeString(source, fixture.get("source").getAsString());
            String name = fixture.get("name").getAsString();
            GrammarDecl grammar = UBNFModuleLoader.load(source).grammars().get(0);
            assertTrue(name, PortabilityCheck.checkFile(source).portable());
            success(run(List.of(nativeGenerator.toString(), "generate", "--grammar", source.toString(),
                "--output", directory.resolve("generated").toString()), "", true));
            for (var file : new RustBackend().generate(grammar)) assertEquals(name + "/" + file.relativePath(),
                file.content(), Files.readString(directory.resolve("generated").resolve(file.relativePath())));
            Files.writeString(directory.resolve("main.rs"), rustProbe(false));
            Path binary = directory.resolve("probe");
            success(run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + library,
                directory.resolve("main.rs").toString(), "-o", binary.toString()), "", false));
            var cases = fixture.getAsJsonArray("cases");
            String inputs = String.join("\n", cases.asList().stream().map(row -> HexFormat.of().formatHex(
                row.getAsJsonObject().get("input").getAsString().getBytes(StandardCharsets.UTF_8))).toList()) + "\n";
            Run rust = run(List.of(binary.toString()), inputs, true);
            success(rust);
            var results = rust.output().lines().toList();
            assertEquals(name, cases.size(), results.size());
            try (URLClassLoader loader = compileJava(grammar)) {
                String prefix = "guide.demo." + grammar.name();
                Parser parser = (Parser) loader.loadClass(prefix + "Parsers").getMethod("getRootParser").invoke(null);
                Class<?> mapper = loader.loadClass(prefix + "Mapper");
                for (int i = 0; i < cases.size(); i++) {
                    var row = cases.get(i).getAsJsonObject();
                    String input = row.get("input").getAsString();
                    String label = name + "/" + row.get("input");
                    var actualRust = JsonParser.parseString(results.get(i)).getAsJsonObject();
                    JsonArray position = new JsonArray();
                    try (var context = new ParseContext(StringSource.createRootSource(input))) {
                        position.add(parser.parse(context).isSucceeded());
                        position.add(context.position()); position.add(context.matchedPosition());
                    }
                    assertEquals(label, position, actualRust.get("prefix"));
                    Optional<?> diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, input);
                    assertEquals(label, row.has("fields"), diagnostic.isEmpty());
                    JsonElement ast = JsonNull.INSTANCE;
                    if (row.has("fields")) {
                        Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
                        Object node = mapped.getClass().getMethod("ast").invoke(mapped);
                        ast = canonical(node, mapped);
                        assertEquals(label, row.get("fields"), ast.getAsJsonObject().get("fields"));
                        JsonArray span = new JsonArray(); span.add(0); span.add(input.codePointCount(0, input.length()));
                        assertEquals(label, span, ast.getAsJsonObject().get("span"));
                    }
                    assertEquals(label, ast, actualRust.get("ast"));
                }
            }
        }
    }

    @Test public void illustratedGuideEmbedsExactExampleFiles() throws Exception {
        String guide = Files.readString(repo.resolve("docs/ubnf-v2-illustrated-ja.md"));
        var examples = java.util.regex.Pattern.compile(
            "<!-- example: ([^\\n]+) -->\\n```ubnf\\n(.*?)\\n```",
            java.util.regex.Pattern.DOTALL).matcher(guide);
        var embedded = new java.util.HashSet<Path>();
        while (examples.find()) {
            Path file = repo.resolve("docs").resolve(examples.group(1)).normalize();
            assertTrue("duplicate example: " + file, embedded.add(file));
            assertEquals(file.toString(), Files.readString(file).strip(), examples.group(2));
        }
        try (var files = Files.walk(repo.resolve("docs/examples/ubnf-v2"))) {
            var grammars = files.filter(p -> p.toString().endsWith(".ubnf"))
                .collect(java.util.stream.Collectors.toSet());
            assertEquals("Every guide grammar must be embedded and kept in sync", grammars, embedded);
            assertEquals(6, embedded.size());
        }
    }

    @Test public void illustratedGuideExamplesAgreeInJavaAndRust() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println(
            "[assumption] illustrated guide conformance requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path fixtures = repo.resolve("docs/examples/ubnf-v2");
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), "", false));
        success(run(List.of("cargo", "build", "--locked", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(),
            "-p", "unlaxer-generator"), "", false));
        Path nativeGenerator = repo.resolve("rust/target/debug/unlaxer");
        var corpus = JsonParser.parseString(Files.readString(fixtures.resolve("cases.json"))).getAsJsonArray();
        for (var item : corpus) {
            var fixture = item.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            Path directory = temporary.newFolder().toPath();
            try (var paths = Files.walk(fixtures)) {
                for (Path from : paths.filter(Files::isRegularFile).toList()) {
                    Path to = directory.resolve(fixtures.relativize(from));
                    Files.createDirectories(to.getParent());
                    Files.copy(from, to);
                }
            }
            Path source = directory.resolve(fixture.get("file").getAsString());
            if (fixture.has("body")) Files.writeString(source, Files.readString(source).replace(
                "Root ::= num.NUMBER @text;", "Root ::= " + fixture.get("body").getAsString() + ";"));
            assertTrue(name, PortabilityCheck.checkFile(source).portable());
            GrammarDecl grammar = UBNFModuleLoader.load(source).grammars().get(0);
            success(run(List.of(nativeGenerator.toString(), "generate", "--grammar", source.toString(),
                "--output", directory.resolve("generated").toString()), "", true));
            for (var file : new RustBackend().generate(grammar)) assertEquals(name + "/" + file.relativePath(),
                file.content(), Files.readString(directory.resolve("generated").resolve(file.relativePath())));
            Files.writeString(directory.resolve("main.rs"), rustProbe(false));
            Path binary = directory.resolve("probe");
            success(run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + library,
                directory.resolve("main.rs").toString(), "-o", binary.toString()), "", false));
            var cases = fixture.getAsJsonArray("cases");
            String inputs = String.join("\n", cases.asList().stream().map(row -> HexFormat.of().formatHex(
                row.getAsJsonObject().get("input").getAsString().getBytes(StandardCharsets.UTF_8))).toList()) + "\n";
            Run rust = run(List.of(binary.toString()), inputs, true);
            success(rust);
            var results = rust.output().lines().toList();
            assertEquals(cases.size(), results.size());
            try (URLClassLoader loader = compileJava(grammar)) {
                String prefix = "guide.demo." + grammar.name();
                Parser parser = (Parser) loader.loadClass(prefix + "Parsers").getMethod("getRootParser").invoke(null);
                Class<?> mapper = loader.loadClass(prefix + "Mapper");
                for (int i = 0; i < cases.size(); i++) {
                    var row = cases.get(i).getAsJsonObject();
                    String text = row.get("input").getAsString();
                    String label = name + "/" + row.get("input");
                    var actualRust = JsonParser.parseString(results.get(i)).getAsJsonObject();
                    JsonArray position = new JsonArray();
                    try (var context = new ParseContext(StringSource.createRootSource(text))) {
                        position.add(parser.parse(context).isSucceeded());
                        position.add(context.position()); position.add(context.matchedPosition());
                    }
                    assertEquals(label, row.get("prefix"), position);
                    assertEquals(label, position, actualRust.get("prefix"));
                    Optional<?> diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, text);
                    assertEquals(label, row.has("fields"), diagnostic.isEmpty());
                    JsonElement ast = JsonNull.INSTANCE;
                    if (row.has("fields")) {
                        Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, text);
                        Object node = mapped.getClass().getMethod("ast").invoke(mapped);
                        ast = canonical(node, mapped);
                        JsonObject expected = new JsonObject();
                        expected.add("type", row.get("type"));
                        JsonArray span = new JsonArray(); span.add(0); span.add(text.codePointCount(0, text.length()));
                        expected.add("span", span);
                        expected.add("fields", row.get("fields"));
                        assertEquals(label, expected, ast);
                    }
                    assertEquals(label, ast, actualRust.get("ast"));
                }
            }
        }
    }

    @Test public void lexicalModulesHaveIndependentNamesCapturesAndPaths() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println(
            "[assumption] lexical module conformance requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path fixtures = repo.resolve("spec-corpus/lexical-modules");
        Path directory = temporary.newFolder().toPath();
        Path library = directory.resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), "", false));
        success(run(List.of("cargo", "build", "--locked", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(),
            "-p", "unlaxer-generator"), "", false));
        Path nativeGenerator = repo.resolve("rust/target/debug/unlaxer");
        GrammarDecl grammar = UBNFModuleLoader.load(fixtures.resolve("root.ubnf")).grammars().get(0);
        assertTrue(PortabilityCheck.checkFile(fixtures.resolve("root.ubnf")).portable());
        assertTrue(grammar.imports().isEmpty());
        assertFalse(grammar.settings().stream().anyMatch(s -> s.key().equals("whitespace")));
        success(run(List.of(nativeGenerator.toString(), "generate", "--grammar", fixtures.resolve("root.ubnf").toString(),
            "--output", directory.resolve("generated").toString()), "", true));
        for (var file : new RustBackend().generate(grammar)) assertEquals(file.relativePath(), file.content(),
            Files.readString(directory.resolve("generated").resolve(file.relativePath())));
        Files.writeString(directory.resolve("main.rs"), rustProbe(false));
        Path binary = directory.resolve("probe");
        success(run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + library,
            directory.resolve("main.rs").toString(), "-o", binary.toString()), "", false));
        var cases = JsonParser.parseString(Files.readString(fixtures.resolve("runtime.json"))).getAsJsonArray();
        String input = String.join("\n", cases.asList().stream().map(row -> HexFormat.of().formatHex(
            row.getAsJsonObject().get("input").getAsString().getBytes(StandardCharsets.UTF_8))).toList()) + "\n";
        Run rust = run(List.of(binary.toString()), input, true);
        success(rust);
        var rows = rust.output().lines().toList();
        assertEquals(cases.size(), rows.size());
        try (URLClassLoader loader = compileJava(grammar)) {
            Parser parser = (Parser) loader.loadClass("lexical.probe.LexicalProbeParsers").getMethod("getRootParser").invoke(null);
            Class<?> mapper = loader.loadClass("lexical.probe.LexicalProbeMapper");
            for (int i = 0; i < cases.size(); i++) {
                var row = cases.get(i).getAsJsonObject();
                String text = row.get("input").getAsString();
                var actual = JsonParser.parseString(rows.get(i)).getAsJsonObject();
                JsonArray prefix = new JsonArray();
                try (var context = new ParseContext(StringSource.createRootSource(text))) {
                    prefix.add(parser.parse(context).isSucceeded());
                    prefix.add(context.position()); prefix.add(context.matchedPosition());
                }
                assertEquals(text, row.get("prefix"), prefix);
                assertEquals(text, prefix, actual.get("prefix"));
                Optional<?> diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, text);
                assertEquals(text, row.has("value"), diagnostic.isEmpty());
                if (row.has("value")) {
                    Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, text);
                    Object node = mapped.getClass().getMethod("ast").invoke(mapped);
                    JsonObject ast = canonical(node, mapped).getAsJsonObject();
                    assertEquals(row.get("value"), ast.getAsJsonObject("fields").get("value"));
                    assertEquals(text, ast, actual.get("ast"));
                } else assertTrue(actual.get("ast").isJsonNull());
            }
        }
        for (var item : JsonParser.parseString(Files.readString(fixtures.resolve("invalid.json"))).getAsJsonArray()) {
            var fixture = item.getAsJsonObject();
            Path invalid = temporary.newFolder().toPath();
            for (var file : fixture.getAsJsonObject("files").entrySet())
                Files.writeString(invalid.resolve(file.getKey()), file.getValue().getAsString());
            assertThrows(fixture.get("name").getAsString(), Exception.class, () -> {
                var g = UBNFModuleLoader.load(invalid.resolve("root.ubnf")).grammars().get(0);
                new ParserGenerator().generate(g);
            });
            Run failed = run(List.of(nativeGenerator.toString(), "generate", "--grammar", invalid.resolve("root.ubnf").toString(),
                "--output", invalid.resolve("generated").toString()), "", true);
            assertNotEquals(fixture.get("name") + ": " + failed.output(), 0, failed.code());
            assertFalse(Files.exists(invalid.resolve("generated")));
            Run checked = run(List.of(nativeGenerator.toString(), "check", "--target", "rust", "--grammar",
                invalid.resolve("root.ubnf").toString()), "", true);
            assertEquals(checked.output(), 3, checked.code());
            var nativeReport = JsonParser.parseString(checked.output()).getAsJsonObject();
            var javaReport = PortabilityCheck.checkFile(invalid.resolve("root.ubnf"));
            assertEquals(fixture.get("name").toString(), javaReport.structure(), nativeReport.get("structure").getAsString());
            assertEquals(javaReport.diagnostics().size(), nativeReport.getAsJsonArray("diagnostics").size());
            for (int i = 0; i < javaReport.diagnostics().size(); i++) {
                var diagnostic = nativeReport.getAsJsonArray("diagnostics").get(i).getAsJsonObject();
                assertEquals(javaReport.diagnostics().get(i).code(), diagnostic.get("code").getAsString());
                assertTrue(diagnostic.get("span").isJsonNull());
            }
        }
    }

    @Test public void generatedProgramsPreserveCursorsCapturesSpansAndRollback() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println(
            "[assumption] DeclarativeTokenConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path fixtures = repo.resolve("spec-corpus/declarative-tokens");
        JsonArray corpus = JsonParser.parseString(Files.readString(fixtures.resolve("runtime.json"))).getAsJsonArray();
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), "", false));
        success(run(List.of("cargo", "build", "--locked", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(),
            "-p", "unlaxer-generator"), "", false));
        Path nativeGenerator = repo.resolve("rust/target/debug/unlaxer");
        List<String> report = new ArrayList<>(List.of("fixture\tinput_json\tprefix\tast\tresult"));
        for (var fixtureValue : corpus) {
            JsonObject fixture = fixtureValue.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            String source = Files.readString(fixtures.resolve("tokens.ubnf"))
                .replace("Root ::= NUMBER @value;", "Root ::= " + fixture.get("body").getAsString() + ";");
            if (fixture.has("whitespace")) source = source.replace("@package: lexical.probe",
                "@package: lexical.probe\n @whitespace: javaStyle");
            var portability = PortabilityCheck.check(source);
            assertTrue(name + ": " + portability, portability.portable());
            GrammarDecl grammar = UBNFMapper.parse(source).grammars().get(0);
            var emitted = new RustBackend().generate(grammar);
            Path directory = temporary.newFolder().toPath();
            Path input = directory.resolve("LexicalProbe.ubnf");
            Files.writeString(input, source);
            Path generated = directory.resolve("generated");
            success(run(List.of(nativeGenerator.toString(), "generate", "--grammar", input.toString(),
                "--output", generated.toString()), "", true));
            for (var file : emitted) assertEquals(name + "/" + file.relativePath(), file.content(),
                Files.readString(generated.resolve(file.relativePath())));
            Path main = directory.resolve("main.rs");
            Files.writeString(main, rustProbe(name.equals("fence")));
            Path binary = directory.resolve("probe");
            success(run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + library,
                main.toString(), "-o", binary.toString()), "", false));
            JsonArray cases = fixture.getAsJsonArray("cases");
            String inputs = String.join("\n", cases.asList().stream().map(row -> HexFormat.of().formatHex(
                row.getAsJsonObject().get("input").getAsString().getBytes(StandardCharsets.UTF_8))).toList()) + "\n";
            Run rust = run(List.of(binary.toString()), inputs, true);
            success(rust);
            List<String> results = rust.output().lines().toList();
            assertEquals(cases.size(), results.size());
            try (URLClassLoader loader = compileJava(grammar)) {
                Parser parser = (Parser) loader.loadClass("lexical.probe.LexicalProbeParsers").getMethod("getRootParser").invoke(null);
                Class<?> mapper = loader.loadClass("lexical.probe.LexicalProbeMapper");
                for (int index = 0; index < cases.size(); index++) {
                    JsonObject row = cases.get(index).getAsJsonObject();
                    String text = row.get("input").getAsString();
                    JsonObject actualRust = JsonParser.parseString(results.get(index)).getAsJsonObject();
                    JsonArray prefix = new JsonArray();
                    try (var context = new ParseContext(StringSource.createRootSource(text))) {
                        prefix.add(parser.parse(context).isSucceeded());
                        prefix.add(context.getConsumedPosition().value());
                        prefix.add(context.getMatchedPosition().value());
                    }
                    assertEquals(name + "/" + text, row.get("prefix"), prefix);
                    assertEquals(name + "/" + text, prefix, actualRust.get("prefix"));
                    if (fixture.has("oracle")) {
                        Parser oracle = switch (fixture.get("oracle").getAsString()) {
                            case "number" -> new org.unlaxer.parser.elementary.NumberParser();
                            case "identifier" -> new org.unlaxer.parser.clang.IdentifierParser();
                            case "string" -> new org.unlaxer.parser.combinator.Choice(
                                new org.unlaxer.parser.elementary.DoubleQuotedParser(),
                                new org.unlaxer.parser.elementary.SingleQuotedParser());
                            default -> throw new AssertionError("unknown oracle");
                        };
                        try (var context = new ParseContext(StringSource.createRootSource(text))) {
                            JsonArray legacy = new JsonArray();
                            legacy.add(oracle.parse(context).isSucceeded());
                            legacy.add(context.position()); legacy.add(context.matchedPosition());
                            assertEquals(name + "/" + text + " legacy", prefix, legacy);
                        }
                    }
                    boolean accepted = row.has("value");
                    Optional<?> diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, text);
                    assertEquals(accepted, diagnostic.isEmpty());
                    JsonElement ast = JsonNull.INSTANCE;
                    if (accepted) {
                        Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, text);
                        Object node = mapped.getClass().getMethod("ast").invoke(mapped);
                        ast = canonical(node, mapped);
                        JsonObject expected = new JsonObject();
                        expected.addProperty("type", "Value");
                        JsonArray span = new JsonArray(); span.add(0); span.add(text.codePointCount(0, text.length()));
                        expected.add("span", span);
                        JsonObject fields = new JsonObject();
                        fields.add("value", row.get("value"));
                        expected.add("fields", fields);
                        assertEquals(name + "/" + text, expected, ast);
                    }
                    assertEquals(name + "/" + text, ast, actualRust.get("ast"));
                    report.add(name + "\t" + row.get("input") + "\t" + prefix + "\t" + ast + "\tequal");
                }
            }
        }
        Files.write(Path.of("target/rust-declarative-token-runtime.tsv"), report);
        for (String declaration : List.of(
                "token T ::= MISSING;", "token T ::= T;", "token T ::= SAME_AS(x);",
                "token T ::= [CAPTURE(x,'a')] SAME_AS(x);",
                "token T ::= (CAPTURE(x,'a') | 'b') SAME_AS(x);",
                "token T ::= LOOKAHEAD(CAPTURE(x,'a')) SAME_AS(x);",
                "token T ::= {EOF};", "token T ::= CHAR_RANGE('z','a');",
                "token T ::= 'a'{3,2};", "token T ::= ();", "token T ::= 'a'{2147483648};")) {
            String source = "grammar Invalid { @ubnf: v2 " + declaration
                + " @root @mapping(Value,params=[value]) Root ::= T @value; }";
            assertThrows(declaration, IllegalArgumentException.class,
                () -> new ParserGenerator().generate(UBNFMapper.parse(source).grammars().get(0)));
            assertThrows(declaration, IllegalArgumentException.class,
                () -> new RustBackend().generate(UBNFMapper.parse(source).grammars().get(0)));
            Path directory = temporary.newFolder().toPath();
            Path grammar = directory.resolve("invalid.ubnf");
            Files.writeString(grammar, source);
            Run result = run(List.of(nativeGenerator.toString(), "generate", "--grammar", grammar.toString(),
                "--output", directory.resolve("generated").toString()), "", true);
            assertNotEquals(declaration + ": " + result.output(), 0, result.code());
            assertFalse(Files.exists(directory.resolve("generated")));
        }
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

    private String rustProbe(boolean fenceOracle) {
        return """
            mod generated;
            use std::io::{self, BufRead};
            fn main() {
                for line in io::stdin().lock().lines() {
                    let encoded = line.unwrap();
                    let bytes = (0..encoded.len()).step_by(2)
                        .map(|i| u8::from_str_radix(&encoded[i..i+2], 16).unwrap()).collect();
                    let input = String::from_utf8(bytes).unwrap();
                    let mut context = unlaxer_runtime::ParseContext::new(&input);
                    let ok = generated::parser::parse_context(&mut context).is_ok();
                    if FENCE_ORACLE {
                        let mut legacy = unlaxer_runtime::ParseContext::new(&input);
                        let old_ok = legacy.parse(&unlaxer_runtime::Expr::LongCodeBlock).is_ok();
                        assert_eq!((ok, context.position(), context.matched_position()),
                            (old_ok, legacy.position(), legacy.matched_position()), "{input:?}");
                    }
                    print!(r#"{{"prefix":[{},{},{}],"ast":"#, ok, context.position(), context.matched_position());
                    match generated::parser::parse_tree_detailed(&input) {
                        Ok(tree) => {
                            let ast = generated::mapper::map(&tree).unwrap();
                            drop(tree);
                            print!("{}", ast.canonical_json());
                        }
                        Err(_) => print!("null"),
                    }
                    println!("}}");
                }
            }
            """.replace("FENCE_ORACLE", Boolean.toString(fenceOracle));
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
