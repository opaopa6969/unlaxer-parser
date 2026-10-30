package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.unlaxer.dsl.codegen.ClassicDapProtocolConformanceTest.Wire;

/** Independent request/error oracle, shared by the actual generated stdio adapters. */
final class DapFailureConformance {
    private final String route, backend;
    private final List<String> raw = new ArrayList<>();
    private final Set<String> checked = new HashSet<>();
    private final List<String> evidence = new ArrayList<>(List.of("stage\tcommand\targuments\tresponse_shape"));
    DapFailureConformance(String route, String backend) { this.route = route; this.backend = backend; }
    void check(Wire wire, String stage) throws Exception {
        if (!checked.add(stage)) return;
        var rows = JsonParser.parseString(Files.readString(Path.of("../docs/fixtures/dap-failures/requests.json"))).getAsJsonArray();
        assertEquals("shared failure inventory", 261, rows.size());
        for (var element : rows) {
            var row = element.getAsJsonObject();
            if (!stage.equals(row.get("stage").getAsString())) continue;
            var response = wire.response(row.get("command").getAsString(), row.get("arguments"), !row.has("omitArguments"));
            assertEquals(route+"/"+backend+" "+row+" => "+response, row.get("success"), response.get("success"));
            raw.add(stage+"\t"+row.get("arguments")+"\t"+response);
            var shape = shape(response);
            if (!row.get("success").getAsBoolean()) {
                assertEquals(row.toString(), row.get("message"), shape.get("message"));
                assertEquals(row.toString(), row.get("bodyType"), shape.get("bodyType"));
                if (row.has("exceptionType")) {
                    assertEquals(row.toString(), row.get("exceptionType").getAsString(), shape.get("firstLine").getAsString().split(":", 2)[0]);
                } else assertEquals(row.toString(), row.get("firstLine"), shape.get("firstLine"));
            }
            if (row.get("command").getAsString().equals("setBreakpoints") && row.get("success").getAsBoolean()) {
                var breakpoints = response.getAsJsonObject("body").getAsJsonArray("breakpoints");
                assertEquals(3, breakpoints.size());
                for (int i = 0; i < 3; i++) assertEquals(i == 0 ? -1 : 0, breakpoints.get(i).getAsJsonObject().get("line").getAsInt());
                shape.add("breakpoints", breakpoints);
            }
            evidence.add(stage+"\t"+row.get("command").getAsString()+"\t"+(row.has("omitArguments") ? "<omitted>" : row.get("arguments"))+"\t"+shape);
        }
        // Barrier: an error must not emit a late stopped/terminated event or move the step.
        wire.request("threads", Map.of());
        assertTrue("failure requests emitted events: "+wire.pending, wire.pending.isEmpty());
        Files.write(Path.of("target/dap-failures-"+route+"-"+backend+".tsv"), evidence);
        Files.write(Path.of("target/dap-failures-"+route+"-"+backend+"-raw.tsv"), raw);
    }
    void complete() throws Exception {
        assertEquals(Set.of("fresh", "pending", "stopped", "terminated"), checked);
        // Each backend independently matched the fixture; compare their evidence as well.
        if (backend.startsWith("rust")) {
            var javaRows = Files.readAllLines(Path.of("target/dap-failures-"+route+"-java.tsv"));
            assertEquals(javaRows.size(), evidence.size());
            assertEquals(javaRows.get(0), evidence.get(0));
            for (int i = 1; i < evidence.size(); i++) {
                String[] expected = javaRows.get(i).split("\\t", 4), actual = evidence.get(i).split("\\t", 4);
                for (int field = 0; field < 3; field++) assertEquals("inventory row "+i, expected[field], actual[field]);
                var javaShape = JsonParser.parseString(expected[3]).getAsJsonObject();
                var rustShape = JsonParser.parseString(actual[3]).getAsJsonObject();
                if (javaShape.has("firstLine") && javaShape.get("firstLine").getAsString().startsWith("java.lang.NullPointerException")) {
                    // Helpful NPE wording names JVM methods/parameters; only its type is portable.
                    for (var shape : List.of(javaShape, rustShape)) shape.addProperty("firstLine", shape.get("firstLine").getAsString().split(":", 2)[0]);
                }
                assertEquals("failure inventory row "+i, javaShape, rustShape);
            }
        }
    }
    static JsonObject shape(JsonObject response) {
        var out = new JsonObject(); out.add("success", response.get("success"));
        if (!response.get("success").getAsBoolean()) {
            out.add("message", response.get("message"));
            var body = response.get("body");
            String type = body == null ? "absent" : body.isJsonNull() ? "null" : body.isJsonPrimitive() && body.getAsJsonPrimitive().isString() ? "string" : "other";
            out.addProperty("bodyType", type);
            if (type.equals("string")) out.addProperty("firstLine", body.getAsString().lines().findFirst().orElse(""));
        }
        return out;
    }
    static void invalidRequest(JsonObject response) {
        assertError(response, "java.util.concurrent.CompletionException: java.lang.IllegalArgumentException: invalid or unavailable debug request");
    }
    static void assertError(JsonObject response, String firstLine) {
        assertFalse(response.toString(), response.get("success").getAsBoolean());
        var shape = shape(response);
        assertEquals("Internal error.", shape.get("message").getAsString());
        assertEquals("string", shape.get("bodyType").getAsString());
        assertEquals(firstLine, shape.get("firstLine").getAsString());
    }
}
