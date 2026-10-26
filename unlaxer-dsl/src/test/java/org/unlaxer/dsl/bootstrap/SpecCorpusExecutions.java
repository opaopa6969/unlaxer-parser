package org.unlaxer.dsl.bootstrap;

import static org.junit.Assert.*;
import com.google.gson.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;

/** #784 の実行先宣言。fixture は複製せず、frontend と全実行 driver が同じ原本を読む。 */
public final class SpecCorpusExecutions {
    private SpecCorpusExecutions() {}
    public static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path CORPUS = ROOT.resolve("unlaxer-dsl/src/test/resources/spec-corpus/ubnf");

    public static JsonObject manifest() {
        return read(ROOT.resolve("spec-corpus/execution/manifest.json"));
    }
    private static JsonObject read(Path path) {
        try { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public static JsonObject original(JsonObject row) {
        var matches = read(CORPUS.resolve(row.get("file").getAsString())).getAsJsonArray("cases").asList().stream()
            .map(JsonElement::getAsJsonObject).filter(c -> c.get("id").equals(row.get("id"))).toList();
        assertEquals(row.toString(), 1, matches.size());
        assertEquals(row.get("scope"), matches.get(0).get("scope"));
        return matches.get(0);
    }
    public static List<JsonObject> rows(String scope) {
        var data = manifest();
        var declared = new TreeSet<String>();
        var selected = new ArrayList<JsonObject>();
        for (var raw : data.getAsJsonArray("cases")) {
            var row = raw.getAsJsonObject();
            var id = row.get("id").getAsString();
            assertTrue("実行先 ID 重複: " + id, declared.add(id));
            original(row);
            var kind = row.get("scope").getAsString();
            assertTrue(kind, Set.of("validate", "runtime").contains(kind));
            assertEquals(List.of("java-" + kind, "java-host-" + kind, "native-" + kind),
                row.getAsJsonArray("drivers").asList().stream().map(JsonElement::getAsString).toList());
            for (var driver : row.getAsJsonArray("drivers")) {
                var destination = data.getAsJsonObject("drivers").getAsJsonObject(driver.getAsString());
                assertNotNull(driver.toString(), destination);
                assertTrue(destination.toString(), Files.isRegularFile(ROOT.resolve(destination.get("path").getAsString())));
            }
            if (kind.equals(scope)) selected.add(row);
        }
        var actual = new TreeSet<String>();
        try (var paths = Files.list(CORPUS)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".json")).toList())
                for (var raw : read(path).getAsJsonArray("cases")) {
                    var row = raw.getAsJsonObject();
                    if (Set.of("validate", "runtime").contains(row.get("scope").getAsString()))
                        assertTrue(actual.add(row.get("id").getAsString()));
                }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        assertEquals("新規除外 corpus は実行先の宣言が必要", actual, declared);
        return selected;
    }
    public static Set<String> runtimeIds() {
        var result = new TreeSet<String>();
        for (var row : rows("runtime")) result.add(row.get("id").getAsString());
        return result;
    }
}
