package org.unlaxer.dsl;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Same read-only CLI contract in the Java host and the native Rust host. */
public class RustPortabilityConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();
    private Path runtime;
    private record Run(int code, String out, String err) {}

    private Path binary() throws Exception {
        boolean enabled = Boolean.getBoolean("rustConformance");
        if (!enabled) System.out.println("[assumption] RustPortabilityConformanceTest requires -DrustConformance=true");
        assumeTrue("requires -DrustConformance=true", enabled);
        Run build = nativeRun(List.of("cargo", "build", "--locked", "--manifest-path",
            repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), false, temporary.getRoot().toPath());
        assertEquals(build.err(), 0, build.code());
        return repo.resolve("rust/target/debug/unlaxer");
    }

    @Test public void inventoriesAndCliExitCodesAgreeWithoutWritingGeneratedFiles() throws Exception {
        Path binary = binary();
        Path fixtures = repo.resolve("rust/unlaxer-generator/tests/fixtures/portability");
        Path cwd = temporary.newFolder().toPath();
        Files.writeString(cwd.resolve("sentinel.rs"), "// handwritten");
        List<String> report = new ArrayList<>(List.of("case\tline_endings\tstructure\tdiagnostics\tresult"));
        for (String line : Files.readAllLines(fixtures.resolve("expected.tsv"))) {
            String[] fields = line.split("\t");
            String source = Files.readString(fixtures.resolve(fields[0]));
            for (String ending : List.of("LF", "CRLF")) {
                JsonObject result = compare(binary, cwd, ending.equals("LF") ? source : source.replace("\n", "\r\n"));
                assertEquals(fields[0], fields[1], result.get("structure").getAsString());
                assertEquals(fields[0], Integer.parseInt(fields[2]), result.getAsJsonArray("diagnostics").size());
                report.add(fields[0] + "\t" + ending + "\t" + fields[1] + "\t" + fields[2] + "\tequal");
            }
        }
        for (int length : new int[] {8, 64, 96, 260, 2500}) {
            var source = new StringBuilder("grammar Chain { @root @mapping(Root, params=[value]) Start ::= R0 @value;\n");
            for (int index = 0; index < length; index++) source.append("R").append(index)
                .append(" ::= R").append(index + 1).append(";\n");
            source.append("R").append(length).append(" ::= 'x';\n}\n");
            for (String ending : List.of("LF", "CRLF")) {
                JsonObject result = compare(binary, cwd,
                    ending.equals("LF") ? source.toString() : source.toString().replace("\n", "\r\n"));
                assertEquals(length <= 64, result.get("portable").getAsBoolean());
                String structure = length <= 64 ? "passed" : "failed";
                assertEquals(structure, result.get("structure").getAsString());
                report.add("chain-" + length + "\t" + ending + "\t" + structure + "\t"
                    + result.getAsJsonArray("diagnostics").size() + "\tequal");
            }
        }
        for (int depth : new int[] {64, 96}) {
            String source = "grammar Nested { @root @mapping(Root, params=[value]) Start ::= "
                + "(".repeat(depth) + "'x'" + ")".repeat(depth) + " @value;\n}\n";
            for (String ending : List.of("LF", "CRLF")) {
                JsonObject result = compare(binary, cwd,
                    ending.equals("LF") ? source : source.replace("\n", "\r\n"));
                assertTrue(result.toString(), result.get("portable").getAsBoolean());
                report.add("nested-" + depth + "\t" + ending + "\tpassed\t0\tequal");
            }
        }
        for (String[] options : List.of(
                new String[] {"check"},
                new String[] {"check", "--target", "java", "--grammar", "absent"},
                new String[] {"check", "--target", "rust", "--grammar", ""},
                new String[] {"check", "--target", "rust", "--grammar", "absent", "--output", cwd.resolve("out").toString()},
                new String[] {"check", "--target", "rust", "--grammar", "absent", "--format", "text"},
                new String[] {"check", "--target", "rust", "--grammar", "absent", "--format", "json", "--format", "json"},
                new String[] {"check", "--target", "rust", "--target", "rust", "--grammar", "absent"},
                new String[] {"check", "--target", "rust", "--grammar", "absent", "--help"})) {
            Run java = javaRun(options);
            var args = new ArrayList<>(List.of(binary.toString()));
            args.addAll(List.of(options));
            Run rust = nativeRun(args, true, cwd);
            assertEquals(2, java.code());
            assertEquals(2, rust.code());
            assertEquals("", java.out());
            assertEquals("", rust.out());
            assertFalse(java.err().isEmpty());
            assertFalse(rust.err().isEmpty());
        }
        String absent = cwd.resolve("absent.ubnf").toString();
        assertEquals(4, javaRun("check", "--target", "rust", "--grammar", absent).code());
        assertEquals(4, nativeRun(List.of(binary.toString(), "check", "--target", "rust", "--grammar", absent), true, cwd).code());
        assertEquals("// handwritten", Files.readString(cwd.resolve("sentinel.rs")));
        try (var files = Files.list(cwd)) { assertEquals(1, files.count()); }
        Files.write(Path.of("target/rust-portability.tsv"), report);
    }

    @Test public void realTinyGrammarPassesAndItsExplicitlyInjectedGapsAreAllReported() throws Exception {
        String path = System.getProperty("tinyexpression.grammar");
        if (path == null) System.out.println("[assumption] RustPortabilityConformanceTest tiny integration requires -Dtinyexpression.grammar=<pinned p4.ubnf>");
        assumeTrue("requires actual tinyexpression.grammar", path != null);
        Path binary = binary();
        Path cwd = temporary.newFolder().toPath();
        String source = Files.readString(Path.of(path));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        // Regression evidence stays tied to the real pinned grammar, not an invented tiny subset.
        assertEquals("b3ebead02cf9c4ff2c8d8b56767ca51f3665331d7b84cf1f1fce79d600774347", hash);
        String injected = source.replaceFirst("(?m)^([ \\t]*)token ", "$1token PORTABILITY_PROBE = example.UnportedParser\n$1token ")
            .replaceFirst("(?m)^([ \\t]*)@root\\b", "$1@interleave(profile=portabilityProbe)\n$1@root");
        assertNotEquals(source, injected);
        List<String> report = new ArrayList<>(List.of("case\tline_endings\tbaseline_sha256\tdiagnostics\tresult"));
        for (String ending : List.of("LF", "CRLF")) {
            String baseline = ending.equals("LF") ? source : source.replace("\n", "\r\n");
            String negative = ending.equals("LF") ? injected : injected.replace("\n", "\r\n");
            assertTrue(compare(binary, cwd, baseline).get("portable").getAsBoolean());
            JsonObject gaps = compare(binary, cwd, negative);
            assertEquals("blocked", gaps.get("structure").getAsString());
            JsonArray diagnostics = gaps.getAsJsonArray("diagnostics");
            assertEquals(2, diagnostics.size());
            assertEquals("P-EXTERNAL-TOKEN", diagnostics.get(0).getAsJsonObject().get("code").getAsString());
            assertEquals("P-INTERLEAVE", diagnostics.get(1).getAsJsonObject().get("code").getAsString());
            report.add("real-p4\t" + ending + "\t" + hash + "\t0\tequal");
            report.add("real-p4-with-injected-gaps\t" + ending + "\t" + hash + "\t2\tequal");
        }
        Files.write(Path.of("target/rust-portability-tiny.tsv"), report);
    }

    @Test public void tokenAdapterRegistryDiagnosticsAgreeWithSourcePositions() throws Exception {
        Path binary = binary();
        Path cwd = temporary.newFolder().toPath();
        Path fixtures = repo.resolve("spec-corpus/token-adapters");
        List<String> report = new ArrayList<>(List.of("case\tline_endings\tstructure\tcodes\tresult"));
        for (String line : Files.readAllLines(fixtures.resolve("cases.tsv"))) {
            String[] fields = line.split("\t");
            String source = Files.readString(fixtures.resolve(fields[0]));
            for (String ending : List.of("LF", "CRLF")) {
                String input = ending.equals("LF") ? source : source.replace("\n", "\r\n");
                JsonObject result = compare(binary, cwd, input);
                assertEquals(fields[0], fields[1], result.get("structure").getAsString());
                List<String> codes = new ArrayList<>();
                for (var entry : result.getAsJsonArray("diagnostics")) {
                    JsonObject diagnostic = entry.getAsJsonObject();
                    codes.add(diagnostic.get("code").getAsString());
                    assertFalse("adapter diagnostics must have source positions", diagnostic.get("span").isJsonNull());
                    JsonObject span = diagnostic.getAsJsonObject("span");
                    String text = input.substring(input.offsetByCodePoints(0, span.get("start").getAsInt()),
                        input.offsetByCodePoints(0, span.get("end").getAsInt()));
                    assertTrue(text, diagnostic.get("code").getAsString().equals("P-STRUCTURE")
                        ? text.startsWith("grammar ")
                        : text.startsWith("@tokenAdapter:") || text.startsWith("token "));
                    if (codes.get(codes.size() - 1).equals("P-ADAPTER-DUPLICATE")) {
                        int offset = input.offsetByCodePoints(0, span.get("start").getAsInt());
                        assertEquals("the conflicting registration, not an arbitrary token", input.lastIndexOf("@tokenAdapter:"), offset);
                    }
                }
                String actual = codes.isEmpty() ? "-" : String.join(",", codes);
                assertEquals(fields[0], fields[2], actual);
                report.add(fields[0] + "\t" + ending + "\t" + fields[1] + "\t" + actual + "\tequal");
            }
        }
        try (var files = Files.list(cwd)) { assertEquals(0, files.count()); }
        Files.write(Path.of("target/rust-token-adapter-diagnostics.tsv"), report);
    }

    @Test public void importedDiagnosticsRetainOnlyRootPositionsAndDistinctOccurrences() throws Exception {
        Path binary = binary();
        Path fixtures = repo.resolve("rust/unlaxer-generator/tests/fixtures/portability/imports.json");
        var cases = JsonParser.parseString(Files.readString(fixtures)).getAsJsonArray();
        List<String> report = new ArrayList<>(List.of("case\tline_endings\tstructure\tdiagnostics\tresult"));
        for (var entry : cases) {
            var fixture = entry.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            for (String ending : List.of("LF", "CRLF")) {
                Path directory = temporary.newFolder().toPath();
                for (var file : fixture.getAsJsonObject("files").entrySet()) {
                    Path path = directory.resolve(file.getKey());
                    Files.createDirectories(path.getParent());
                    String source = file.getValue().getAsString();
                    Files.writeString(path, ending.equals("LF") ? source : source.replace("\n", "\r\n"));
                }
                Path input = directory.resolve("main.ubnf");
                var actual = compareFile(binary, directory, input, Files.readString(input));
                var expected = fixture.getAsJsonObject("expected").getAsJsonObject(ending);
                assertEquals(name + " / " + ending, expected, actual);
                for (var file : fixture.getAsJsonObject("files").entrySet()) {
                    String source = file.getValue().getAsString();
                    assertEquals(name, ending.equals("LF") ? source : source.replace("\n", "\r\n"),
                        Files.readString(directory.resolve(file.getKey())));
                }
                try (var files = Files.walk(directory)) {
                    assertEquals(name, fixture.getAsJsonObject("files").size(), files.filter(Files::isRegularFile).count());
                }
                report.add(name + "\t" + ending + "\t" + actual.get("structure").getAsString() + "\t"
                    + actual.getAsJsonArray("diagnostics").size() + "\tequal");
            }
        }
        Files.write(Path.of("target/rust-portability-imports.tsv"), report);
    }

    @Test public void structuralDiagnosticPriorityMatchesJavaForEveryLineEnding() throws Exception {
        Path binary = binary();
        var cases = JsonParser.parseString(Files.readString(repo.resolve(
            "rust/unlaxer-generator/tests/fixtures/portability/structure-priority.json"))).getAsJsonArray();
        List<String> report = new ArrayList<>(List.of("case\tline_endings\tstructure\tdiagnostics\tresult"));
        for (var entry : cases) {
            var fixture = entry.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            String source = fixture.has("sourceFile")
                ? Files.readString(repo.resolve(fixture.get("sourceFile").getAsString()))
                : fixture.get("source").getAsString();
            for (String ending : List.of("LF", "CRLF")) {
                Path directory = temporary.newFolder().toPath();
                Path input = directory.resolve("main.ubnf");
                String text = ending.equals("LF") ? source : source.replace("\n", "\r\n");
                Files.writeString(input, text);
                var actual = compareFile(binary, directory, input, text);
                assertEquals(name + " / " + ending,
                    fixture.getAsJsonObject("expected").getAsJsonObject(ending), actual);
                try (var files = Files.list(directory)) { assertEquals(name, 1, files.count()); }
                if (fixture.has("generation")) {
                    compareGeneration(binary, directory, input, fixture.getAsJsonObject("generation"));
                    assertEquals(text, Files.readString(input));
                }
                report.add(name + "\t" + ending + "\t" + actual.get("structure").getAsString() + "\t"
                    + actual.getAsJsonArray("diagnostics").size() + "\tequal");
            }
        }
        Files.write(Path.of("target/rust-portability-structure-priority.tsv"), report);
    }

    @Test public void adapterAndContractDiagnosticsMatchIndependentOracleAcrossConsumers() throws Exception {
        Path binary = binary();
        var cases = JsonParser.parseString(Files.readString(repo.resolve(
            "spec-corpus/token-adapters/diagnostics.json"))).getAsJsonArray();
        assertEquals("all authored cases execute", 46, cases.size());
        List<String> report = new ArrayList<>(List.of("case\tline_endings\tcheck_exit\tgenerate_exit\tresult"));
        for (var entry : cases) {
            var fixture = entry.getAsJsonObject();
            for (String ending : List.of("LF", "CRLF")) {
                String label = fixture.get("name").getAsString() + " / " + ending;
                String source = fixture.get("source").getAsString().replace("\n", ending.equals("LF") ? "\n" : "\r\n");
                var grammar = org.unlaxer.dsl.bootstrap.UBNFMapper.parse(source).grammars().get(0);
                List<String> adapterErrors = diagnosticMessages(fixture.getAsJsonArray("adapters"));
                List<String> contractErrors = diagnosticMessages(fixture.getAsJsonArray("contracts"));
                assertEquals(label, adapterErrors, org.unlaxer.dsl.bootstrap.TokenAdapterRegistry.build(grammar, null)
                    .diagnostics().stream().map(d -> d.code() + ": " + d.subject()).toList());
                assertEquals(label, contractErrors, org.unlaxer.dsl.bootstrap.TokenContractRegistry.build(grammar, null)
                    .diagnostics().stream().map(d -> d.code() + ": " + d.subject()).toList());
                var errors = new ArrayList<>(adapterErrors);
                errors.addAll(contractErrors);
                assertEquals(label, errors, org.unlaxer.dsl.codegen.GrammarValidator.validate(grammar)
                    .stream().map(d -> d.message()).toList());
                if (errors.isEmpty()) {
                    org.unlaxer.dsl.codegen.GrammarValidator.validateOrThrow(grammar);
                } else {
                    var failure = assertThrows(IllegalArgumentException.class,
                        () -> org.unlaxer.dsl.codegen.GrammarValidator.validateOrThrow(grammar));
                    assertTrue(label + failure.getMessage(), failure.getMessage().contains("\n - " + errors.get(0) + " [code:"));
                }
                Path directory = temporary.newFolder().toPath();
                Path input = directory.resolve("main.ubnf");
                Files.writeString(input, source);
                assertEquals(label, diagnosticCheckOracle(fixture, source), compareFile(binary, directory, input, source));
                Path javaOutput = directory.resolve("java-rust"), rustOutput = directory.resolve("native-rust");
                Run java = javaRun("generate", "--target", "rust", "--grammar", input.toString(), "--output", javaOutput.toString());
                Run rust = nativeRun(List.of(binary.toString(), "generate", "--target", "rust", "--grammar", input.toString(),
                    "--output", rustOutput.toString()), true, directory);
                int code = errors.isEmpty() ? 0 : 3;
                assertEquals(label + java.err(), code, java.code());
                assertEquals(label + rust.err(), code, rust.code());
                // Keep the Java-target CLI as well as Java-host Rust generation intact.
                Path javaTarget = directory.resolve("java");
                Run javaGeneration = javaRun("--grammar", input.toString(), "--output", javaTarget.toString(),
                    "--generators", "Parser,AST,Mapper,Evaluator");
                assertEquals(label + javaGeneration.err(), code, javaGeneration.code());
                if (errors.isEmpty()) {
                    for (String file : List.of("ast.rs", "parser.rs", "mapper.rs", "evaluator.rs", "mod.rs"))
                        assertArrayEquals(label + file, Files.readAllBytes(javaOutput.resolve(file)), Files.readAllBytes(rustOutput.resolve(file)));
                    try (var files = Files.walk(javaTarget)) { assertTrue(label, files.anyMatch(p -> p.toString().endsWith("AST.java"))); }
                } else {
                    assertEquals(label, errors.get(0), rust.err().strip());
                    assertTrue(label + java.err(), java.err().startsWith("Grammar validation failed:\n - " + errors.get(0) + " [code:"));
                    String javaFirst = fixture.has("javaTargetFirst") ? fixture.get("javaTargetFirst").getAsString() : errors.get(0);
                    assertTrue(label + javaGeneration.err(), javaGeneration.err().startsWith("Grammar validation failed:\n - grammar G: " + javaFirst + " [code:"));
                    assertFalse(label, Files.exists(javaOutput));
                    assertFalse(label, Files.exists(rustOutput));
                    assertFalse(label, Files.exists(javaTarget));
                }
                report.add(fixture.get("name").getAsString() + "\t" + ending + "\t" + code + "\t" + code + "\tequal");
            }
        }
        // ADAPTER references are registry diagnostics, so they precede later generator checks.
        var ordered = JsonParser.parseString(Files.readString(repo.resolve(
            "spec-corpus/token-adapters/generate-order.json"))).getAsJsonArray();
        assertEquals("all generate-order cases execute", 5, ordered.size());
        for (var entry : ordered) {
            var fixture = entry.getAsJsonObject();
            for (String ending : List.of("\n", "\r\n")) {
                String label = fixture.get("name").getAsString() + " / " + ending.length();
                String source = fixture.get("source").getAsString().replace("\n", ending);
                String first = fixture.get("first").getAsString();
                var grammar = org.unlaxer.dsl.bootstrap.UBNFMapper.parse(source).grammars().get(0);
                var failure = assertThrows(IllegalArgumentException.class,
                    () -> org.unlaxer.dsl.codegen.GrammarValidator.validateOrThrow(grammar));
                assertTrue(label + failure.getMessage(), failure.getMessage().startsWith("Grammar validation failed for G:\n - " + first + " [code:"));
                Path directory = temporary.newFolder().toPath(), input = directory.resolve("main.ubnf");
                Files.writeString(input, source);
                Run java = javaRun("generate", "--target", "rust", "--grammar", input.toString(),
                    "--output", directory.resolve("java-rust").toString());
                Run rust = nativeRun(List.of(binary.toString(), "generate", "--target", "rust", "--grammar", input.toString(),
                    "--output", directory.resolve("native-rust").toString()), true, directory);
                assertEquals(label + java.err(), 3, java.code());
                assertTrue(label + java.err(), java.err().startsWith("Grammar validation failed:\n - " + first + " [code:"));
                assertEquals(label + rust.err(), 3, rust.code());
                assertEquals(label, first, rust.err().strip());
            }
        }
        Files.write(Path.of("target/rust-adapter-contract-diagnostics.tsv"), report);
    }

    @Test public void featureAndLexicalDiagnosticsMatchIndependentOracleAcrossConsumers() throws Exception {
        Path binary = binary();
        var cases = JsonParser.parseString(Files.readString(repo.resolve(
            "spec-corpus/feature-diagnostics/cases.json"))).getAsJsonArray();
        assertEquals("all authored cases execute", 31, cases.size());
        var whitespace = JsonParser.parseString(Files.readString(repo.resolve(
            "spec-corpus/feature-diagnostics/whitespace.json"))).getAsJsonArray();
        assertEquals("all whitespace combinations execute", 172, whitespace.size());
        cases.addAll(whitespace);
        var versions = JsonParser.parseString(Files.readString(repo.resolve(
            "spec-corpus/feature-diagnostics/ubnf-version.json"))).getAsJsonArray();
        assertEquals("all ubnf version cases execute", 109, versions.size());
        cases.addAll(versions);
        List<String> report = new ArrayList<>(List.of("case\tline_endings\tcheck_exit\tgenerate_exit\tresult"));
        for (var entry : cases) {
            var fixture = entry.getAsJsonObject();
            for (String ending : List.of("LF", "CRLF")) {
                String label = fixture.get("name").getAsString() + " / " + ending;
                String source = fixture.get("source").getAsString().replace("\n", ending.equals("LF") ? "\n" : "\r\n");
                var grammar = org.unlaxer.dsl.bootstrap.UBNFMapper.parse(source).grammars().get(0);
                var features = org.unlaxer.dsl.bootstrap.UBNFFeatures.validate(grammar).stream()
                    .map(d -> d.code() + ": " + d.subject()).toList();
                assertEquals(label, fixture.get("features"), new Gson().toJsonTree(features));
                if (fixture.has("ubnf")) {
                    var versionIssues = org.unlaxer.dsl.codegen.GrammarValidator.validate(grammar).stream()
                        .filter(d -> d.code().startsWith("E-UBNF-VERSION"))
                        .map(d -> d.code() + ": " + d.message()).toList();
                    assertEquals(label, fixture.get("ubnf"), new Gson().toJsonTree(versionIssues));
                }
                assertEquals(label, features, org.unlaxer.dsl.codegen.GrammarValidator.validate(grammar).stream()
                    .filter(d -> d.code().startsWith("E-FEATURE-")).map(d -> d.message()).toList());
                Path directory = temporary.newFolder().toPath(), input = directory.resolve("main.ubnf");
                Files.writeString(input, source);
                var expected = fixture.getAsJsonObject("expected").getAsJsonObject(ending);
                assertEquals(label, expected, compareFile(binary, directory, input, source));
                Path javaOutput = directory.resolve("java-rust"), rustOutput = directory.resolve("native-rust");
                Run java = javaRun("generate", "--target", "rust", "--grammar", input.toString(), "--output", javaOutput.toString());
                Run rust = nativeRun(List.of(binary.toString(), "generate", "--target", "rust", "--grammar", input.toString(),
                    "--output", rustOutput.toString()), true, directory);
                int code = fixture.get("first").isJsonNull() ? 0 : 3;
                assertEquals(label + java.err(), code, java.code());
                assertEquals(label + rust.err(), code, rust.code());
                if (code == 0) {
                    for (String file : List.of("ast.rs", "parser.rs", "mapper.rs", "evaluator.rs", "mod.rs"))
                        assertArrayEquals(label + file, Files.readAllBytes(javaOutput.resolve(file)), Files.readAllBytes(rustOutput.resolve(file)));
                } else {
                    String first = fixture.get("first").getAsString();
                    assertEquals(label, first, rust.err().strip());
                    // Java renders some diagnostic codes separately from their messages.
                    String javaFirst = fixture.has("javaFirst") ? fixture.get("javaFirst").getAsString() : first + " [code:";
                    assertTrue(label + java.err(), java.err().startsWith("Grammar validation failed:\n - " + javaFirst));
                    if (fixture.has("firstCode"))
                        assertTrue(label + java.err(), java.err().lines().skip(1).findFirst().orElseThrow()
                            .contains("[code: " + fixture.get("firstCode").getAsString() + "]"));
                    assertFalse(label, Files.exists(javaOutput));
                    assertFalse(label, Files.exists(rustOutput));
                }
                report.add(fixture.get("name").getAsString() + "\t" + ending + "\t"
                    + (expected.get("portable").getAsBoolean() ? 0 : 3) + "\t" + code + "\tequal");
            }
        }
        var modules = JsonParser.parseString(Files.readString(repo.resolve(
            "spec-corpus/feature-diagnostics/modules.json"))).getAsJsonArray();
        assertEquals("all imported lexical messages execute", 8, modules.size());
        for (var entry : modules) {
            var fixture = entry.getAsJsonObject();
            for (String ending : List.of("LF", "CRLF")) {
                String label = "module/" + fixture.get("name").getAsString() + " / " + ending;
                Path directory = temporary.newFolder().toPath(), input = directory.resolve("main.ubnf");
                Files.writeString(directory.resolve("child.ubnf"), fixture.get("source").getAsString()
                    .replace("\n", ending.equals("LF") ? "\n" : "\r\n"));
                String source = "grammar G { @import m from 'child.ubnf' @ubnf: v2 @root @mapping(Root) Start ::= 'x'; }";
                Files.writeString(input, source);
                var expected = JsonParser.parseString("{\"schemaVersion\":1,\"target\":\"rust\",\"portable\":false,"
                    + "\"structure\":\"blocked\",\"diagnostics\":[{\"code\":\"P-MODULE\",\"severity\":\"error\","
                    + "\"span\":null,\"subject\":\"UBNF module resolution\"}]}");
                assertEquals(label, expected, compareFile(binary, directory, input, source));
                Path javaOutput = directory.resolve("java-rust"), rustOutput = directory.resolve("native-rust");
                Run java = javaRun("generate", "--target", "rust", "--grammar", input.toString(), "--output", javaOutput.toString());
                Run rust = nativeRun(List.of(binary.toString(), "generate", "--target", "rust", "--grammar", input.toString(),
                    "--output", rustOutput.toString()), true, directory);
                assertEquals(label + java.err(), 3, java.code());
                assertEquals(label + rust.err(), 3, rust.code());
                assertEquals(label, fixture.get("first").getAsString(), java.err().strip());
                assertEquals(label, fixture.get("first").getAsString(), rust.err().strip());
                assertFalse(label, Files.exists(javaOutput));
                assertFalse(label, Files.exists(rustOutput));
                report.add("module/" + fixture.get("name").getAsString() + "\t" + ending + "\t3\t3\tequal");
            }
        }
        var contexts = JsonParser.parseString(Files.readString(repo.resolve(
            "spec-corpus/feature-diagnostics/lexical-contexts.json"))).getAsJsonArray();
        assertEquals("all lexical-context cases execute", 6, contexts.size());
        for (var entry : contexts) {
            var fixture = entry.getAsJsonObject();
            for (String ending : List.of("LF", "CRLF")) {
                String label = "lexical-context/" + fixture.get("name").getAsString() + " / " + ending;
                String source = fixture.get("source").getAsString().replace("\n", ending.equals("LF") ? "\n" : "\r\n");
                String first = fixture.get("first").getAsString();
                var grammar = org.unlaxer.dsl.bootstrap.UBNFMapper.parse(source).grammars().get(0);
                assertEquals(label, List.of(first), org.unlaxer.dsl.codegen.GrammarValidator.validate(grammar).stream()
                    .filter(d -> d.code().equals("E-LEXICAL")).map(d -> d.message()).toList());
                Path directory = temporary.newFolder().toPath(), input = directory.resolve("main.ubnf");
                Files.writeString(input, source);
                var expected = fixture.getAsJsonObject("expected").getAsJsonObject(ending);
                assertEquals(label, expected, compareFile(binary, directory, input, source));
                Path javaOutput = directory.resolve("java-rust"), rustOutput = directory.resolve("native-rust");
                Run java = javaRun("generate", "--target", "rust", "--grammar", input.toString(), "--output", javaOutput.toString());
                Run rust = nativeRun(List.of(binary.toString(), "generate", "--target", "rust", "--grammar", input.toString(),
                    "--output", rustOutput.toString()), true, directory);
                assertEquals(label + java.err(), 3, java.code());
                assertEquals(label + rust.err(), 3, rust.code());
                assertTrue(label + java.err(), java.err().startsWith("Grammar validation failed:\n - " + first + " [code:"));
                assertEquals(label, first, rust.err().strip());
                assertFalse(label, Files.exists(javaOutput));
                assertFalse(label, Files.exists(rustOutput));
                report.add("lexical-context/" + fixture.get("name").getAsString() + "\t" + ending + "\t3\t3\tequal");
            }
        }
        Files.write(Path.of("target/rust-feature-lexical-diagnostics.tsv"), report);
    }

    private static List<String> diagnosticMessages(JsonArray diagnostics) {
        var messages = new ArrayList<String>();
        for (var entry : diagnostics) {
            var d = entry.getAsJsonObject();
            messages.add(d.get("code").getAsString() + ": " + d.get("subject").getAsString());
        }
        return messages;
    }

    private static JsonObject diagnosticCheckOracle(JsonObject fixture, String source) {
        // Fixture line numbers select complete declarations independently of AST spans.
        var diagnostics = new ArrayList<JsonObject>();
        for (String kind : List.of("adapters", "contracts")) {
            for (var entry : fixture.getAsJsonArray(kind)) {
                var d = entry.getAsJsonObject();
                int line = d.get("line").getAsInt(), offset = 0;
                for (int i = 0; i < line; i++) offset = source.indexOf('\n', offset) + 1;
                int end = source.indexOf('\n', offset);
                if (source.charAt(end - 1) == '\r') end--;
                var span = new JsonObject();
                span.addProperty("start", source.codePointCount(0, offset));
                span.addProperty("end", source.codePointCount(0, end));
                var diagnostic = new JsonObject();
                diagnostic.add("code", d.get("code"));
                diagnostic.addProperty("severity", "error");
                diagnostic.add("span", span);
                diagnostic.add("subject", d.get("subject"));
                diagnostics.add(diagnostic);
            }
        }
        diagnostics.sort(java.util.Comparator.comparingInt(d -> d.getAsJsonObject("span").get("start").getAsInt()));
        var result = new JsonObject();
        result.addProperty("schemaVersion", 1);
        result.addProperty("target", "rust");
        result.addProperty("portable", diagnostics.isEmpty());
        result.addProperty("structure", diagnostics.isEmpty() ? "passed" : "blocked");
        var values = new JsonArray();
        diagnostics.forEach(values::add);
        result.add("diagnostics", values);
        return result;
    }

    private void compareGeneration(Path binary, Path directory, Path input, JsonObject expected) throws Exception {
        Path javaRust = directory.resolve("java-rust"), nativeRust = directory.resolve("native-rust");
        Run java = javaRun("generate", "--target", "rust", "--grammar", input.toString(),
            "--output", javaRust.toString());
        Run rust = nativeRun(List.of(binary.toString(), "generate", "--grammar", input.toString(),
            "--output", nativeRust.toString()), true, directory);
        int code = expected.get("rust").getAsInt();
        assertEquals(java.err(), code, java.code());
        assertEquals(rust.err(), code, rust.code());
        if (code == 0) {
            for (String file : List.of("ast.rs", "parser.rs", "mapper.rs", "evaluator.rs", "mod.rs"))
                assertArrayEquals(file, Files.readAllBytes(javaRust.resolve(file)), Files.readAllBytes(nativeRust.resolve(file)));
            if (expected.has("samples")) {
                assertEquals(compileAndObserve(javaRust, expected), compileAndObserve(nativeRust, expected));
            }
        } else {
            assertTrue(java.err(), java.err().contains("E-COMMONFIELD-MISMATCH"));
            assertTrue(rust.err(), rust.err().contains("E-COMMONFIELD-MISMATCH"));
            assertFalse(Files.exists(javaRust));
            assertFalse(Files.exists(nativeRust));
        }
        if (expected.has("java")) {
            Path output = directory.resolve("java");
            Run generated = javaRun("--grammar", input.toString(), "--output", output.toString(),
                "--generators", "Parser,AST,Mapper,Evaluator");
            assertEquals(generated.err(), expected.get("java").getAsInt(), generated.code());
            try (var files = Files.walk(output)) {
                assertTrue(files.anyMatch(path -> path.toString().endsWith("AST.java")));
            }
        }
    }

    private String compileAndObserve(Path generated, JsonObject expected) throws Exception {
        if (runtime == null) {
            runtime = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
            Run build = nativeRun(List.of("rustc", "--edition=2021", "--crate-type=rlib",
                "--crate-name=unlaxer_runtime", repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),
                "-o", runtime.toString()), false, generated);
            assertEquals(build.err(), 0, build.code());
        }
        StringBuilder probe = new StringBuilder(
            "#[path=\"mod.rs\"] mod generated; use generated::{parser, mapper}; fn main() {\n");
        for (var entry : expected.getAsJsonArray("samples")) {
            var sample = entry.getAsJsonArray();
            probe.append("{let tree=parser::parse_tree(").append(sample.get(0))
                .append(").unwrap();let ast=mapper::map(&tree).unwrap();let _: &")
                .append(expected.get("fieldType").getAsString())
                .append("=ast.common_node().unwrap().value;println!(\"{}\",ast.canonical_json());}\n");
        }
        for (var input : expected.getAsJsonArray("invalidInputs"))
            probe.append("assert!(parser::parse_tree(").append(input).append(").is_err());\n");
        probe.append("}\n");
        Path source = generated.resolve("probe.rs"), executable = generated.resolve("probe");
        Files.writeString(source, probe);
        Run compile = nativeRun(List.of("rustc", "--edition=2021", "--extern",
            "unlaxer_runtime=" + runtime, source.toString(), "-o", executable.toString()), false, generated);
        assertEquals(compile.err(), 0, compile.code());
        Run observed = nativeRun(List.of(executable.toString()), true, generated);
        assertEquals(observed.err(), 0, observed.code());
        String[] lines = observed.out().lines().toArray(String[]::new);
        var samples = expected.getAsJsonArray("samples");
        assertEquals(samples.size(), lines.length);
        for (int i = 0; i < samples.size(); i++) {
            var sample = samples.get(i).getAsJsonArray();
            var ast = new JsonObject();
            ast.add("type", sample.get(3));
            ast.add("span", sample.get(2));
            var fields = new JsonObject();
            fields.add("value", sample.get(1));
            ast.add("fields", fields);
            assertEquals(sample.get(0).toString(), ast, JsonParser.parseString(lines[i]));
        }
        return observed.out();
    }

    private JsonObject compare(Path binary, Path cwd, String source) throws Exception {
        Path input = temporary.newFile().toPath();
        Files.writeString(input, source);
        return compareFile(binary, cwd, input, source);
    }

    private JsonObject compareFile(Path binary, Path cwd, Path input, String source) throws Exception {
        String[] options = {"check", "--target", "rust", "--grammar", input.toString(), "--format", "json"};
        Run java = javaRun(options);
        var args = new ArrayList<>(List.of(binary.toString()));
        args.addAll(List.of(options));
        Run rust = nativeRun(args, true, cwd);
        assertEquals(java.out() + "\n" + rust.out() + "\n" + rust.err(), java.code(), rust.code());
        assertEquals("", java.err());
        assertEquals("", rust.err());
        JsonObject expected = JsonParser.parseString(java.out()).getAsJsonObject();
        assertEquals(1, expected.get("schemaVersion").getAsInt());
        assertEquals("rust", expected.get("target").getAsString());
        assertEquals(source, expected, JsonParser.parseString(rust.out()));
        assertEquals(expected.get("portable").getAsBoolean() ? 0 : 3, java.code());
        assertEquals(java.out(), javaRun(options).out());
        for (var value : expected.getAsJsonArray("diagnostics")) {
            JsonObject diagnostic = value.getAsJsonObject();
            assertEquals("error", diagnostic.get("severity").getAsString());
            if (!diagnostic.get("span").isJsonNull()) {
                JsonObject span = diagnostic.getAsJsonObject("span");
                int start = span.get("start").getAsInt(), end = span.get("end").getAsInt();
                assertTrue(start >= 0 && end > start && end <= source.codePointCount(0, source.length()));
            }
        }
        assertEquals(source, Files.readString(input));
        return expected;
    }

    private static Run javaRun(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = CodegenMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Run nativeRun(List<String> args, boolean noJava, Path cwd) throws Exception {
        Path out = temporary.newFile().toPath(), err = temporary.newFile().toPath();
        var builder = new ProcessBuilder(args).directory(cwd.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
        if (noJava) { builder.environment().put("PATH", ""); builder.environment().put("JAVA_HOME", "/missing-java"); }
        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("process timeout: " + args); }
        return new Run(process.exitValue(), Files.readString(out), Files.readString(err));
    }
}
