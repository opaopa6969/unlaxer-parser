package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFAST;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/** Native frontend comparison only: no Rust backend assumptions or Java class loading. */
public class RustUbnfFrontendConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void nativeFrontendMatchesJavaSyntaxAndRecordsKnownMapperDifferences() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)", Boolean.getBoolean("rustConformance"));
        Path crate = repo.resolve("rust/unlaxer-ubnf");
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_ubnf.rlib");
        run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_ubnf",
            crate.resolve("src/lib.rs").toString(), "-o", library.toString()));
        Path binary = temporary.getRoot().toPath().resolve("inspect");
        run(List.of("rustc", "--edition=2021", crate.resolve("examples/inspect.rs").toString(),
            "--extern", "unlaxer_ubnf=" + library, "-o", binary.toString()));

        List<Path> positives;
        try (var paths = Files.list(crate.resolve("tests/fixtures/positive"))) {
            positives = new ArrayList<>(paths.sorted().toList());
        }
        positives.add(repo.resolve("unlaxer-dsl/grammar/ubnf.ubnf"));
        positives.add(repo.resolve("unlaxer-dsl/tinycalc-vscode/grammar/tinycalc.ubnf"));
        positives.add(repo.resolve("unlaxer-dsl/src/test/resources/cardinality/Cardinality.ubnf"));
        for (int i = 0; i < 4; i++) positives.add(repo.resolve("unlaxer-dsl/src/test/resources/evolution/" + i + "/Evolution.ubnf"));
        var report = new ArrayList<>(List.of("case\tclassification\tjava\trust"));
        for (Path path : positives) {
            String source = Files.readString(path);
            JsonObject nativeResult = inspect(binary, path);
            assertTrue(path + " " + nativeResult, nativeResult.has("ast"));
            JsonElement java = canonical(UBNFMapper.parse(source));
            assertEquals(path.toString(), java, nativeResult.get("ast"));
            report.add(path.getFileName() + "\tequal\t" + java + "\t" + nativeResult);
        }
        for (String line : Files.readAllLines(crate.resolve("tests/fixtures/negative.tsv"))) {
            String[] parts = line.split("\t", 2);
            String input = parts.length == 1 ? "" : parts[1];
            Path source = temporary.newFile().toPath();
            Files.writeString(source, input);
            JsonObject nativeResult = inspect(binary, source);
            assertTrue(parts[0] + " " + nativeResult, nativeResult.has("error"));
            if (parts[0].equals("trailing-input")) {
                assertEquals(1, UBNFMapper.parse(input).grammars().size());
                report.add(parts[0] + "\tknown-prefix-acceptance\taccepted-prefix\t" + nativeResult);
            } else {
                assertThrows(parts[0], RuntimeException.class, () -> UBNFMapper.parse(input));
                report.add(parts[0] + "\treject\trejected\t" + nativeResult);
            }
        }
        // These are isolated Java frontend defects, not hidden normalizations in the equality check.
        for (String name : List.of("eval-default", "namespace", "keyword-boundary", "common-field-space")) {
            Path path = crate.resolve("tests/fixtures/known/" + name + ".ubnf");
            JsonObject nativeResult = inspect(binary, path);
            assertTrue(nativeResult.toString(), nativeResult.has("ast"));
            if (name.equals("keyword-boundary") || name.equals("common-field-space")) {
                assertThrows(name, IllegalArgumentException.class, () -> UBNFMapper.parse(Files.readString(path)));
                report.add(name + "\tknown-java-rejection\trejected\t" + nativeResult);
                continue;
            }
            UBNFAST.UBNFFile java = UBNFMapper.parse(Files.readString(path));
            assertNotEquals(name, canonical(java), nativeResult.get("ast"));
            if (name.equals("eval-default")) {
                var annotation = (UBNFAST.EvalAnnotation) java.grammars().get(0).rules().get(0).annotations().get(0);
                assertEquals("$", annotation.strategy());
            } else if (name.equals("namespace")) {
                var body = (UBNFAST.ChoiceBody) java.grammars().get(0).rules().get(0).body();
                var reference = (UBNFAST.RuleRefElement) body.alternatives().get(0).elements().get(0).element();
                assertEquals(Optional.of("a"), reference.namespace());
                assertEquals("b", reference.name());
            }
            report.add(name + "\tknown-difference\t" + canonical(java) + "\t" + nativeResult);
        }
        Files.write(Path.of("target/rust-ubnf-frontend.tsv"), report, StandardCharsets.UTF_8);
    }

    private JsonObject inspect(Path binary, Path input) throws Exception {
        return JsonParser.parseString(run(List.of(binary.toString(), input.toString()))).getAsJsonObject();
    }

    private String run(List<String> command) throws Exception {
        Path log = temporary.newFile().toPath();
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("process timed out: " + command);
        }
        String output = Files.readString(log);
        assertEquals(command + "\n" + output, 0, process.exitValue());
        return output;
    }

    private JsonElement canonical(Object value) throws Exception {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof Optional<?> optional) return canonical(optional.orElse(null));
        if (value instanceof String string) return new JsonPrimitive(string);
        if (value instanceof Character character) return new JsonPrimitive(character);
        if (value instanceof Number number) return new JsonPrimitive(number);
        if (value instanceof Enum<?> enumeration) return new JsonPrimitive(enumeration.name());
        if (value instanceof List<?> list) {
            JsonArray result = new JsonArray();
            for (Object item : list) result.add(canonical(item));
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject result = new JsonObject();
            for (var entry : map.entrySet()) result.add(entry.getKey().toString(), canonical(entry.getValue()));
            return result;
        }
        // Postfix ?/* use a synthetic SequenceBody in Java; explicit brackets use ChoiceBody.
        if (value instanceof UBNFAST.RuleBody body) {
            JsonArray result = new JsonArray(); result.add("body");
            JsonArray alternatives = new JsonArray();
            if (body instanceof UBNFAST.ChoiceBody choice) {
                for (var sequence : choice.alternatives()) alternatives.add(canonical(sequence.elements()));
            } else alternatives.add(canonical(((UBNFAST.SequenceBody) body).elements()));
            result.add(alternatives); return result;
        }
        assertTrue(value.toString(), value.getClass().isRecord());
        JsonArray result = new JsonArray(); result.add(value.getClass().getSimpleName());
        for (var component : value.getClass().getRecordComponents()) result.add(canonical(component.getAccessor().invoke(value)));
        return result;
    }
}
