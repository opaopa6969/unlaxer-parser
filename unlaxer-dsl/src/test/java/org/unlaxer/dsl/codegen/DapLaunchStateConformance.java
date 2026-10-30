package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.unlaxer.dsl.codegen.ClassicDapProtocolConformanceTest.Wire;

/** #938: independent Java stdio observations, shared by all generated adapter routes. */
final class DapLaunchStateConformance {
    private final Wire wire;
    private final String route, backend;
    private final JsonObject fixture, launch;
    private final Path program, missing;
    private final List<String> evidence = new ArrayList<>(), raw = new ArrayList<>();
    private final boolean astOnly;
    private final int sourceLength;

    static void check(Wire wire, String route, String backend, Path directory) throws Exception {
        var failures = wire.failures;
        wire.failures = null; // The #900 inventory has its own lifecycle probes.
        try { new DapLaunchStateConformance(wire, route, backend, directory).check(); }
        finally { wire.failures = failures; }
    }

    private DapLaunchStateConformance(Wire wire, String route, String backend, Path directory) throws Exception {
        this.wire = wire; this.route = route; this.backend = backend; astOnly = route.equals("classic-ast");
        fixture = JsonParser.parseString(Files.readString(Path.of("../docs/fixtures/dap-failures/launch-state.json"))).getAsJsonObject();
        launch = new JsonObject();
        String input = "p<a><b>[]x!";
        if (route.equals("classic-token")) {
            var rows = JsonParser.parseString(Files.readString(Path.of("../docs/fixtures/classic-token-dap/steps.json"))).getAsJsonArray();
            input = rows.get(0).getAsJsonObject().get("input").getAsString();
        } else if (route.equals("generated") || route.equals("projected")) {
            var rows = JsonParser.parseString(Files.readString(Path.of("../docs/fixtures/"+route+"-token-dap/cases.json"))).getAsJsonArray();
            input = rows.asList().stream().map(JsonElement::getAsJsonObject).filter(r -> r.get("name").getAsString().equals("trace-order")).findFirst().orElseThrow().get("input").getAsString();
            launch.addProperty("fixture", "trace-order");
        }
        sourceLength = input.codePointCount(0, input.length());
        program = directory.resolve("launch-state-"+backend+".txt"); Files.writeString(program, input);
        missing = directory.resolve("missing-launch-state-"+backend+".txt"); assertFalse(Files.exists(missing));
        launch.addProperty("program", program.toString());
        launch.addProperty("runtimeMode", "prepared-runtime");
        launch.addProperty("steppingMode", astOnly ? "AsT" : "ToKeN");
        launch.addProperty("stopOnEntry", true); launch.addProperty("lineOffset", 7);
    }

    private void check() throws Exception {
        Path out = Path.of("target/dap-launch-state-"+route+"-"+backend);
        Files.deleteIfExists(Path.of(out+".tsv"));
        try {
            assertEquals(8, fixture.getAsJsonArray("programs").size());
            assertEquals(12, fixture.getAsJsonArray("arrayRequests").size());
            assertEquals(List.of(2, 3), fixture.getAsJsonArray("arrayLengths").asList().stream().map(JsonElement::getAsInt).toList());
            matrix("first-launch", fixture.getAsJsonArray("freshVariables"), 0);
            prepare();
            matrix("relaunch-from-stopped", retainedVariables(), 7);
            arrays("pending");
            prepare();
            arrays("stopped");
            finish();
            matrix("relaunch-from-terminated", retainedVariables(), 7);
            failAfterResolve();
            // Leave the existing lifecycle suites with no configuration pending.
            prepare(); finish();
            int sessions = (astOnly ? 1 : 2) * 8 * 2 * 2 * 3;
            assertEquals(sessions, evidence.stream().filter(s -> s.startsWith("pending\t")).count());
            assertEquals(48, evidence.stream().filter(s -> s.startsWith("array\t")).count());
            assertEquals(1, evidence.stream().filter(s -> s.startsWith("resolved-failure\t")).count());
            Files.write(Path.of(out+".tsv"), evidence);
        } finally { Files.write(Path.of(out+"-raw.tsv"), raw); }
    }

    private void prepare() throws Exception {
        wire.request("initialize", Map.of("linesStartAt1", true, "columnsStartAt1", true));
        wire.request("launch", launch); wire.event("initialized");
        wire.request("setBreakpoints", Map.of("breakpoints", List.of()));
        wire.request("configurationDone", Map.of()); wire.event("stopped");
    }

