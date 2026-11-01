package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;

/** Both UBNF hosts emit the same Rust; compiled Java/Rust keep original text and CP spans. */
public class GraphemeConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();
    private Path fixture(String name) { return repo.resolve("spec-corpus/grapheme/" + name); }
    private String decode(String hex) {
        StringBuilder result = new StringBuilder();
        if (false == hex.equals("~")) for (String cp : hex.split(" ")) result.appendCodePoint(Integer.parseInt(cp, 16));
        return result.toString();
    }
    private List<String[]> cases() throws Exception {
        return Files.readAllLines(fixture("boundaries.tsv")).stream().filter(line -> !line.startsWith("#"))
            .map(line -> line.split("\t")).toList();
    }
    @Test public void generatedJavaPreservesTextAndCodePointSpans() throws Exception {
        for (String binding : List.of("GraphemeParser", "org.unlaxer.parser.elementary.GraphemeParser")) {
          for (String shape : List.of("Root ::= G @text;", "Root ::= (G) @text;", "Root ::= Item @text; Item ::= G;")) {
            String source = Files.readString(fixture("Graphemes.ubnf")).replace("org.unlaxer.parser.elementary.GraphemeParser", binding)
                .replace("Root ::= G @text;", shape);
            GrammarDecl grammar = UBNFMapper.parse(source).grammars().get(0);
            try (var loader = compileJava(grammar)) { verifyJava(loader, cases(), null); }
          }
        }
    }
    @Test public void bothRustHostsCompileAndMatchTheIndependentOracle() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] GraphemeConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), "", false);
        run(List.of("cargo", "build", "--locked", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), "", false);
        List<String> report = new ArrayList<>(List.of("binding\tshape\tcase\tprefix_cp\tfull_input_accepted\tstatus"));
        for (String binding : List.of("GraphemeParser", "org.unlaxer.parser.elementary.GraphemeParser")) {
          for (String shape : List.of("Root ::= G @text;", "Root ::= (G) @text;", "Root ::= Item @text; Item ::= G;")) {
            String source = Files.readString(fixture("Graphemes.ubnf")).replace("org.unlaxer.parser.elementary.GraphemeParser", binding)
                .replace("Root ::= G @text;", shape);
            GrammarDecl grammar = UBNFMapper.parse(source).grammars().get(0);
            Path directory = temporary.newFolder().toPath();
            Path grammarFile = directory.resolve("Graphemes.ubnf");
            Files.writeString(grammarFile, source);
            Path generated = directory.resolve("generated");
            run(List.of(repo.resolve("rust/target/debug/unlaxer").toString(), "generate", "--grammar", grammarFile.toString(),
                "--output", generated.toString()), "", true);
            for (var file : new RustBackend().generate(grammar)) {
                assertEquals(file.relativePath(), file.content(), Files.readString(generated.resolve(file.relativePath())));
            }
            Path main = directory.resolve("main.rs");
            Files.writeString(main, """
                mod generated;
                use std::io::{self, BufRead};
                fn main() {
                    for row in io::stdin().lock().lines() {
                        let encoded = row.unwrap();
                        let input: String = if encoded == "~" { String::new() } else {
                            encoded.split_whitespace().map(|cp| char::from_u32(u32::from_str_radix(cp,16).unwrap()).unwrap()).collect()
                        };
                        let mut context = unlaxer_runtime::ParseContext::new(&input);
                        let ok = generated::parser::parse_context(&mut context).is_ok();
                        print!(r#"{{"ok":{},"consumed":{},"matched":{},"ast":"#, ok, context.position(), context.matched_position());
                        match generated::parser::parse_tree(&input) {
                            Ok(tree) => { let ast = generated::mapper::map(&tree).unwrap(); drop(tree); print!("{}", ast.canonical_json()); }
                            Err(_) => print!("null"),
                        }
                        println!("}}");
                    }
                }
                """);
            Path binary = directory.resolve("probe");
            run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + library,
                main.toString(), "-o", binary.toString()), "", false);
            var corpus = cases();
            String input = String.join("\n", corpus.stream().map(row -> row[1]).toList()) + "\n";
            List<String> rust = run(List.of(binary.toString()), input, true).lines().toList();
            assertEquals(corpus.size(), rust.size());
            try (var loader = compileJava(grammar)) { verifyJava(loader, corpus, rust); }
            for (String[] row : corpus) {
                String[] boundaries = row[2].split(",");
                report.add(binding + "\t" + shape + "\t" + row[0] + "\t" + (boundaries.length == 1 ? 0 : boundaries[1].split(":")[0])
                    + "\t" + (boundaries.length == 2) + "\tequal");
            }
        }
        }
        Files.write(Path.of("target/grapheme-conformance.tsv"), report);
    }
    private void verifyJava(URLClassLoader loader, List<String[]> corpus, List<String> rust) throws Exception {
        Parser parser = (Parser) loader.loadClass("grapheme.probe.GraphemesParsers").getMethod("getRootParser").invoke(null);
        Class<?> mapper = loader.loadClass("grapheme.probe.GraphemesMapper");
        for (int index = 0; index < corpus.size(); index++) {
            String[] row = corpus.get(index);
            String input = decode(row[1]);
            String[] boundaries = row[2].split(",");
            int prefix = boundaries.length == 1 ? 0 : Integer.parseInt(boundaries[1].split(":")[0]);
            boolean accepted = boundaries.length == 2;
            JsonObject nativeResult = rust == null ? null : JsonParser.parseString(rust.get(index)).getAsJsonObject();
            try (var context = new ParseContext(StringSource.createRootSource(input))) {
                assertEquals(row[0], prefix > 0, parser.parse(context).isSucceeded());
                assertEquals(row[0], prefix, context.getConsumedPosition().value());
                assertEquals(row[0], prefix, context.getMatchedPosition().value());
            }
            Optional<?> diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, input);
            assertEquals(row[0], accepted, diagnostic.isEmpty());
            if (nativeResult != null) {
                assertEquals(row[0], prefix > 0, nativeResult.get("ok").getAsBoolean());
                assertEquals(row[0], prefix, nativeResult.get("consumed").getAsInt());
                assertEquals(row[0], prefix, nativeResult.get("matched").getAsInt());
                assertEquals(row[0], accepted, !nativeResult.get("ast").isJsonNull());
            }
            if (accepted) {
                Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
                Object ast = mapped.getClass().getMethod("ast").invoke(mapped);
                assertEquals(row[0], input, ast.getClass().getMethod("text").invoke(ast));
                int[] span = (int[]) ((Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class).invoke(mapped, ast)).orElseThrow();
                assertArrayEquals(row[0], new int[] {0, prefix}, span);
                if (nativeResult != null) {
                    var nativeAst = nativeResult.getAsJsonObject("ast");
                    assertEquals("Value", nativeAst.get("type").getAsString());
                    assertEquals(input, nativeAst.getAsJsonObject("fields").get("text").getAsString());
                    assertEquals(JsonParser.parseString("[0," + prefix + "]"), nativeAst.get("span"));
                }
            }
        }
    }
    private URLClassLoader compileJava(GrammarDecl grammar) throws Exception {
        List<JavaFileObject> units = new ArrayList<>();
        for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
            var source = generator.generate(grammar);
            units.add(new SimpleJavaFileObject(URI.create("string:///" + source.packageName().replace('.', '/') + "/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignored) { return source.source(); }
            });
        }
        Path output = temporary.newFolder().toPath();
        var compiler = ToolProvider.getSystemJavaCompiler();
        StringWriter diagnostics = new StringWriter();
        try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            assertTrue(diagnostics.toString(), compiler.getTask(diagnostics, files, null,
                List.of("--release", "17", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()), null, units).call());
        }
        return new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader());
    }
    private String run(List<String> command, String input, boolean noJava) throws Exception {
        Path log = temporary.newFile().toPath();
        ProcessBuilder builder = org.unlaxer.dsl.TestProcesses.builder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        if (noJava) { builder.environment().put("PATH", ""); builder.environment().put("JAVA_HOME", "/missing-java"); }
        Process process = builder.start();
        try {
            try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            assertTrue("process timeout: " + command, process.waitFor(60, TimeUnit.SECONDS));
            String result = Files.readString(log);
            assertEquals(result, 0, process.exitValue());
            return result;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
