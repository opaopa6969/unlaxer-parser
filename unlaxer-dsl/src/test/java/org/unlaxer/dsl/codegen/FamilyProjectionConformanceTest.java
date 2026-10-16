package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Shared independent family projection and preferred-candidate observations. */
public class FamilyProjectionConformanceTest extends PreferredAstConformanceTest {
    @Override protected JsonArray corpus() throws Exception { return read("cases.json"); }
    private JsonArray read(String name) throws Exception {
        return JsonParser.parseString(Files.readString(
            Path.of("../spec-corpus/family-projection/" + name))).getAsJsonArray();
    }
    @Test public void javaRejectsInvalidFamiliesBeforeEmission() throws Exception {
        for (var raw : read("invalid-cases.json")) {
            var fixture = raw.getAsJsonObject();
            var grammar = UBNFMapper.parse(fixture.get("grammar").getAsString()).grammars().get(0);
            String code = fixture.get("error").getAsString();
            assertTrue(GrammarValidator.validateWithoutClassLoading(grammar).stream()
                .anyMatch(issue -> issue.code().equals(code)));
            for (CodeGenerator generator : List.of(new ASTGenerator(),new MapperGenerator(),new EvaluatorGenerator(21)))
                assertTrue(assertThrows(IllegalArgumentException.class, () -> generator.generate(grammar)).getMessage().contains(code));
            assertTrue(assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(grammar)).getMessage().contains(code));
        }
    }
    @Test public void nativeRejectsSameInvalidFamiliesBeforeEmission() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] FamilyProjectionConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path repo = Path.of("..").toAbsolutePath().normalize();
        run(List.of("cargo","build","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator"),"",false,true);
        for (var raw : read("invalid-cases.json")) {
            var fixture = raw.getAsJsonObject();
            Path dir=temporary.newFolder().toPath(), input=dir.resolve("grammar.ubnf"), output=dir.resolve("generated");
            Files.writeString(input,fixture.get("grammar").getAsString());
            String message=run(List.of(repo.resolve("rust/target/debug/unlaxer").toString(),"generate","--grammar",input.toString(),"--output",output.toString()),"",true,false);
            assertTrue(message,message.contains(fixture.get("error").getAsString()));
            assertFalse(Files.exists(output.resolve("ast.rs")));
        }
    }
}
