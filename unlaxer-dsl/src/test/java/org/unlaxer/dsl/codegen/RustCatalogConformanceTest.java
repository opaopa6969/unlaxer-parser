package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.codegen.rust.RustGrammarLowering;

/** Catalog annotations are owned metadata, independent of parser scope effects and Java tooling. */
public class RustCatalogConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    private JsonArray corpus() throws Exception {
        return JsonParser.parseString(Files.readString(Path.of("src/test/resources/catalog/corpus.json"))).getAsJsonArray();
    }

    @Test public void fixturesHaveValidSyntaxAndIndependentlyDecodedContexts() throws Exception {
        for (var fixture : corpus()) {
            var row = fixture.getAsJsonObject();
            var grammar = UBNFMapper.parse(row.get("grammar").getAsString()).grammars().get(0);
            GrammarValidator.validateOrThrow(grammar);
            var expected = row.getAsJsonArray("catalogs");
            int index = 0;
            for (var rule : grammar.rules()) for (var annotation : rule.annotations()) {
                if (annotation instanceof org.unlaxer.dsl.bootstrap.UBNFAST.CatalogAnnotation catalog) {
                    assertEquals(expected.get(index).getAsJsonObject().get("rule").getAsString(), rule.name());
                    assertEquals(expected.get(index++).getAsJsonObject().get("context").getAsString(), catalog.context());
                }
            }
            assertEquals(expected.size(), index);
        }
    }

    @Test public void javaFrontendPreservesContextAndSortedLocalCapturesInIr() throws Exception {
        for (var fixture : corpus()) {
            var row = fixture.getAsJsonObject();
            var grammar = UBNFMapper.parse(row.get("grammar").getAsString()).grammars().get(0);
            GrammarValidator.validateOrThrow(grammar);
            var ir = RustGrammarLowering.lower(grammar);
            var actual = new JsonArray();
            for (var rule : ir.rules()) {
                // Reflection lets the acceptance test compile before the new IR accessor exists.
                Object catalog = rule.getClass().getMethod("catalog").invoke(rule);
                if (catalog == null) continue;
                var item = new JsonObject();
                item.addProperty("rule", rule.name());
                item.addProperty("context", (String) catalog.getClass().getMethod("context").invoke(catalog));
                var captures = new JsonArray();
                for (Object capture : (List<?>) catalog.getClass().getMethod("captures").invoke(catalog)) {
                    captures.add((String) capture);
                }
                item.add("captures", captures);
                actual.add(item);
            }
            assertEquals(row.get("name").getAsString(), row.get("catalogs"), actual);
        }
    }

    @Test public void catalogRequiresACaptureInItsOwnRule() {
        for (String source : invalidCatalogs()) {
            var grammar = UBNFMapper.parse(source).grammars().get(0);
            var error = assertThrows(IllegalArgumentException.class, () -> RustGrammarLowering.lower(grammar));
            assertTrue(error.getMessage(), error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("catalog"));
            assertTrue(error.getMessage(), error.getMessage().contains("capture"));
        }
    }

    @Test public void duplicateCatalogMetadataIsRejectedInsteadOfChoosingOneContext() {
        var grammar = UBNFMapper.parse(duplicateCatalog()).grammars().get(0);
        var error = assertThrows(IllegalArgumentException.class, () -> RustGrammarLowering.lower(grammar));
        assertTrue(error.getMessage(), error.getMessage().contains("duplicate @catalog"));
    }

    private List<String> invalidCatalogs() {
        return List.of(
            "grammar Empty { @root @catalog(context='variable') @mapping(Root) Root ::= 'x'; }",
            "grammar ChildOnly { @root @catalog(context='variable') @mapping(Root) Root ::= Child; @mapping(Child, params=[name]) Child ::= 'x' @name; }");
    }

    @Test public void grammarWithoutCatalogKeepsAllFiveCommittedFilesUnchanged() throws Exception {
        String source = Files.readString(Path.of("src/test/resources/evolution/3/Evolution.ubnf"));
        var files = new RustBackend().generate(UBNFMapper.parse(source).grammars().get(0));
        assertEquals(5, files.size());
        for (var file : files) {
            assertArrayEquals(file.relativePath(), Files.readAllBytes(repo.resolve("rust/examples/evolution/src/generated").resolve(file.relativePath())),
                file.content().getBytes(StandardCharsets.UTF_8));
            assertFalse(file.content().contains("CatalogSpec"));
            assertFalse(file.content().contains("CATALOGS"));
        }
    }

    @Test public void bothFrontendsEmitIdenticalExecutableCatalogMetadata() throws Exception {
        assumeTrue("enable with -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        Path nativeGenerator = buildNative();
        Path runtime = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()), false));
        Path unchanged = temporary.newFolder().toPath().resolve("generated");
        success(generate(nativeGenerator, repo.resolve("unlaxer-dsl/src/test/resources/evolution/3/Evolution.ubnf"), unchanged));
        for (String file : List.of("mod.rs", "ast.rs", "parser.rs", "mapper.rs", "evaluator.rs")) {
            assertArrayEquals("native no-catalog snapshot: " + file,
                Files.readAllBytes(repo.resolve("rust/examples/evolution/src/generated").resolve(file)), Files.readAllBytes(unchanged.resolve(file)));
        }
        var report = new ArrayList<>(List.of("fixture\texpected_catalogs\tgenerated_catalogs"));
        for (var fixture : corpus()) {
            var row = fixture.getAsJsonObject();
            Path directory = temporary.newFolder().toPath();
            Path source = directory.resolve("Catalog.ubnf");
            Files.writeString(source, row.get("grammar").getAsString());
            Path generated = directory.resolve("generated");
            success(generate(nativeGenerator, source, generated));
            var grammar = UBNFMapper.parse(Files.readString(source)).grammars().get(0);
            var files = new RustBackend().generate(grammar);
            assertEquals(5, files.size());
            for (var file : files) assertArrayEquals(row.get("name") + "/" + file.relativePath(),
                file.content().getBytes(StandardCharsets.UTF_8), Files.readAllBytes(generated.resolve(file.relativePath())));
            Files.writeString(directory.resolve("main.rs"), """
                mod generated;
                fn main() {
                    let metadata: &[generated::parser::CatalogSpec] = generated::parser::CATALOGS;
                    let rows = metadata.iter().map(|item| {
                        let _: &'static str = item.rule;
                        let _: &'static str = item.context;
                        let captures: &'static [&'static str] = item.captures;
                        format!(r#"{{"rule":{},"context":{},"captures":[{}]}}"#,
                            unlaxer_runtime::json_string(item.rule), unlaxer_runtime::json_string(item.context),
                            captures.iter().map(|name| unlaxer_runtime::json_string(name)).collect::<Vec<_>>().join(","))
                    }).collect::<Vec<_>>().join(",");
                    println!("[{}]", rows);
                }
                """);
            Path probe = directory.resolve("probe");
            success(run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + runtime,
                directory.resolve("main.rs").toString(), "-o", probe.toString()), false));
            Result result = run(List.of(probe.toString()), false);
            success(result);
            var actual = JsonParser.parseString(result.output());
            assertEquals(row.get("name").getAsString(), row.get("catalogs"), actual);
            report.add(row.get("name").getAsString() + "\t" + row.get("catalogs") + "\t" + actual);
        }
        for (String invalid : invalidCatalogs()) {
            Path directory = temporary.newFolder().toPath();
            Path source = directory.resolve("Invalid.ubnf");
            Files.writeString(source, invalid);
            Path generated = directory.resolve("generated");
            Result rejected = generate(nativeGenerator, source, generated);
            assertEquals(rejected.output(), 3, rejected.code());
            assertTrue(rejected.output(), rejected.output().toLowerCase(java.util.Locale.ROOT).contains("catalog"));
            assertTrue(rejected.output(), rejected.output().contains("capture"));
            assertFalse("rejected catalog must not leave partial output", Files.exists(generated));
        }
        Path duplicateDirectory = temporary.newFolder().toPath();
        Path duplicateSource = duplicateDirectory.resolve("Duplicate.ubnf");
        Files.writeString(duplicateSource, duplicateCatalog());
        Result duplicate = generate(nativeGenerator, duplicateSource, duplicateDirectory.resolve("generated"));
        assertEquals(duplicate.output(), 3, duplicate.code());
        assertTrue(duplicate.output(), duplicate.output().contains("duplicate @catalog"));
        assertFalse("duplicate catalog must not leave partial output", Files.exists(duplicateDirectory.resolve("generated")));
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/rust-catalog-metadata.tsv"), report, StandardCharsets.UTF_8);
    }

    private String duplicateCatalog() {
        return "grammar Duplicate { @root @catalog(context='variable') @catalog(context='function') @mapping(Root, params=[name]) Root ::= 'x' @name; }";
    }

    @Test public void realTinyGrammarAdvancesPastCatalogToTheSameGenerationFrontier() throws Exception {
        assumeTrue("enable with -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        String configured = System.getProperty("tinyexpression.grammar");
        assumeTrue("set -Dtinyexpression.grammar=<real tinyexpression-p4.ubnf>", configured != null && !configured.isBlank());
        Path source = Path.of(configured).toAbsolutePath();
        String original = Files.readString(source);
        assertTrue("the real grammar must exercise catalog", original.contains("@catalog(context='variable')"));
        // Remove ONLY catalog in a temporary diagnostic copy: this independently exposes the next frontier.
        String withoutCatalog = original.replace("@catalog(context='variable')", "");
        String nextJava = loweringOutcome(withoutCatalog);
        assertFalse(nextJava, nextJava.toLowerCase(java.util.Locale.ROOT).contains("catalog"));
        String actualJava = loweringOutcome(original);
        Path nativeGenerator = buildNative();
        Path bypass = temporary.newFile("without-catalog.ubnf").toPath();
        Files.writeString(bypass, withoutCatalog);
        Path baseline = temporary.newFolder().toPath();
        Result nextNative = generate(nativeGenerator, bypass, baseline.resolve("generated"));
        Path actualDirectory = temporary.newFolder().toPath();
        Result actualNative = generate(nativeGenerator, source, actualDirectory.resolve("generated"));
        assertFalse(nextNative.output(), nextNative.output().toLowerCase(java.util.Locale.ROOT).contains("catalog"));
        String compileFrontier = "not reached";
        if (nextNative.code() == 0) {
            Path runtime = baseline.resolve("libunlaxer_runtime.rlib");
            success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
                repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()), false));
            Files.writeString(baseline.resolve("main.rs"), "mod generated; fn main() {}\n");
            Result compilation = run(List.of("rustc", "--edition=2021", "--cap-lints=allow", "--extern",
                "unlaxer_runtime=" + runtime, baseline.resolve("main.rs").toString(), "-o", baseline.resolve("probe").toString()), false);
            compileFrontier = "exit=" + compilation.code() + "\n" + compilation.output();
            if (actualNative.code() == 0) {
                Files.writeString(actualDirectory.resolve("main.rs"), """
                    mod generated;
                    fn main() {
                        let catalog = generated::parser::CATALOGS.iter().find(|c| c.rule == "VariableRef").unwrap();
                        assert_eq!(catalog.context, "variable");
                        assert_eq!(catalog.captures, ["name", "type"]);
                    }
                    """);
                Result actualCompilation = run(List.of("rustc", "--edition=2021", "--cap-lints=allow", "--extern",
                    "unlaxer_runtime=" + runtime, actualDirectory.resolve("main.rs").toString(), "-o", actualDirectory.resolve("probe").toString()), false);
                assertEquals("catalog must not introduce a new compile blocker: " + actualCompilation.output(),
                    compilation.code(), actualCompilation.code());
                if (actualCompilation.code() == 0) success(run(List.of(actualDirectory.resolve("probe").toString()), false));
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/rust-catalog-tiny-frontier.txt"),
            "source=" + source + "\nJava without catalog=" + nextJava + "\nJava original=" + actualJava
                + "\nNative without catalog=" + nextNative.output() + "\nNative original=" + actualNative.output()
                + "\nRust compile frontier without catalog=" + compileFrontier);
        assertEquals("Java must advance past catalog", nextJava, actualJava);
        assertEquals("native must advance past catalog", nextNative.code(), actualNative.code());
        if (nextNative.code() != 0) {
            assertEquals("native must reach the same next blocker", nextNative.output(), actualNative.output());
        }
    }

    private String loweringOutcome(String source) {
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        try {
            return "generated " + new RustBackend().generate(grammar).size() + " files";
        } catch (IllegalArgumentException error) {
            return error.getMessage();
        }
    }

    private Path buildNative() throws Exception {
        Path target = temporary.newFolder().toPath();
        success(run(List.of("cargo", "build", "--locked", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(),
            "-p", "unlaxer-generator", "--target-dir", target.toString()), false));
        return target.resolve("debug/unlaxer");
    }

    private Result generate(Path binary, Path source, Path output) throws Exception {
        return run(List.of(binary.toString(), "generate", "--grammar", source.toString(), "--output", output.toString()), true);
    }

    private record Result(int code, String output) {}

    private Result run(List<String> command, boolean withoutJava) throws Exception {
        Path log = temporary.newFile().toPath();
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        if (withoutJava) {
            builder.environment().put("PATH", "");
            builder.environment().put("JAVA_HOME", "/nonexistent-unlaxer-java");
        }
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            assertTrue("process timed out: " + command, process.waitFor(120, TimeUnit.SECONDS));
            return new Result(process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        }
    }

    private void success(Result result) { assertEquals(result.output(), 0, result.code()); }
}
