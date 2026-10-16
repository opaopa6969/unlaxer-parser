package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Shared independent associative projection and preferred-candidate observations. */
public class AssociativeProjectionConformanceTest extends PreferredAstConformanceTest {
    @Override
    protected JsonArray corpus() throws Exception {
        return read("cases.json");
    }

    private JsonArray read(String name) throws Exception {
        return JsonParser.parseString(
                        Files.readString(Path.of("../spec-corpus/associative-projection/" + name)))
                .getAsJsonArray();
    }

    @Test
    public void javaRejectsInvalidAssociativeShapesBeforeEmission() throws Exception {
        for (var raw : read("invalid-cases.json")) {
            var fixture = raw.getAsJsonObject();
            var grammar = UBNFMapper.parse(fixture.get("grammar").getAsString()).grammars().get(0);
            String code = fixture.get("error").getAsString();
            assertTrue(
                    GrammarValidator.validateWithoutClassLoading(grammar).stream()
                            .anyMatch(issue -> issue.code().equals(code)));
            assertTrue(
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () -> GrammarValidator.validateOrThrow(grammar))
                            .getMessage()
                            .contains(code));
            assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(grammar));
        }
    }

    @Test
    public void nativeRejectsSameInvalidAssociativeShapesBeforeEmission() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println(
                    "[assumption] AssociativeProjectionConformanceTest requires"
                        + " -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path repo = Path.of("..").toAbsolutePath().normalize();
        run(
                List.of(
                        "cargo",
                        "build",
                        "--locked",
                        "--manifest-path",
                        repo.resolve("rust/Cargo.toml").toString(),
                        "-p",
                        "unlaxer-generator"),
                "",
                false,
                true);
        for (var raw : read("invalid-cases.json")) {
            var fixture = raw.getAsJsonObject();
            Path dir = temporary.newFolder().toPath(),
                    input = dir.resolve("grammar.ubnf"),
                    output = dir.resolve("generated");
            Files.writeString(input, fixture.get("grammar").getAsString());
            String message =
                    run(
                            List.of(
                                    repo.resolve("rust/target/debug/unlaxer").toString(),
                                    "generate",
                                    "--grammar",
                                    input.toString(),
                                    "--output",
                                    output.toString()),
                            "",
                            true,
                            false);
            assertTrue(message, message.contains(fixture.get("nativeError").getAsString()));
            assertFalse(Files.exists(output.resolve("ast.rs")));
        }
    }

    @Test
    public void bothHostsDapWalkSyntheticLeaves() throws Exception {
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path repo = Path.of("..").toAbsolutePath().normalize();
        var fixture = corpus().get(0).getAsJsonObject();
        String text = fixture.get("grammar").getAsString();
        var grammar = UBNFMapper.parse(text).grammars().get(0);
        Path project = temporary.newFolder().toPath(), modules = project.resolve("src/generated");
        Files.createDirectories(modules);
        Path input = project.resolve("grammar.ubnf");
        Files.writeString(input, text);
        run(
                List.of(
                        "cargo",
                        "build",
                        "--locked",
                        "--manifest-path",
                        repo.resolve("rust/Cargo.toml").toString(),
                        "-p",
                        "unlaxer-generator"),
                "",
                false,
                true);
        run(
                List.of(
                        repo.resolve("rust/target/debug/unlaxer").toString(),
                        "generate",
                        "--grammar",
                        input.toString(),
                        "--output",
                        modules.toString(),
                        "--dap"),
                "",
                true,
                true);
        for (var file : new RustBackend().generateWithDap(grammar))
            assertEquals(
                    file.relativePath(),
                    file.content(),
                    Files.readString(modules.resolve(file.relativePath())));
        Files.writeString(
                project.resolve("Cargo.toml"),
                "[package]\n"
                    + "name=\"associative-dap-fixture\"\n"
                    + "version=\"0.1.0\"\n"
                    + "edition=\"2021\"\n"
                    + "[dependencies]\n"
                    + "unlaxer-runtime={path=\""
                        + repo.resolve("rust/unlaxer-runtime")
                        + "\"}\nunlaxer-dap={path=\""
                        + repo.resolve("rust/unlaxer-dap")
                        + "\"}\n");
        Files.writeString(
                project.resolve("src/main.rs"),
                """
                #![allow(dead_code)]
                mod generated;
                fn main() {
                    let steps=generated::dap::steps("9-3").unwrap();
                    let observed:Vec<_>=steps.iter().map(|s|(s.label.as_str(),s.span.start,s.span.end)).collect();
                    assert_eq!(observed,vec![("Binary",0,3),("Binary",0,1),("Binary",0,1),("Binary",2,3),("Binary",2,3)]);
                    assert!(generated::dap::steps("9-").is_err());
                }
                """);
        run(
                List.of(
                        "cargo",
                        "run",
                        "--quiet",
                        "--offline",
                        "--manifest-path",
                        project.resolve("Cargo.toml").toString(),
                        "--target-dir",
                        repo.resolve("rust/target").toString()),
                "",
                false,
                true);
    }
}
