package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.*;
import org.unlaxer.dsl.tooling.VocabularyOrigins;

public class VocabularyOriginsConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void sourceIdentityAndScalarRangesMatchAcrossHosts() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] vocabulary origins require -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path repository = Path.of("..").toAbsolutePath().normalize();
        Path log = temporary.newFile().toPath();
        Process build = new ProcessBuilder("cargo", "build", "--locked", "--manifest-path", repository.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(build.waitFor(90, TimeUnit.SECONDS)); assertEquals(Files.readString(log), 0, build.exitValue());
        String source = "grammar Layout {\n @ubnf: v2\n // 😀\n token GAP ::= ' ' | '#';\n token WORD ::= 'word';\n}\n";
        List<String> report = new ArrayList<>(List.of("fixture\tidentity\tscalar_definitions\tjava_native_json"));
        for (int mode = 0; mode < 3; mode++) {
            boolean packaged = mode != 0;
            String moduleSource = mode == 2 ? source.replace("grammar Layout {\n", "grammar Layout {\n @import std from 'pkg:std/layout'\n").replace("' ' | '#'", "std.SPACES") : source;
            Path directory = temporary.newFolder().toPath(), grammar = directory.resolve("root.ubnf");
            String reference = packaged ? "pkg:team/layout" : "layout.ubnf";
            Files.writeString(grammar, "grammar G { @import layout from '" + reference + "' @ubnf: v2 @whitespace: layout.GAP @root @mapping(Root,params=[value]) Start ::= 'a' @value; }");
            Files.writeString(directory.resolve("layout.ubnf"), moduleSource);
            if (packaged) {
                Files.writeString(directory.resolve("artifact.json"), new Gson().toJson(Map.of("schemaVersion", 1, "id", "team/layout", "version", "1.2.3", "entry", "layout.ubnf", "files", Map.of("layout.ubnf", moduleSource), "dependencies", mode == 2 ? Map.of("std/layout", Map.of("version", "1.0.0", "source", "builtin:std/layout@1.0.0")) : Map.of())));
                Files.writeString(directory.resolve("ubnf.json"), "{\"schemaVersion\":1,\"dependencies\":{\"team/layout\":{\"version\":\"1.2.3\",\"source\":\"local:artifact.json\"}}}");
                UBNFPackageResolver.resolve(directory.resolve("ubnf.json"));
            }
            JsonObject javaResult = VocabularyOrigins.inspect(grammar);
            Process nativeProcess = new ProcessBuilder(repository.resolve("rust/target/debug/unlaxer").toString(), "deps", "inspect", "--grammar", grammar.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertTrue(nativeProcess.waitFor(20, TimeUnit.SECONDS)); assertEquals(Files.readString(log), 0, nativeProcess.exitValue());
            assertEquals(javaResult.toString() + "\n", Files.readString(log));
            JsonObject module = javaResult.getAsJsonArray("modules").get(0).getAsJsonObject();
            assertEquals(moduleSource, module.get("text").getAsString());
            assertEquals("layout", module.get("alias").getAsString()); assertEquals(reference, module.get("source").getAsString());
            String expected = mode == 2 ? "[{\"end\":95,\"name\":\"GAP\",\"start\":70},{\"end\":119,\"name\":\"WORD\",\"start\":97}]"
                : "[{\"end\":59,\"name\":\"GAP\",\"start\":35},{\"end\":83,\"name\":\"WORD\",\"start\":61}]";
            assertEquals(JsonParser.parseString(expected), module.get("definitions"));
            if (packaged) {
                JsonObject identity = module.getAsJsonObject("identity"); assertEquals("team/layout", identity.get("id").getAsString());
                assertEquals("1.2.3", identity.get("version").getAsString()); assertEquals("layout.ubnf", identity.get("file").getAsString());
                assertEquals(UBNFPackageResolver.sha256(Files.readAllBytes(directory.resolve("artifact.json"))), identity.get("sha256").getAsString());
            } else assertTrue(module.get("identity").isJsonNull());
            Path output = directory.resolve("playground");
            Process playground = new ProcessBuilder(repository.resolve("rust/target/debug/unlaxer").toString(), "playground", "--grammar", grammar.toString(), "--output", output.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertTrue(playground.waitFor(20, TimeUnit.SECONDS)); assertEquals(Files.readString(log), 0, playground.exitValue());
            for (var entry : org.unlaxer.dsl.codegen.rust.PlaygroundGenerator.generate(grammar).entrySet())
                assertEquals(entry.getKey(), entry.getValue(), Files.readString(output.resolve(entry.getKey())));
            report.add((mode == 2 ? "transitive-package" : packaged ? "pinned-package" : "relative-module") + "\tpass\tpass\tpass");
            if (mode == 2) {
                String standardHash = UBNFPackageResolver.sha256(Files.readAllBytes(repository.resolve("unlaxer-dsl/src/main/resources/ubnf-packages/std-layout-1.0.0.json")));
                Path cached = directory.resolve(".ubnf-cache/packages/" + standardHash + ".json");
                Files.writeString(cached, Files.readString(cached) + " ");
                assertTrue(assertThrows(IllegalArgumentException.class, () -> VocabularyOrigins.inspect(grammar)).getMessage().contains("artifact hash mismatch"));
                Process rejected = new ProcessBuilder(repository.resolve("rust/target/debug/unlaxer").toString(), "deps", "inspect", "--grammar", grammar.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                assertTrue(rejected.waitFor(20, TimeUnit.SECONDS)); assertNotEquals(0, rejected.exitValue()); assertTrue(Files.readString(log).contains("artifact hash mismatch"));
                report.add("unverified-transitive-origin\trefused\trefused\tpass");
            }
        }
        JsonObject coexistence = JsonParser.parseString(Files.readString(repository.resolve(
            "unlaxer-dsl/src/test/resources/rule-trivia/corpus.json"))).getAsJsonArray().asList().stream()
            .map(JsonElement::getAsJsonObject).filter(value -> value.get("name").getAsString()
                .equals("public-personal-project-coexistence")).findFirst().orElseThrow();
        Path directory = temporary.newFolder().toPath(), grammar = directory.resolve("root.ubnf");
        Files.writeString(grammar, coexistence.get("grammar").getAsString());
        for (var module : coexistence.getAsJsonObject("modules").entrySet())
            Files.writeString(directory.resolve(module.getKey()), module.getValue().getAsString());
        Files.writeString(directory.resolve("ubnf.json"), coexistence.get("manifest").toString());
        UBNFPackageResolver.resolve(directory.resolve("ubnf.json"));
        JsonObject snapshot = VocabularyOrigins.inspect(grammar);
        Process inspect = new ProcessBuilder(repository.resolve("rust/target/debug/unlaxer").toString(),
            "deps", "inspect", "--grammar", grammar.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(inspect.waitFor(20, TimeUnit.SECONDS)); assertEquals(Files.readString(log), 0, inspect.exitValue());
        assertEquals(snapshot.toString() + "\n", Files.readString(log));
        JsonArray modules = snapshot.getAsJsonArray("modules"); assertEquals(3, modules.size());
        assertEquals(List.of("standard", "personal", "project"), modules.asList().stream()
            .map(value -> value.getAsJsonObject().get("alias").getAsString()).toList());
        JsonObject standard = modules.get(0).getAsJsonObject().getAsJsonObject("identity");
        assertEquals("std/layout", standard.get("id").getAsString());
        assertEquals("2701f6884ea6c712d28c76ec3d340c3b27fca4575846239bd0854933205f4b67", standard.get("sha256").getAsString());
        assertEquals("alice/layout", modules.get(1).getAsJsonObject().getAsJsonObject("identity").get("id").getAsString());
        assertEquals(JsonParser.parseString("[{\"end\":55,\"name\":\"GAP\",\"start\":37}]"), modules.get(1).getAsJsonObject().get("definitions"));
        assertTrue(modules.get(2).getAsJsonObject().get("identity").isJsonNull());
        assertEquals(JsonParser.parseString("[{\"end\":48,\"name\":\"GAP\",\"start\":30}]"), modules.get(2).getAsJsonObject().get("definitions"));
        report.add("public-personal-project-coexistence\tpass\tpass\tpass");
        Files.write(Path.of("target/rust-vocabulary-origins.tsv"), report);
    }
}
