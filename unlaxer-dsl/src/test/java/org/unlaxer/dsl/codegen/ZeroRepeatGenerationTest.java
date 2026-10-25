package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Invalid definitions are rejected independently of whether their child executes. */
public class ZeroRepeatGenerationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private JsonArray cases() throws Exception {
        return JsonParser.parseString(Files.readString(ROOT.resolve("conformance/zero-repeat/invalid-bounds.json"))).getAsJsonArray();
    }
    @Test public void everyJavaGenerationEntryRejectsInvalidBoundsIncludingDeadChildren() throws Exception {
        for (var raw : cases()) {
            var row = raw.getAsJsonObject();
            var grammar = UBNFMapper.parse(row.get("grammar").getAsString()).grammars().get(0);
            assertTrue(row.toString(), GrammarValidator.validateWithoutClassLoading(grammar).stream()
                .anyMatch(issue -> issue.code().equals("E-BOUNDED-INVALID")));
            for (var generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator(), new EvaluatorGenerator(21))) {
                var error = assertThrows(IllegalArgumentException.class, () -> generator.generate(grammar));
                assertTrue(row + " " + error, error.getMessage().contains("E-BOUNDED-INVALID"));
            }
            var error = assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(grammar));
            assertTrue(row + " " + error, error.getMessage().contains("E-BOUNDED-INVALID"));
        }
    }
    @Test public void nativeRustRejectsTheSameInvalidDefinitionsBeforeEmittingFiles() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] ZeroRepeatGenerationTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        assertEquals(0, run(List.of("cargo", "build", "--locked", "--manifest-path", ROOT.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator")).status());
        for (var raw : cases()) {
            var row = raw.getAsJsonObject();
            Path dir = temporary.newFolder().toPath(), input = dir.resolve("grammar.ubnf"), output = dir.resolve("generated");
            Files.writeString(input, row.get("grammar").getAsString());
            var result = run(List.of(ROOT.resolve("rust/target/debug/unlaxer").toString(), "generate", "--grammar", input.toString(), "--output", output.toString()));
            assertNotEquals(row + " " + result.output(), 0, result.status());
            assertTrue(row + " " + result.output(), result.output().contains("E-BOUNDED-INVALID"));
            assertFalse(Files.exists(output.resolve("parser.rs")));
        }
    }
    private record Result(int status, String output) {}
    private Result run(List<String> command) throws Exception {
        Path log = temporary.newFile().toPath();
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("timeout " + command); }
        return new Result(process.exitValue(), Files.readString(log));
    }
}
