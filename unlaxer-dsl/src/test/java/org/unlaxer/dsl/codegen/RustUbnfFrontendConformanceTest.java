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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFAST;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/**
 * Native frontend comparison only: no Rust backend assumptions or Java class loading.
 *
 * <p><b>This test only runs when {@code -DrustConformance=true} is passed and a {@code rustc}
 * toolchain is on {@code PATH}.</b> A plain local {@code mvn test} skips it, so in practice it is
 * verified by the CI job "Rust backend and Java conformance" (see {@code .github/workflows/maven.yml}).
 * To run it locally: {@code mvn -pl unlaxer-common,unlaxer-dsl -am test
 * -Dtest=RustUbnfFrontendConformanceTest -DrustConformance=true}. The skip reason is printed to
 * stdout so a skipped run is visible in the surefire output instead of silently passing (#282).
 */
public class RustUbnfFrontendConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    /**
     * negative.tsv cases where both frontends must not only reject, but reject at the same
     * 1-based line/column. #282: the spec's {@code UBNFFile ::= { '//' LINE_COMMENT } GrammarDecl+}
     * leaves no room for residual input, so trailing garbage is a positioned error on both sides.
     */
    private static final Set<String> POSITIONED_REJECTIONS = Set.of("trailing-input", "trailing-input-no-space");

    @Test public void nativeFrontendMatchesJavaSyntaxAndRecordsKnownMapperDifferences() throws Exception {
        if (false == Boolean.getBoolean("rustConformance")) {
            System.out.println("[assumption] RustUbnfFrontendConformanceTest skipped:"
                + " requires -DrustConformance=true and a rustc toolchain on PATH."
                + " Verified by the CI job \"Rust backend and Java conformance\".");
        }
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
        // Issue #284: chained dotted RuleRef (a.b.Value) used to be a known Java mapper
        // defect (UBNFMapper only kept the first two identifiers). Now fixed, so this
        // fixture is checked for full AST equality like every other positive case.
        positives.add(crate.resolve("tests/fixtures/known/namespace.ubnf"));
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
            RuntimeException javaError = assertThrows(parts[0], RuntimeException.class, () -> UBNFMapper.parse(input));
            if (POSITIONED_REJECTIONS.contains(parts[0])) {
                String position = "line " + nativeResult.get("line").getAsInt()
                    + ", column " + nativeResult.get("column").getAsInt();
                assertTrue(parts[0] + ": java=" + javaError.getMessage() + " rust=" + nativeResult,
                    javaError.getMessage().contains(position));
                report.add(parts[0] + "\treject-same-position\t" + position + "\t" + nativeResult);
            } else {
                report.add(parts[0] + "\treject\trejected\t" + nativeResult);
            }
        }
        // These are isolated Java frontend defects, not hidden normalizations in the equality check.
        for (String name : List.of("eval-default", "keyword-boundary", "common-field-space")) {
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
