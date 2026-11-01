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

/** One source/code/CP oracle for Java, Java-host Rust and the native generator. */
public class BoundedOverflowConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test public void javaAndJavaHostRustMatchTheSharedOracle() throws Exception {
        var cases = JsonParser.parseString(Files.readString(
            ROOT.resolve("conformance/bounded-overflow/corpus.json"))).getAsJsonArray();
        // Retain all ASTs: equal record values at distinct source positions must not collide.
        var grammars = new ArrayList<org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl>();
        for (var raw : cases) grammars.add(UBNFMapper.parse(raw.getAsJsonObject().get("grammar").getAsString()).grammars().get(0));
        for (int index = 0; index < cases.size(); index++) {
            var row = cases.get(index).getAsJsonObject();
            var source = row.get("grammar").getAsString();
            // Both existing mapper entry points must preserve the diagnostic.
            for (var grammar : List.of(grammars.get(index), UBNFMapper.parseWithSource(source).ast().grammars().get(0))) {
                var issues = GrammarValidator.validate(grammar);
                boolean accepted = row.get("code").getAsString().equals("accept");
                if (accepted) assertEquals(row.toString(), List.of(), issues);
                else {
                    assertEquals(row.toString(), 1, issues.size());
                    assertEquals(row.toString(), row.get("code").getAsString(), issues.get(0).code());
                    assertEquals(row + " " + issues, MESSAGE + row.get("span").getAsString(), issues.get(0).message());
                }
                for (var generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator(), new EvaluatorGenerator(21))) {
                    if (accepted) assertNotNull(generator.generate(grammar));
                    else diagnostic(row, assertThrows(IllegalArgumentException.class, () -> generator.generate(grammar)).getMessage());
                }
                if (accepted) assertEquals(5, new RustBackend().generate(grammar).size());
                else diagnostic(row, assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(grammar)).getMessage());
            }
        }
    }

    // Java generators, Java-host Rust and the native generator share this exact text.
    private static final String MESSAGE = "repetition bound exceeds 2147483647 at CP ";

    private static void diagnostic(JsonObject row, String message) {
        assertEquals(row.toString(), row.get("code").getAsString() + ": " + MESSAGE + row.get("span").getAsString(), message);
    }

    @Test public void nativeValidatorAndGeneratorRunTheSameFixtures() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println(
            "[assumption] BoundedOverflowConformanceTest requires -DrustConformance=true and cargo");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path log = temporary.newFile().toPath();
        var process = org.unlaxer.dsl.TestProcesses.builder("cargo", "test", "--locked", "--manifest-path",
            ROOT.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator", "--test", "bounded_overflow")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("native fixture timeout"); }
        assertEquals(Files.readString(log), 0, process.exitValue());
    }
}
