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
import org.unlaxer.dsl.NestingLimitGrammars;
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

    /** #949: syntax of the whole file precedes mapping a retired token. */
    @Test public void regexMigrationWaitsForCompleteSyntax() throws Exception {
        checkSyntaxCases("regex", 254);
    }

    /** #986: only the namespace prefix is contiguous; the final name permits trivia. */
    @Test public void qualifiedReferenceTriviaMatchesJava() throws Exception {
        checkSyntaxCases("qualified", 36);
    }

    /** #983: mapper values never hide subsequent syntax, including later grammars. */
    @Test public void valueValidationWaitsForCompleteSyntax() throws Exception {
        checkSyntaxCases("value", 158);
    }

    private void checkSyntaxCases(String scope, int expectedCount) throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] frontend syntax priority requires -DrustConformance=true");
        assumeTrue("enable with -DrustConformance=true (requires rustc)", Boolean.getBoolean("rustConformance"));
        Path binary = inspectBinary();
        var cases = JsonParser.parseString(Files.readString(repo.resolve("spec-corpus/cli-stderr.json")))
            .getAsJsonObject().getAsJsonArray("cases");
        int checked = 0;
        for (var item : cases) {
            var row = item.getAsJsonObject();
            if (!row.get("scope").getAsString().equals(scope) || !row.has("frontend")) continue;
            for (String ending : List.of("LF", "CRLF")) {
                String source = row.getAsJsonObject("files").get("main.ubnf").getAsString()
                    .replace("\n", ending.equals("LF") ? "\n" : "\r\n");
                String name = row.get("id").getAsString() + "/" + ending;
                var expected = row.getAsJsonObject("frontend").getAsJsonObject(ending);
                Path input = temporary.newFile().toPath();
                Files.writeString(input, source);
                assertEquals(name, expected.get("native"), inspect(binary, input));
                if (expected.has("javaLimit")) {
                    var limit = expected.getAsJsonObject("javaLimit");
                    for (boolean snapshot : List.of(false, true)) {
                        var error = assertThrows(name, org.unlaxer.context.ParseLimitException.class,
                            () -> { if (snapshot) UBNFMapper.parseWithSource(source); else UBNFMapper.parse(source); });
                        assertEquals(name, limit.get("code").getAsString(), error.code());
                        assertEquals(name, limit.get("limit").getAsInt(), error.limit());
                        assertEquals(name, limit.get("offset").getAsInt(), error.offset());
                    }
                } else if (expected.has("javaAst")) {
                    assertEquals(name, expected.get("javaAst"), canonical(UBNFMapper.parse(source)));
                    assertEquals(name, expected.get("javaAst"), canonical(UBNFMapper.parseWithSource(source).ast()));
                } else {
                    var javaError = assertThrows(name, IllegalArgumentException.class, () -> UBNFMapper.parse(source));
                    assertEquals(name, expected.get("javaError").getAsString(), javaError.getMessage());
                    var snapshotError = assertThrows(name, IllegalArgumentException.class, () -> UBNFMapper.parseWithSource(source));
                    assertEquals(name, javaError.getClass(), snapshotError.getClass());
                    assertEquals(name, javaError.getMessage(), snapshotError.getMessage());
                    if (javaError instanceof org.unlaxer.dsl.bootstrap.RegexRetirement.MigrationException migration) {
                        var span = expected.getAsJsonObject("native").getAsJsonArray("span");
                        assertEquals(name, span.get(2).getAsInt(), migration.start());
                        assertEquals(name, span.get(3).getAsInt(), migration.end());
                        // Non-BMP prefixes must use CP, not UTF-16 offsets.
                        assertEquals(name, span.get(0).getAsInt(), source.substring(0,
                            source.offsetByCodePoints(0, migration.start())).getBytes(StandardCharsets.UTF_8).length);
                    }
                }
                checked++;
            }
        }
        assertEquals("shared cases must not disappear", expectedCount, checked);
    }

    @Test public void nativeFrontendMatchesJavaSyntax() throws Exception {
        if (false == Boolean.getBoolean("rustConformance")) {
            System.out.println("[assumption] RustUbnfFrontendConformanceTest skipped:"
                + " requires -DrustConformance=true and a rustc toolchain on PATH."
                + " Verified by the CI job \"Rust backend and Java conformance\".");
        }
        assumeTrue("enable with -DrustConformance=true (requires rustc)", Boolean.getBoolean("rustConformance"));
        Path crate = repo.resolve("rust/unlaxer-ubnf");
        Path binary = inspectBinary();

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
        for (String name : List.of("eval-default", "keyword-boundary", "common-field-space")) {
            positives.add(crate.resolve("tests/fixtures/known/" + name + ".ubnf"));
        }
        for (int i = 0; i < 4; i++) positives.add(repo.resolve("unlaxer-dsl/src/test/resources/evolution/" + i + "/Evolution.ubnf"));
        var report = new ArrayList<>(List.of("case\tclassification\tjava\trust"));
        for (Path path : positives) {
            String source = Files.readString(path);
            JsonObject nativeResult = inspect(binary, path);
            assertTrue(path + " " + nativeResult, nativeResult.has("ast"));
            JsonElement java = canonical(UBNFMapper.parse(source));
            assertEquals(path.toString(), java, nativeResult.get("ast"));
            assertEquals(path.toString(), java, canonical(UBNFMapper.parseWithSource(source).ast()));
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
            RuntimeException snapshotError = assertThrows(parts[0], RuntimeException.class,
                () -> UBNFMapper.parseWithSource(input));
            assertEquals(parts[0], javaError.getClass(), snapshotError.getClass());
            assertEquals(parts[0], javaError.getMessage(), snapshotError.getMessage());
            if (parts[0].startsWith("non-ascii-")) {
                // These must fail in grammar recognition, before any numeric conversion.
                assertEquals(parts[0], IllegalArgumentException.class, javaError.getClass());
                assertTrue(javaError.getMessage(), javaError.getMessage().startsWith("UBNF パース失敗"));
                // The native lexer must stop at the first non-ASCII digit itself (CP offset).
                int firstDigit = input.codePoints().boxed().toList().indexOf(input.codePoints()
                    .filter(c -> c > '9' && Character.isDigit(c)).findFirst().orElseThrow());
                assertEquals(parts[0] + " " + nativeResult, firstDigit,
                    nativeResult.getAsJsonArray("span").get(2).getAsInt());
                StringBuilder asciiDigits = new StringBuilder();
                input.codePoints().forEach(c -> asciiDigits.appendCodePoint(
                    c > '9' && Character.isDigit(c) ? '0' + Character.digit(c, 10) : c));
                String ascii = asciiDigits.toString();
                Files.writeString(source, ascii);
                JsonObject nativeAscii = inspect(binary, source);
                assertTrue(parts[0] + " " + nativeAscii, nativeAscii.has("ast"));
                assertEquals(parts[0], canonical(UBNFMapper.parse(ascii)), nativeAscii.get("ast"));
                report.add(parts[0] + "\treject\t" + javaError.getMessage() + "\t" + nativeResult);
            } else if (POSITIONED_REJECTIONS.contains(parts[0])) {
                String position = "line " + nativeResult.get("line").getAsInt()
                    + ", column " + nativeResult.get("column").getAsInt();
                assertTrue(parts[0] + ": java=" + javaError.getMessage() + " rust=" + nativeResult,
                    javaError.getMessage().contains(position));
                report.add(parts[0] + "\treject-same-position\t" + position + "\t" + nativeResult);
            } else {
                report.add(parts[0] + "\treject\trejected\t" + nativeResult);
            }
        }
        Files.write(Path.of("target/rust-ubnf-frontend.tsv"), report, StandardCharsets.UTF_8);
    }

    /**
     * #741: the frontends bound rule body nesting differently, as docs/java-parser-limits.md
     * states. The native frontend counts 128 body levels; the Java frontend has no counter and
     * stops when its 1024 parser frames run out, which depends on the nested construct. Both
     * count declarative token groups the same (128 levels). The Java part needs no toolchain.
     */
    @Test public void frontendNestingLimitsAreTheDocumentedContract() throws Exception {
        record Shape(String name, java.util.function.IntFunction<String> grammar, int javaLimit) {}
        var shapes = List.of(new Shape("groups", NestingLimitGrammars::groups, NestingLimitGrammars.JAVA_GROUPS),
            new Shape("alternatives", NestingLimitGrammars::alternatives, NestingLimitGrammars.JAVA_ALTERNATIVES));
        for (var shape : shapes) {
            int limit = shape.javaLimit();
            UBNFMapper.parse(shape.grammar().apply(limit));
            var error = assertThrows(shape.name(), org.unlaxer.context.ParseLimitException.class,
                () -> UBNFMapper.parse(shape.grammar().apply(limit + 1)));
            assertEquals("ULX-PARSE-DEPTH-LIMIT", error.code());
            assertEquals(1024, error.limit());
        }
        UBNFMapper.parse(NestingLimitGrammars.tokenGroups(NestingLimitGrammars.NATIVE_BODY_LEVELS - 1));
        var tokenError = assertThrows(IllegalArgumentException.class,
            () -> UBNFMapper.parse(NestingLimitGrammars.tokenGroups(NestingLimitGrammars.NATIVE_BODY_LEVELS)));
        assertTrue(tokenError.getMessage(), tokenError.getMessage().startsWith("UBNF パース失敗"));
        assumeTrue("enable with -DrustConformance=true (requires rustc)", Boolean.getBoolean("rustConformance"));
        Path binary = inspectBinary();
        var report = new ArrayList<>(List.of("case\tjava\trust"));
        for (var shape : shapes) {
            for (int depth = 126; depth <= 200; depth++) {
                String name = shape.name() + "-" + depth;
                Path source = temporary.newFile(name + ".ubnf").toPath();
                Files.writeString(source, shape.grammar().apply(depth));
                boolean javaAccepted = depth <= shape.javaLimit();
                if (javaAccepted) UBNFMapper.parse(Files.readString(source));
                else assertThrows(name, org.unlaxer.context.ParseLimitException.class, () -> UBNFMapper.parse(Files.readString(source)));
                String nativeError = nativeError(binary, source);
                // The rule body itself is the first of the native body levels.
                assertEquals(name, depth < NestingLimitGrammars.NATIVE_BODY_LEVELS ? null : "UBNF nesting limit exceeded", nativeError);
                report.add(name + "\t" + (javaAccepted ? "accepted" : "ULX-PARSE-DEPTH-LIMIT") + "\t"
                    + (nativeError == null ? "accepted" : nativeError));
            }
        }
        for (int depth : List.of(NestingLimitGrammars.NATIVE_BODY_LEVELS - 1, NestingLimitGrammars.NATIVE_BODY_LEVELS)) {
            Path source = temporary.newFile("token-" + depth + ".ubnf").toPath();
            Files.writeString(source, NestingLimitGrammars.tokenGroups(depth));
            assertEquals("token-" + depth, depth < NestingLimitGrammars.NATIVE_BODY_LEVELS ? null : "lexical nesting exceeds 128",
                nativeError(binary, source));
        }
        Files.write(Path.of("target/rust-ubnf-nesting-limits.tsv"), report, StandardCharsets.UTF_8);
    }

    /** The native error message, or null for an AST (too deep for Gson's JSON nesting limit). */
    private String nativeError(Path binary, Path input) throws Exception {
        String output = run(List.of(binary.toString(), input.toString()));
        if (output.startsWith("{\"ast\":")) return null;
        return JsonParser.parseString(output).getAsJsonObject().get("error").getAsString();
    }

    private Path inspectBinary() throws Exception {
        Path crate = repo.resolve("rust/unlaxer-ubnf");
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_ubnf.rlib");
        run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_ubnf",
            crate.resolve("src/lib.rs").toString(), "-o", library.toString()));
        Path binary = temporary.getRoot().toPath().resolve("inspect");
        run(List.of("rustc", "--edition=2021", crate.resolve("examples/inspect.rs").toString(),
            "--extern", "unlaxer_ubnf=" + library, "-o", binary.toString()));
        return binary;
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

    JsonElement canonical(Object value) throws Exception {
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
