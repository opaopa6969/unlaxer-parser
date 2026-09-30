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
            .replaceFirst("(?m)^([ \\t]*)@root\\b", "$1@doc('portability probe')\n$1@root");
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
            assertEquals("P-ANNOTATION", diagnostics.get(1).getAsJsonObject().get("code").getAsString());
            report.add("real-p4\t" + ending + "\t" + hash + "\t0\tequal");
            report.add("real-p4-with-injected-gaps\t" + ending + "\t" + hash + "\t2\tequal");
        }
        Files.write(Path.of("target/rust-portability-tiny.tsv"), report);
    }

    private JsonObject compare(Path binary, Path cwd, String source) throws Exception {
        Path input = temporary.newFile().toPath();
        Files.writeString(input, source);
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