    private void finish() throws Exception {
        wire.request("continue", Map.of("threadId", 1));
        wire.event("output"); wire.event("terminated"); wire.event("exited");
        assertTrue(wire.request("stackTrace", Map.of("threadId", 1)).getAsJsonArray("stackFrames").isEmpty());
        assertEquals(retainedVariables(), wire.request("variables", Map.of("variablesReference", 1)).get("variables"));
    }

    /** Java assigns sourceLineOffset once the source resolves, before parsing or AST steps. */
    private void failAfterResolve() throws Exception {
        wire.request("initialize", Map.of("linesStartAt1", true, "columnsStartAt1", true));
        var args = launch.deepCopy(); args.addProperty("lineOffset", 3);
        // Classic hosts parse the resolved sourceText; the token-only fixtures have no AST steps.
        if (route.startsWith("classic")) args.addProperty("sourceText", "#"); else args.addProperty("steppingMode", "ast");
        wire.request("launch", args); wire.event("initialized");
        wire.request("configurationDone", Map.of());
        assertEquals("stderr", wire.event("output").get("category").getAsString());
        wire.event("terminated"); wire.event("exited");
        var relaunch = launch.deepCopy(); relaunch.addProperty("program", missing.toString()); relaunch.addProperty("lineOffset", 99);
        wire.request("launch", relaunch); wire.event("initialized");
        var frames = new JsonArray();
        if (!astOnly) {
            var frame = fixture.getAsJsonObject("placeholder").deepCopy();
            frame.getAsJsonObject("source").addProperty("path", missing.toString());
            frame.addProperty("line", 4); frames.add(frame);
        }
        assertEquals("failed preparation keeps its resolved offset", frames, success("resolved-failure", "stackTrace", Map.of("threadId", 1)).get("stackFrames"));
        assertEquals("failed preparation keeps the previous probes", retainedVariables(), success("resolved-failure", "variables", Map.of("variablesReference", 1)).get("variables"));
        barrier();
        evidence.add("resolved-failure\t"+frames+"\t"+retainedVariables());
    }

    private JsonArray retainedVariables() {
        var variables = fixture.getAsJsonArray("retainedVariables").deepCopy();
        if (astOnly) {
            variables.get(1).getAsJsonObject().addProperty("value", "AsT");
            for (var entry : List.of(Map.entry("runtimeLabel", "prepared-runtime"), Map.entry("sourceLength", Integer.toString(sourceLength)))) {
                var value = variables.get(0).deepCopy().getAsJsonObject();
                value.addProperty("name", entry.getKey()); value.addProperty("value", entry.getValue()); variables.add(value);
            }
        }
        return variables;
    }

    private JsonElement resolve(JsonElement value) {
        return JsonParser.parseString(value.toString().replace("$EXISTING", program.toString()).replace("$MISSING", missing.toString()));
    }

    private void matrix(String phase, JsonArray variables, int previousOffset) throws Exception {
        for (boolean base : List.of(true, false)) for (boolean argumentsFirst : List.of(true, false))
            for (String mode : astOnly ? List.of("ast") : List.of("token", "ast")) for (var element : fixture.getAsJsonArray("programs")) {
                var row = element.getAsJsonObject();
                wire.request("initialize", Map.of("linesStartAt1", base, "columnsStartAt1", base));
                var args = launch.deepCopy(); args.add("program", resolve(row.get("value")));
                args.addProperty("runtimeMode", "new-runtime"); args.addProperty("steppingMode", mode);
                args.addProperty("lineOffset", 99); // Not resolved until configurationDone.
                var response = wire.responseOrdered("launch", args, true, argumentsFirst);
                assertTrue(response.toString(), response.get("success").getAsBoolean()); assertFalse(response.has("body"));
                wire.event("initialized");
                String jdk = Integer.toString(Runtime.version().feature());
                String path = resolve(backend.equals("java") && row.has("pathByJdk") && row.getAsJsonObject("pathByJdk").has(jdk)
                    ? row.getAsJsonObject("pathByJdk").get(jdk) : row.get("path")).getAsString();
                var frames = new JsonArray();
                if (mode.equals("token")) {
                    var frame = fixture.getAsJsonObject("placeholder").deepCopy();
                    frame.getAsJsonObject("source").addProperty("path", path);
                    frame.addProperty("line", previousOffset+(base ? 1 : 0)); frame.addProperty("column", base ? 1 : 0); frames.add(frame);
                }
                String label = phase+"/"+base+"/"+argumentsFirst+"/"+mode+"/"+row.get("id").getAsString();
                assertEquals(label, frames, success(label, "stackTrace", Map.of("threadId", 1)).get("stackFrames"));
                assertEquals(label, fixture.get("scopes"), success(label, "scopes", Map.of("frameId", 0)));
                assertEquals(label, fixture.get("scopes"), success(label, "scopes", Map.of()));
                assertEquals(label, variables, success(label, "variables", Map.of("variablesReference", 1)).get("variables"));
                assertEquals(label, fixture.get("threads"), success(label, "threads", Map.of()));
                for (var request : List.of(Map.entry("stackTrace", Map.of("threadId", 2)), Map.entry("scopes", Map.of("frameId", 1)),
                        Map.entry("variables", Map.of("variablesReference", 0)), Map.entry("variables", Map.of("variablesReference", 2)),
                        Map.entry("next", Map.of("threadId", 1)), Map.entry("continue", Map.of("threadId", 1)))) {
                    var failure = wire.response(request.getKey(), request.getValue());
                    raw.add(label+"\t"+request.getKey()+"\t"+failure);
                    DapFailureConformance.assertError(failure, fixture.get("invalidRequest").getAsString());
                }
                assertEquals("failed steps preserve pending display", frames, success(label, "stackTrace", Map.of("threadId", 1)).get("stackFrames"));
                assertEquals(variables, success(label, "variables", Map.of("variablesReference", 1)).get("variables"));
                barrier();
                evidence.add("pending\t"+label+"\t"+frames+"\t"+variables);
            }
    }

