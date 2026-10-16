package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import java.util.List;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;

/** Existing Java associative delimiters through independently compiled host outputs. */
public class AssociativeDelimiterConformanceTest extends PreferredAstConformanceTest {
    @Override
    protected JsonArray corpus() throws Exception {
        return JsonParser.parseString(Files.readString(
            Path.of("../spec-corpus/associative-delimiters/cases.json"))).getAsJsonArray();
    }

    @Test
    public void bothHostsRejectOtherShapesBeforeEmission() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] AssociativeDelimiterConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path repo = Path.of("..").toAbsolutePath().normalize();
        run(List.of("cargo", "build", "--locked", "--manifest-path",
            repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), "", false, true);
        var rows = JsonParser.parseString(Files.readString(
            repo.resolve("spec-corpus/associative-delimiters/invalid-cases.json"))).getAsJsonArray();
        for (var raw : rows) {
            var row = raw.getAsJsonObject();
            String source = row.get("grammar").getAsString();
            String expected = row.get("error").getAsString();
            var grammar = UBNFMapper.parse(source).grammars().get(0);
            String javaDiagnostic = assertThrows(IllegalArgumentException.class,
                () -> new RustBackend().generate(grammar)).getMessage();
            assertTrue(javaDiagnostic, javaDiagnostic.contains(expected));
            Path dir = temporary.newFolder().toPath(), input = dir.resolve("grammar.ubnf"),
                output = dir.resolve("generated");
            Files.writeString(input, source);
            String diagnostic = run(List.of(repo.resolve("rust/target/debug/unlaxer").toString(),
                "generate", "--grammar", input.toString(), "--output", output.toString()), "", true, false);
            assertTrue(diagnostic, diagnostic.contains(expected));
            assertFalse(Files.exists(output.resolve("ast.rs")));
        }
    }
}
