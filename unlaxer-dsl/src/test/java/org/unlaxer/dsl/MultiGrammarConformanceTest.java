package org.unlaxer.dsl;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.Token;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;

/** File-level CLI, code-point diagnostics and independently executed generated consumers (#714). */
public class MultiGrammarConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();
    private record Run(int code, String text) {}

    @org.junit.Before public void java21Reference() {
        assumeTrue("Java21 reference compiler", Runtime.version().feature() >= 21);
    }

    private JsonObject corpus() throws Exception {
        return JsonParser.parseString(Files.readString(repo.resolve("spec-corpus/multiple-grammars/corpus.json"))).getAsJsonObject();
    }

    @Test public void javaArtifactsCompileParseMapAndEvaluateTheSharedCorpus() throws Exception {
        var evidence = new ArrayList<>(List.of("fixture\tending\tgrammar\tobservation"));
        for (var element : corpus().getAsJsonArray("valid")) {
            var fixture = element.getAsJsonObject();
            for (boolean crlf : List.of(false, true)) {
                Path input = input(fixture, crlf);
                for (String observation : javaObservations(input, fixture))
                    evidence.add(fixture.get("name") + "\t" + crlf + "\t" + observation);
            }
        }
        Files.write(Path.of("target/multiple-grammars-java.tsv"), evidence);
    }

    @Test public void bothRustHostsCompileAndExecuteEveryGrammarIncludingProtocols() throws Exception {
        Path binary = binary();
        var evidence = new ArrayList<>(List.of("fixture\tending\tprotocols\thost\tobservation"));
        for (var element : corpus().getAsJsonArray("valid")) {
            var fixture = element.getAsJsonObject();
            for (boolean crlf : List.of(false, true)) {
                Path input = input(fixture, crlf);
                var checked = javaRun("check", "--target", "rust", "--grammar", input.toString());
                assertEquals(checked.text(), 0, checked.code());
                Run nativeCheck = nativeRun(binary, "check", "--target", "rust", "--grammar", input.toString());
                assertEquals(nativeCheck.text(), 0, nativeCheck.code());
                assertEquals(JsonParser.parseString(checked.text()), JsonParser.parseString(nativeCheck.text()));
                var expected = javaObservations(input, fixture);
                for (boolean protocols : List.of(false, true)) {
                    Path directory = temporary.newFolder().toPath();
                    Path javaOut = directory.resolve("java_host"), nativeOut = directory.resolve("native_host");
                    success(generate(null, input, javaOut, protocols, false));
                    success(generate(binary, input, nativeOut, protocols, false));
                    assertEquals(files(javaOut), files(nativeOut));
                    var parsed = UBNFMapper.parse(Files.readString(input));
                    assertEquals(1 + (protocols ? 7 : 5) * parsed.grammars().size(), files(javaOut).size());
                    // Nested contents are byte-for-byte the established single-grammar API output.
                    for (var grammar : parsed.grammars()) {
                        for (var artifact : new RustBackend().generateWithProtocols(grammar, protocols, protocols)) {
                            assertEquals(artifact.relativePath(), artifact.content(),
                                Files.readString(javaOut.resolve("grammar_" + grammar.name().toLowerCase(Locale.ROOT)).resolve(artifact.relativePath())));
                        }
                    }
                    success(generate(null, input, javaOut, protocols, true));
                    success(generate(binary, input, nativeOut, protocols, true));
                    for (String host : List.of("java_host", "native_host")) {
                        Files.writeString(directory.resolve("main.rs"), rustProbe(host, fixture));
                        Files.writeString(directory.resolve("Cargo.toml"), "[package]\nname=\"multiple-grammars-probe\"\nversion=\"0.1.0\"\nedition=\"2021\"\n"
                            + "[[bin]]\nname=\"probe\"\npath=\"main.rs\"\n[dependencies]\n"
                            + "unlaxer-runtime={path=\"" + repo.resolve("rust/unlaxer-runtime") + "\"}\n"
                            + "unlaxer-lsp={path=\"" + repo.resolve("rust/unlaxer-lsp") + "\"}\n"
                            + "unlaxer-dap={path=\"" + repo.resolve("rust/unlaxer-dap") + "\"}\n");
                        Run result = command(List.of("cargo", "run", "--quiet", "--offline", "--manifest-path", directory.resolve("Cargo.toml").toString(),
                            "--target-dir", repo.resolve("rust/target/multiple-grammars-probe").toString()), false);
                        success(result);
                        List<String> actual = result.text().lines().filter(s -> s.startsWith("OBS\t")).map(s -> s.substring(4)).toList();
                        assertEquals(fixture.toString(), expected, actual);
                        for (String observation : actual) evidence.add(fixture.get("name") + "\t" + crlf + "\t" + protocols + "\t" + host + "\t" + observation);
                    }
                }
            }
        }
        Files.write(Path.of("target/multiple-grammars-rust.tsv"), evidence);
    }

    @Test public void rejectionOriginsAndNoPartialOutputAgree() throws Exception {
        Path binary = binary();
        var evidence = new ArrayList<>(List.of("fixture\tending\tdiagnostics"));
        for (var element : corpus().getAsJsonArray("invalid")) {
            var fixture = element.getAsJsonObject();
            for (boolean crlf : List.of(false, true)) {
                Path input = input(fixture, crlf);
                Run javaCheck = javaRun("check", "--target", "rust", "--grammar", input.toString());
                Run nativeCheck = nativeRun(binary, "check", "--target", "rust", "--grammar", input.toString());
                assertEquals(javaCheck.text(), 3, javaCheck.code());
                assertEquals(nativeCheck.text(), 3, nativeCheck.code());
                JsonObject actual = JsonParser.parseString(javaCheck.text()).getAsJsonObject();
                assertEquals(actual, JsonParser.parseString(nativeCheck.text()));
                assertEquals(fixture.get("structure"), actual.get("structure"));
                JsonArray expected = fixture.getAsJsonArray("diagnostics").deepCopy();
                for (var d : expected) {
                    d.getAsJsonObject().addProperty("severity", "error");
                    if (crlf) {
                        var span = d.getAsJsonObject().getAsJsonObject("span");
                        for (String bound : List.of("start", "end")) {
                            int cp = span.get(bound).getAsInt();
                            String source = fixture.get("source").getAsString();
                            long extra = source.substring(0, source.offsetByCodePoints(0, cp)).chars().filter(c -> c == '\n').count();
                            span.addProperty(bound, cp + extra);
                        }
                    }
                }
                assertEquals(fixture.toString(), expected, actual.getAsJsonArray("diagnostics"));
                for (int host = 0; host < 3; host++) {
                    Path output = temporary.newFolder().toPath();
                    Files.writeString(output.resolve("sentinel"), "unchanged");
                    Run result = host == 0 ? javaRun("--grammar", input.toString(), "--output", output.toString(), "--generators", "AST,Parser,Mapper,Evaluator")
                        : generate(host == 1 ? null : binary, input, output, false, false);
                    assertNotEquals(fixture + "\n" + result.text(), 0, result.code());
                    assertEquals(Map.of("sentinel", "unchanged"), files(output));
                    if (fixture.get("name").getAsString().contains("collision") || fixture.get("name").getAsString().equals("duplicate-name")) {
                        var span = expected.get(0).getAsJsonObject().getAsJsonObject("span");
                        String subject = host == 0 ? "org.example.multiple."
                            + UBNFMapper.parse(Files.readString(input)).grammars().get(1).name() + "AST" : "grammar_alpha";
                        assertTrue(result.text(), result.text().contains("P-OUTPUT-COLLISION [" + span.get("start") + "," + span.get("end") + "): " + subject));
                    }
                }
                evidence.add(fixture.get("name") + "\t" + crlf + "\t" + actual.get("diagnostics"));
            }
        }
        Files.write(Path.of("target/multiple-grammars-rejections.tsv"), evidence);
    }

    @Test public void nestedOutputPreflightAndDriftNeverTouchEarlierArtifacts() throws Exception {
        Path binary = binary();
        Path input = input(corpus().getAsJsonArray("valid").get(0).getAsJsonObject(), false);
        for (Path host : Arrays.asList(null, binary)) {
            for (String conflict : List.of("handwritten", "directory", "symlink", "parent-file")) {
                Path out = temporary.newFolder().toPath();
                Files.writeString(out.resolve("mod.rs"), "// Generated by unlaxer RustBackend. old index\n");
                Path beta = out.resolve("grammar_beta");
                if (conflict.equals("parent-file")) Files.writeString(beta, "handwritten");
                else if (conflict.equals("symlink")) Files.createSymbolicLink(beta, temporary.newFolder().toPath());
                else {
                    Files.createDirectory(beta);
                    if (conflict.equals("directory")) Files.createDirectory(beta.resolve("parser.rs"));
                    else Files.writeString(beta.resolve("parser.rs"), "handwritten");
                }
                assertEquals(conflict, 4, generate(host, input, out, false, false).code());
                assertEquals("// Generated by unlaxer RustBackend. old index\n", Files.readString(out.resolve("mod.rs")));
                assertFalse(Files.exists(out.resolve("grammar_alpha")));
            }
            Path out = temporary.newFolder().toPath();
            success(generate(host, input, out, false, false));
            Path parser = out.resolve("grammar_beta/parser.rs");
            Files.writeString(parser, Files.readString(parser) + "// drift\n");
            Map<String, String> before = files(out);
            assertEquals(4, generate(host, input, out, false, true).code());
            assertEquals(before, files(out));
        }
    }

    @Test public void playgroundDoesNotSilentlySelectTheFirstGrammar() throws Exception {
        Path binary = binary();
        Path input = temporary.newFile().toPath();
        Files.writeString(input, "grammar A { @root @mapping(Value,params=[text]) Root ::= 'a' @text; }\n"
            + "grammar B { @root @mapping(Value,params=[text]) Root ::= 'b' @text; }\n");
        for (Path host : Arrays.asList(null, binary)) {
            Path output = temporary.getRoot().toPath().resolve(host == null ? "java-playground" : "native-playground");
            String[] args = {"playground", "--grammar", input.toString(), "--output", output.toString()};
            Run result = host == null ? javaRun(args) : nativeRun(host, args);
            assertEquals(result.text(), 3, result.code());
            assertTrue(result.text(), result.text().contains("playground requires exactly one grammar"));
            assertFalse(Files.exists(output));
        }
    }

    @Test public void impactRemainsExplicitlySingleGrammar() throws Exception {
        Path binary = binary();
        Path input = input(corpus().getAsJsonArray("valid").get(0).getAsJsonObject(), false);
        for (String target : List.of("java", "rust")) {
            Run result = javaRun("impact", "--target", target, "--before", input.toString(), "--after", input.toString());
            assertEquals(3, result.code());
            assertEquals("I-SCHEMA", JsonParser.parseString(result.text()).getAsJsonObject().getAsJsonArray("diagnostics").get(0).getAsJsonObject().get("code").getAsString());
        }
        Run result = nativeRun(binary, "impact", "--target", "rust", "--before", input.toString(), "--after", input.toString());
        assertEquals(3, result.code());
        assertEquals("I-SCHEMA", JsonParser.parseString(result.text()).getAsJsonObject().getAsJsonArray("diagnostics").get(0).getAsJsonObject().get("code").getAsString());
    }

    @Test public void sameNameInDifferentJavaPackagesCompilesAndExecutes() throws Exception {
        Path input = temporary.newFile().toPath(), out = temporary.newFolder().toPath();
        Files.writeString(input, reviewGrammar("Calc", "org.p1", "one") + reviewGrammar("Calc", "org.p2", "two"));
        success(javaRun("--grammar", input.toString(), "--output", out.toString(), "--generators", "AST,Parser,Mapper,Evaluator"));
        for (String pkg : List.of("org.p1", "org.p2")) {
            Path source = out.resolve(pkg.replace('.', '/')).resolve("Probe.java");
            Files.writeString(source, "package " + pkg + "; public class Probe extends CalcEvaluator<String> {"
                + "protected String evalValue(CalcAST.Value node) { return node.text() + \"!\"; }"
                + "public static String run(String input) { return new Probe().eval(CalcMapper.parse(input)); }}");
        }
        Path classes = temporary.newFolder().toPath();
        var args = new ArrayList<>(List.of("--release", "21", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()));
        try (var walk = Files.walk(out)) { args.addAll(walk.filter(p -> p.toString().endsWith(".java")).map(Path::toString).toList()); }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            assertEquals("one!", loader.loadClass("org.p1.Probe").getMethod("run", String.class).invoke(null, "one"));
            assertEquals("two!", loader.loadClass("org.p2.Probe").getMethod("run", String.class).invoke(null, "two"));
        }
    }

    @Test public void javaCollisionReportsAndManifestsUseJavaClassNames() throws Exception {
        for (String second : List.of("Calc", "CALC")) for (String format : List.of("json", "ndjson")) {
            Path dir = temporary.newFolder().toPath(), input = dir.resolve("input.ubnf");
            Files.writeString(input, reviewGrammar("Calc", "org.p1", "one") + reviewGrammar(second, "org.p1", "two"));
            Path report = dir.resolve("report.json"), manifest = dir.resolve("manifest.json"), output = dir.resolve("out");
            Run result = javaRun("--grammar", input.toString(), "--output", output.toString(), "--generators", "AST",
                "--report-format", format, "--report-file", report.toString(), "--output-manifest", manifest.toString(), "--report-schema-check");
            assertEquals(result.text(), 3, result.code());
            String payload = Files.readString(report);
            assertTrue(payload, payload.contains("P-OUTPUT-COLLISION"));
            assertTrue(payload, payload.contains("org.p1." + second + "AST"));
            assertFalse(payload, payload.contains("grammar_calc"));
            assertFalse(JsonParser.parseString(payload).getAsJsonObject().get("ok").getAsBoolean());
            assertTrue(Files.readString(manifest).contains("\"writtenCount\":0"));
            assertFalse(Files.exists(output));
        }
    }

    @Test public void javaHostAcceptsCallerOwnedSymlinkAncestors() throws Exception {
        Path dir = temporary.newFolder().toPath(), actual = Files.createDirectory(dir.resolve("actual"));
        Path link = Files.createSymbolicLink(dir.resolve("link"), actual);
        for (boolean multiple : List.of(false, true)) {
            Path input = dir.resolve("input-" + multiple + ".ubnf");
            Files.writeString(input, reviewGrammar("A", "p", "a") + (multiple ? reviewGrammar("B", "p", "b") : ""));
            Path out = link.resolve("project-" + multiple).resolve("generated");
            success(generate(null, input, out, false, false));
            success(generate(null, input, out, false, true));
            assertTrue(Files.isRegularFile(out.resolve("mod.rs")));
        }
        // The explicit output directory itself and generated descendants retain their existing protection.
        Path input = dir.resolve("input-false.ubnf");
        assertEquals(4, generate(null, input, link, false, false).code());
    }

    @Test public void importedCollisionAndLateErrorsKeepOriginalGrammarLocations() throws Exception {
        Path binary = binary(), dir = temporary.newFolder().toPath(), input = dir.resolve("input.ubnf");
        Files.writeString(dir.resolve("part.ubnf"), "grammar Part { @ubnf: v2 token X ::= 'x'; }\n");
        String first = "// 😀 original CP\n" + reviewGrammar("A", "p", "a");
        String imported = "grammar B { @import part from 'part.ubnf' @ubnf: v2 @package: p "
            + "token T ::= part.X; @root @mapping(Value,params=[text]) Root ::= T @text; }\n";
        for (String kind : List.of("collision", "mapping", "evaluator", "import")) for (boolean crlf : List.of(false, true)) {
            String second = kind.equals("collision") ? imported.replace("grammar B", "grammar A")
                : kind.equals("mapping") ? imported.replace("params=[text]", "params=[missing]")
                : kind.equals("import") ? imported.replace("part.ubnf", "missing.ubnf")
                : imported.replace("@root", "@root @eval(kind='binary_arithmetic')");
            String prefix = crlf ? first.replace("\n", "\r\n") : first;
            second = crlf ? second.replace("\n", "\r\n") : second;
            String source = prefix + second;
            Files.writeString(input, source);
            String location = "[" + prefix.codePointCount(0, prefix.length()) + ","
                + source.stripTrailing().codePointCount(0, source.stripTrailing().length()) + ")";
            for (int host = 0; host < 3; host++) {
                Path out = dir.resolve("out-" + kind + crlf + host);
                Run result = host == 0 ? javaRun("--grammar", input.toString(), "--output", out.toString(), "--generators", "AST,Parser,Mapper,Evaluator")
                    : generate(host == 1 ? null : binary, input, out, false, false);
                assertNotEquals(result.text(), 0, result.code());
                assertTrue(result.text(), result.text().contains(location));
                if (!kind.equals("collision")) assertTrue(result.text(), result.text().contains("grammar B " + location));
                assertFalse(Files.exists(out));
            }
        }
    }

    @Test public void singleJavaGrammarEmitterFailureWritesNoFiles() throws Exception {
        Path input = temporary.newFile().toPath(), output = temporary.getRoot().toPath().resolve("late-failure");
        Files.writeString(input, reviewGrammar("Calc", "p", "x").replace("@root", "@root @eval(kind='binary_arithmetic')"));
        Run result = javaRun("--grammar", input.toString(), "--output", output.toString(), "--generators", "AST,Parser,Mapper,Evaluator");
        assertNotEquals(result.text(), 0, result.code());
        assertFalse(Files.exists(output));
    }

    @Test public void invalidProgrammaticGrammarNameIsADiagnostic() {
        var snapshot = UBNFMapper.parseWithSource(reviewGrammar("A", "p", "x"));
        var g = snapshot.ast().grammars().get(0);
        var invalid = new org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl("文法", g.imports(), g.settings(), g.tokens(), g.rules());
        var result = PortabilityCheck.check(snapshot, new org.unlaxer.dsl.bootstrap.UBNFAST.UBNFFile(List.of(invalid)));
        assertFalse(result.portable());
        assertEquals("P-GRAMMAR-NAME", result.diagnostics().get(0).code());
    }

    @Test public void ioFailureDoesNotPublishRootIndexAndNamesFailedPath() throws Exception {
        String source = reviewGrammar("A", "p", "a") + reviewGrammar("B", "p", "b");
        var generated = new RustBackend().generateFile(UBNFMapper.parse(source), false, false);
        assertEquals("mod.rs", generated.get(generated.size() - 1).relativePath());
        for (boolean existingIndex : List.of(false, true)) {
            Path output = temporary.newFolder().toPath();
            if (existingIndex) Files.writeString(output.resolve("mod.rs"), "old index");
            // Exercise an actual write failure after one child, below CLI preflight.
            Files.createDirectories(output.resolve("grammar_a/ast.rs"));
            var error = assertThrows(java.io.IOException.class, () -> RustGenerateCommand.writeArtifacts(output, generated));
            assertTrue(error.toString(), error.getMessage().contains(output.resolve("grammar_a/ast.rs").toString()));
            assertTrue(Files.exists(output.resolve("grammar_a/mod.rs")));
            if (existingIndex) assertEquals("old index", Files.readString(output.resolve("mod.rs")));
            else assertFalse(Files.exists(output.resolve("mod.rs")));
        }
    }

    private static String reviewGrammar(String name, String pkg, String literal) {
        return "grammar " + name + " { @package: " + pkg + " @root @mapping(Value,params=[text]) Root ::= '" + literal + "' @text; }\n";
    }

    private List<String> javaObservations(Path input, JsonObject fixture) throws Exception {
        Path out = temporary.newFolder().toPath();
        success(javaRun("--grammar", input.toString(), "--output", out.toString(), "--generators", "AST,Parser,Mapper,Evaluator"));
        for (var item : fixture.getAsJsonArray("cases")) {
            String name = item.getAsJsonObject().get("grammar").getAsString();
            Files.writeString(out.resolve(name + "Probe.java"), "package org.example.multiple; public final class " + name
                + "Probe extends " + name + "Evaluator<String> { protected String evalValue(" + name + "AST.Value node) { return node.text() + \"!\"; } }");
        }
        Path classes = temporary.newFolder().toPath();
        var args = new ArrayList<>(List.of("--release", "21", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()));
        try (var walk = Files.walk(out)) { args.addAll(walk.filter(p -> p.toString().endsWith(".java")).map(Path::toString).toList()); }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        var observations = new ArrayList<String>();
        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            for (var item : fixture.getAsJsonArray("cases")) {
                JsonObject row = item.getAsJsonObject();
                String name = row.get("grammar").getAsString(), text = row.get("input").getAsString();
                String prefix = "org.example.multiple." + name;
                Parser parser = (Parser) loader.loadClass(prefix + "Parsers").getMethod("getRootParser").invoke(null);
                try (var context = new ParseContext(org.unlaxer.StringSource.createRootSource(text))) {
                    assertTrue(parser.parse(context).isSucceeded());
                    assertEquals(text.codePointCount(0, text.length()), context.getConsumedPosition().value());
                    Token token = context.getCurrent().getTokens().get(0);
                    var mapped = loader.loadClass(prefix + "Mapper").getMethod("mapParsedTokenWithSourceMap", Token.class).invoke(null, token);
                    Object ast = mapped.getClass().getMethod("ast").invoke(mapped);
                    String captured = (String) ast.getClass().getMethod("text").invoke(ast);
                    int[] span = (int[]) ((Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class).invoke(mapped, ast)).orElseThrow();
                    Object evaluator = loader.loadClass(prefix + "Probe").getConstructor().newInstance();
                    String value = (String) evaluator.getClass().getMethod("eval", loader.loadClass(prefix + "AST")).invoke(evaluator, ast);
                    assertEquals(row.get("value").getAsString(), value);
                    assertEquals(row.get("span"), new Gson().toJsonTree(span));
                    assertEquals(text, captured);
                    assertEquals(text, token.source.toString());
                    observations.add(name + "\t" + captured + "\t" + value + "\t" + span[0] + "\t" + span[1]);
                }
                try (var context = new ParseContext(org.unlaxer.StringSource.createRootSource("!"))) { assertFalse(parser.parse(context).isSucceeded()); }
                for (var other : fixture.getAsJsonArray("cases")) {
                    String foreign = other.getAsJsonObject().get("input").getAsString();
                    if (!foreign.equals(text)) try (var context = new ParseContext(org.unlaxer.StringSource.createRootSource(foreign))) { assertFalse(parser.parse(context).isSucceeded()); }
                }
            }
        }
        return observations;
    }

    private String rustProbe(String host, JsonObject fixture) {
        var source = new StringBuilder("#![allow(dead_code, unused_imports)]\n#[path=\"" + host + "/mod.rs\"] mod generated;\n");
        var main = new StringBuilder("fn main() {\n");
        int index = 0;
        for (var item : fixture.getAsJsonArray("cases")) {
            var row = item.getAsJsonObject();
            String module = row.get("module").getAsString(), name = row.get("grammar").getAsString(), probe = "Probe" + index++;
            source.append("struct ").append(probe).append("; impl generated::").append(module).append("::evaluator::Semantics for ").append(probe)
                .append(" { type Output=String; fn eval_value(&mut self, text:&str, _:unlaxer_runtime::Span)->String {format!(\"{text}!\")} }\n");
            main.append("{ use generated::").append(module).append(" as g; let input = ").append(row.get("input"))
                .append("; let tree=g::parser::parse_tree(input).unwrap(); let ast=g::mapper::map(&tree).unwrap();")
                .append("let g::ast::Ast::Value{text,span}= &ast; assert_eq!(input,text); assert_eq!(input,tree.text(*span));")
                .append("let value=g::evaluator::evaluate(&ast,&mut ").append(probe);
            main.append("); println!(\"OBS\\t").append(name).append("\\t{}\\t{}\\t{}\\t{}\",text,value,span.start,span.end);")
                .append("assert!(g::parser::parse_tree(\"!\").is_err());");
            for (var other : fixture.getAsJsonArray("cases")) {
                if (!other.getAsJsonObject().get("input").equals(row.get("input")))
                    main.append("assert!(g::parser::parse_tree(").append(other.getAsJsonObject().get("input")).append(").is_err());");
            }
            main.append("}\n");
        }
        return source.append(main).append("}\n").toString();
    }

    private Path input(JsonObject fixture, boolean crlf) throws Exception {
        Path path = temporary.newFile().toPath();
        String source = fixture.get("source").getAsString();
        Files.writeString(path, crlf ? source.replace("\n", "\r\n") : source);
        return path;
    }
    private Path binary() throws Exception {
        boolean enabled = Boolean.getBoolean("rustConformance");
        if (!enabled) System.out.println("[assumption] MultiGrammarConformanceTest requires -DrustConformance=true");
        assumeTrue("requires -DrustConformance=true", enabled);
        success(command(List.of("cargo", "build", "--offline", "--locked", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), false));
        return repo.resolve("rust/target/debug/unlaxer");
    }
    private Run generate(Path binary, Path input, Path output, boolean protocols, boolean check) throws Exception {
        var args = new ArrayList<>(List.of("generate", "--target", "rust", "--grammar", input.toString(), "--output", output.toString()));
        if (protocols) args.addAll(List.of("--lsp", "--dap"));
        if (check) args.add("--check");
        return binary == null ? javaRun(args.toArray(String[]::new)) : nativeRun(binary, args.toArray(String[]::new));
    }
    private Run javaRun(String... args) {
        var result = CodegenTestHelper.runCodegen(args);
        return new Run(result.exitCode(), result.out() + result.err());
    }
    private Run nativeRun(Path binary, String... args) throws Exception {
        var command = new ArrayList<>(List.of(binary.toString()));
        command.addAll(List.of(args));
        return command(command, true);
    }
    private Run command(List<String> args, boolean noJava) throws Exception {
        Path log = temporary.newFile().toPath();
        var builder = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("CARGO_INCREMENTAL", "0");
        if (noJava) { builder.environment().put("PATH", ""); builder.environment().put("JAVA_HOME", "/no-java"); }
        var process = builder.start();
        try {
            assertTrue("timeout: " + args, process.waitFor(180, TimeUnit.SECONDS));
            return new Run(process.exitValue(), Files.readString(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private static void success(Run result) { assertEquals(result.text(), 0, result.code()); }
    private Map<String, String> files(Path directory) throws Exception {
        var result = new TreeMap<String, String>();
        try (var walk = Files.walk(directory)) {
            for (Path file : walk.filter(Files::isRegularFile).toList())
                result.put(directory.relativize(file).toString(), Files.readString(file, StandardCharsets.UTF_8));
        }
        return result;
    }
}