    private JsonObject success(String label, String command, Object args) throws Exception {
        var response = wire.response(command, args); raw.add(label+"\t"+command+"\t"+response);
        assertTrue(label+": "+response, response.get("success").getAsBoolean()); assertFalse(response.has("message"));
        return response.getAsJsonObject("body");
    }

    private List<JsonObject> state() throws Exception {
        return List.of(wire.request("stackTrace", Map.of("threadId", 1)), wire.request("scopes", Map.of("frameId", 0)),
            wire.request("variables", Map.of("variablesReference", 1)));
    }

    private void arrays(String phase) throws Exception {
        var before = state();
        for (var element : fixture.getAsJsonArray("arrayRequests")) for (var length : fixture.getAsJsonArray("arrayLengths")) {
            var row = element.getAsJsonObject(); String command = row.get("command").getAsString();
            var first = resolve(row.get("arguments")).getAsJsonObject();
            if (command.equals("launch") && astOnly) first.addProperty("steppingMode", "ast");
            var args = new JsonArray(); args.add(first);
            for (int i = 1; i < length.getAsInt(); i++) args.add(new JsonObject());
            String label = phase+"/"+command+"/"+length;
            if (backend.equals("java")) {
                wire.sendOrdered(command, args, true, false);
                raw.add(label+"\tsent\t"+wire.lastPayload);
                evidence.add("array\t"+label+"\tno-response");
            } else {
                var response = wire.responseOrdered(command, args, true, false);
                raw.add(label+"\t"+response);
                assertFalse(response.toString(), response.get("success").getAsBoolean());
                assertEquals(fixture.getAsJsonObject("arrayFailure").get("message"), response.get("message"));
                int start = wire.lastPayload.indexOf("\"arguments\":[") + "\"arguments\":[".length();
                int column = start + new Gson().toJson(first).length() + 3;
                String diagnostic = fixture.getAsJsonObject("arrayFailure").get("diagnostic").getAsString().replace("{column}", Integer.toString(column));
                assertEquals(new Gson().toJsonTree(Map.of("message", diagnostic)), response.get("body"));
                evidence.add("array\t"+label+"\t"+response.get("message")+"\t"+response.get("body"));
            }
        }
        if (backend.equals("java")) {
            assertNull("Java must emit neither a response nor a lifecycle event", wire.readWithin(fixture.get("noResponseTestMillis").getAsLong()));
            assertTrue("Java transport stays open", wire.process.isAlive());
        }
        barrier();
        assertEquals("array failures must not dispatch the first argument", before, state());
        // A one-element method-argument array still dispatches normally.
        var response = wire.responseOrdered("stackTrace", List.of(Map.of("threadId", 1)), true, false);
        assertTrue(response.toString(), response.get("success").getAsBoolean()); assertEquals(before.get(0), response.get("body"));
        barrier();
    }

    private void barrier() throws Exception {
        assertEquals(fixture.get("threads"), wire.request("threads", Map.of()));
        assertTrue("unexpected response or lifecycle event: "+wire.pending, wire.pending.isEmpty());
    }
}
