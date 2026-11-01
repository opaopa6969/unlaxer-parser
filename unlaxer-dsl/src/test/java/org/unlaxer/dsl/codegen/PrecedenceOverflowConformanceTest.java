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
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.codegen.rust.RustGrammarLowering;

/** Fixed diagnostics, including their order and original CP ranges, for all three hosts. */
public class PrecedenceOverflowConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test public void precedenceLevelsMatchTheSharedOracle() throws Exception {
        check("precedence-");
    }

    @Test public void boundedDiagnosticsMatchTheSharedOracle() throws Exception {
        check("bounded-");
    }

    private void check(String prefix) throws Exception {
        var cases = new JsonArray();
        for (var row : JsonParser.parseString(Files.readString(
                ROOT.resolve("conformance/precedence-overflow/corpus.json"))).getAsJsonArray()) {
            if (row.getAsJsonObject().get("name").getAsString().startsWith(prefix)) cases.add(row);
        }
        // Keep equal-valued ASTs alive together to detect source-origin key collisions.
        var grammars = new ArrayList<GrammarDecl>();
        for (var raw : cases) grammars.add(UBNFMapper.parse(raw.getAsJsonObject().get("grammar").getAsString()).grammars().get(0));
        for (int index = 0; index < cases.size(); index++) {
            var row = cases.get(index).getAsJsonObject();
            var name = row.get("name").getAsString();
            var expected = new ArrayList<String>();
            for (var issue : row.getAsJsonArray("issues")) {
                var object = issue.getAsJsonObject();
                expected.add(object.get("code").getAsString() + ": " + object.get("message").getAsString());
            }
            for (var grammar : List.of(grammars.get(index), UBNFMapper.parseWithSource(row.get("grammar").getAsString()).ast().grammars().get(0))) {
                assertEquals(name, expected, GrammarValidator.validate(grammar).stream()
                    .map(issue -> issue.code() + ": " + issue.message()).toList());
                assertEquals(name, expected, GrammarValidator.validateWithoutClassLoading(grammar).stream()
                    .map(issue -> issue.code() + ": " + issue.message()).toList());
                for (var generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator(), new EvaluatorGenerator(21))) {
                    if (expected.isEmpty()) assertNotNull(name, generator.generate(grammar));
                    else assertEquals(name, expected.get(0), assertThrows(IllegalArgumentException.class,
                        () -> generator.generate(grammar)).getMessage());
                }
                if (expected.isEmpty()) {
                    assertNotNull(name, RustGrammarLowering.lower(grammar));
                    assertEquals(name, 5, new RustBackend().generate(grammar).size());
                } else {
                    assertEquals(name, expected.get(0), assertThrows(IllegalArgumentException.class,
                        () -> RustGrammarLowering.lower(grammar)).getMessage());
                    assertEquals(name, expected.get(0), assertThrows(IllegalArgumentException.class,
                        () -> new RustBackend().generate(grammar)).getMessage());
                }
            }
        }
    }

    @Test public void nativeValidatorAndGeneratorRunTheSameFixtures() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println(
            "[assumption] PrecedenceOverflowConformanceTest requires -DrustConformance=true and cargo");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path log = temporary.newFile().toPath();
        var process = org.unlaxer.dsl.TestProcesses.builder("cargo", "test", "--locked", "--manifest-path",
            ROOT.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator", "--test", "precedence_overflow")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("native fixture timeout"); }
        assertEquals(Files.readString(log), 0, process.exitValue());
    }
}
