package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.unlaxer.dsl.codegen.ClassicDapProtocolConformanceTest.Wire;

/** Java stdio measurements, independent of Rust, shared by every generated DAP route. */
final class DapArgumentConformance {
    private final Wire wire;
    private final String route, backend;
    private final JsonObject launch;
    private final List<String> evidence = new ArrayList<>();
    private final List<String> raw = new ArrayList<>();

    static void check(Wire wire, String route, String backend, Path directory) throws Exception {
        new DapArgumentConformance(wire, route, backend, directory).check();
    }

    private DapArgumentConformance(Wire wire, String route, String backend, Path directory) throws Exception {
        this.wire = wire; this.route = route; this.backend = backend;
        String input = "p<a><b>[]x!";
        launch = new JsonObject();
        launch.addProperty("runtimeMode", route.equals("classic-ast") ? "inspection-ast" : "inspection-token");
        launch.addProperty("stopOnEntry", true);
        if (route.equals("classic-token")) {
            var rows = JsonParser.parseString(Files.readString(Path.of("../docs/fixtures/classic-token-dap/steps.json"))).getAsJsonArray();
            input = rows.get(0).getAsJsonObject().get("input").getAsString();
        } else if (route.equals("generated") || route.equals("projected")) {
            var rows = JsonParser.parseString(Files.readString(Path.of("../docs/fixtures/"+route+"-token-dap/cases.json"))).getAsJsonArray();
            var row = rows.asList().stream().map(JsonElement::getAsJsonObject).filter(r -> r.get("name").getAsString().equals("trace-order")).findFirst().orElseThrow();
            input = row.get("input").getAsString();
            launch.addProperty("fixture", "trace-order");
        }
        Path program = directory.resolve("arguments-"+backend+".txt");
        Files.writeString(program, input);
        launch.addProperty("program", program.toString());
    }

    private void check() throws Exception {
        var fixture = JsonParser.parseString(Files.readString(Path.of("../docs/fixtures/dap-failures/arguments.json"))).getAsJsonObject();
        assertEquals(54, fixture.getAsJsonArray("numbers").size());
        assertEquals(111, fixture.getAsJsonArray("shapes").size());
        assertEquals(102, fixture.getAsJsonArray("launches").size());
        for (boolean argumentsFirst : List.of(true, false)) for (var element : fixture.getAsJsonArray("numbers")) {
            var row = element.getAsJsonObject().deepCopy();
            if (!argumentsFirst) {
                row.remove("int32"); row.remove("error");
                if (row.has("streamInt32")) row.add("int32", row.get("streamInt32"));
                else row.add("error", row.get("streamError"));
            }
            for (String command : List.of("stackTrace", "scopes", "variables", "next", "continue", "setBreakpoints")) {
                prepare();
                var before = state();
                String field = switch (command) {
                    case "scopes" -> "frameId";
                    case "variables" -> "variablesReference";
                    case "setBreakpoints" -> "line";
                    default -> "threadId";
                };
                var args = new JsonObject(); args.add(field, row.get("value"));
                if (command.equals("setBreakpoints")) {
                    var array = new JsonArray(); array.add(args); args = new JsonObject(); args.add("breakpoints", array);
                }
                var response = wire.responseOrdered(command, args, true, argumentsFirst);
                boolean success = !row.has("error") && (command.equals("setBreakpoints") || row.get("int32").getAsInt() == (command.equals("scopes") ? 0 : 1));
                var expected = new JsonObject(); expected.addProperty("success", success);
                expected.add("message", success ? JsonNull.INSTANCE : new JsonPrimitive(row.has("error") ? "Message could not be parsed." : "Internal error."));
                expected.addProperty("bodyType", row.has("error") || success && !command.equals("next") ? "object" : success ? "absent" : "string");
                if (row.has("error")) {
                    String error = row.get("error").getAsString();
                    if (argumentsFirst) error = error.replace("$.breakpoints[0].line", command.equals("setBreakpoints") ? "$.breakpoints[0].line" : "$."+field);
                    else {
                        String encoded = new Gson().toJson(row.get("value"));
                        int end = wire.lastPayload.lastIndexOf(encoded) + encoded.length();
                        int position = row.get("value").isJsonArray() || row.get("value").isJsonObject() ? end - encoded.length() + 1 : end;
                        error = error.replace("{column}", Integer.toString(position+1)).replace("{path}", command.equals("setBreakpoints") ? "$.arguments.breakpoints[0].line" : "$.arguments."+field);
                    }
                    expected.addProperty("bodyMessage", error);
                }
                else if (!success) expected.addProperty("firstLine", "java.util.concurrent.CompletionException: java.lang.IllegalArgumentException: invalid or unavailable debug request");
                observe((argumentsFirst ? "tree/" : "stream/")+row.get("id").getAsString()+"/"+command, response, expected);
                if (success && command.equals("setBreakpoints")) {
                    assertEquals(row.get("int32"), response.getAsJsonObject("body").getAsJsonArray("breakpoints").get(0).getAsJsonObject().get("line"));
                } else if (success && command.equals("next")) {
                    assertEquals("step", wire.event("stopped").get("reason").getAsString());
                    String firstName = before.get(0).getAsJsonArray("stackFrames").get(0).getAsJsonObject().get("name").getAsString();
                    String nextName = state().get(0).getAsJsonArray("stackFrames").get(0).getAsJsonObject().get("name").getAsString();
                    assertTrue("next advances exactly once: "+nextName, nextName.endsWith(firstName.substring(firstName.lastIndexOf(" (1/")).replace(" (1/", " (2/")));
                } else if (success && command.equals("continue")) {
                    assertTrue(response.getAsJsonObject("body").get("allThreadsContinued").getAsBoolean());
                    wire.event("output"); wire.event("terminated"); wire.event("exited");
                    assertTrue(wire.request("stackTrace", Map.of("threadId", 1)).getAsJsonArray("stackFrames").isEmpty());
                } else {
                    if (success) assertEquals(before.get(command.equals("stackTrace") ? 0 : command.equals("scopes") ? 1 : 2), response.get("body"));
                    assertEquals("rejected requests keep the stopped state", before, state());
                }
                barrier();
            }
        }
        for (boolean argumentsFirst : List.of(true, false)) for (var element : fixture.getAsJsonArray("shapes")) {
            prepare();
            var row = element.getAsJsonObject();
            if (!argumentsFirst && !row.has("streamExpected")) continue;
            var before = state();
            var response = wire.responseOrdered(row.get("command").getAsString(), row.get("arguments"), true, argumentsFirst);
            observe((argumentsFirst ? "tree/" : "stream/")+row.get("id").getAsString(), response, expected(row, argumentsFirst));
            if (!response.get("success").getAsBoolean()) assertEquals(before, state());
            barrier();
        }
        for (boolean argumentsFirst : List.of(true, false)) for (var element : fixture.getAsJsonArray("launches")) {
            prepare();
            var before = state();
            var row = element.getAsJsonObject();
            if (!argumentsFirst && !row.has("streamExpected")) continue;
            var args = row.get("arguments").deepCopy();
            // Classic AST's fixture backend exposes only AST steps. The conversion
            // oracle is identical; an explicit valid mode keeps its launch usable.
            if (route.equals("classic-ast") && args.isJsonObject() && !args.getAsJsonObject().has("steppingMode")) args.getAsJsonObject().addProperty("steppingMode", "ast");
            if (route.equals("classic-ast") && args.isJsonArray() && row.has("programPath")) {
                var pair = new JsonArray(); pair.add("steppingMode"); pair.add("ast"); args.getAsJsonArray().add(pair);
            }
            var response = wire.responseOrdered("launch", args, true, argumentsFirst);
            observe((argumentsFirst ? "tree/" : "stream/")+row.get("id").getAsString(), response, expected(row, argumentsFirst));
            if (response.get("success").getAsBoolean()) {
                wire.event("initialized");
                assertTrue("measured program path is required", row.has("programPath"));
                var breakpoint = wire.request("setBreakpoints", Map.of("source", Map.of("path", row.get("programPath").getAsString()), "breakpoints", List.of(Map.of("line", 1))));
                assertTrue("program stringification: "+row, breakpoint.getAsJsonArray("breakpoints").get(0).getAsJsonObject().get("verified").getAsBoolean());
            } else assertEquals("failed launch preserves the current session", before, state());
            barrier();
        }
        assertEquals(15, fixture.getAsJsonArray("literals").size());
        for (var element : fixture.getAsJsonArray("literals")) {
            prepare(); var row = element.getAsJsonObject();
            if (row.has("launchProgram")) {
                var args = launch.deepCopy(); args.add("program", row.get("launchProgram"));
                wire.request("launch", args); wire.event("initialized");
            }
            var response = wire.responseLiteral(row.get("raw").getAsString());
            observe(row.get("id").getAsString(), response, row.getAsJsonObject("expected"));
            if (row.has("body")) assertEquals(row.get("body"), response.get("body"));
            barrier();
        }
        // One value per field of every LSP4J argument DTO, including the default
        // methods the generated adapter does not implement. These responses do not
        // depend on the session, and a rejected conversion must not change it.
        assertEquals(557, fixture.getAsJsonArray("requests").size());
        prepare();
        var stopped = state();
        for (var element : fixture.getAsJsonArray("requests")) {
            var row = element.getAsJsonObject();
            observe(row.get("id").getAsString(), wire.responseLiteral(row.get("raw").getAsString()), row.getAsJsonObject("expected"));
        }
        assertEquals("rejected requests keep the stopped state", stopped, state());
        barrier();
        Files.write(Path.of("target/dap-arguments-"+route+"-"+backend+".tsv"), evidence);
        if (backend.startsWith("rust")) assertEquals(Files.readAllLines(Path.of("target/dap-arguments-"+route+"-java.tsv")), evidence);
        // Leave the next independent suite in an empty, terminated session.
        prepare(); wire.request("continue", Map.of("threadId", 1));
        wire.event("output"); wire.event("terminated"); wire.event("exited"); barrier();
    }

    private JsonObject expected(JsonObject row, boolean argumentsFirst) {
        var expected = row.getAsJsonObject(argumentsFirst ? "expected" : "streamExpected").deepCopy();
        if (!argumentsFirst && expected.has("bodyMessage")) {
            String message = expected.get("bodyMessage").getAsString();
            var pattern = java.util.regex.Pattern.compile("column ([0-9]+)");
            var matcher = pattern.matcher(message);
            if (matcher.find()) {
                int column = Integer.parseInt(matcher.group(1)) + Integer.toString(wire.next).length() - row.get("streamSeq").getAsString().length();
                message = matcher.replaceFirst("column "+column);
            }
            expected.addProperty("bodyMessage", message);
        }
        return expected;
    }

    private void prepare() throws Exception {
        wire.request("initialize", Map.of("linesStartAt1", true, "columnsStartAt1", true));
        wire.request("launch", launch); wire.event("initialized");
        wire.request("setBreakpoints", Map.of("breakpoints", List.of()));
        wire.request("configurationDone", Map.of());
        assertEquals("entry", wire.event("stopped").get("reason").getAsString());
    }

    private List<JsonObject> state() throws Exception {
        return List.of(wire.request("stackTrace", Map.of("threadId", 1)), wire.request("scopes", Map.of("frameId", 0)), wire.request("variables", Map.of("variablesReference", 1)));
    }

    private void barrier() throws Exception {
        wire.request("threads", Map.of());
        assertTrue("unexpected lifecycle events: "+wire.pending, wire.pending.isEmpty());
    }

    private void observe(String id, JsonObject response, JsonObject expected) throws Exception {
        raw.add(id+"\t"+response);
        Files.write(Path.of("target/dap-arguments-"+route+"-"+backend+"-raw.tsv"), raw);
        var actual = shape(response);
        if (expected.has("exceptionType")) {
            assertEquals(expected.get("exceptionType").getAsString(), actual.get("firstLine").getAsString().split(":", 2)[0]);
            actual.remove("firstLine"); actual.add("exceptionType", expected.get("exceptionType"));
        }
        assertEquals(route+"/"+backend+" "+id+" => "+response, expected, actual);
        evidence.add(id+"\t"+actual);
    }

    private static JsonObject shape(JsonObject response) {
        var out = new JsonObject(); out.add("success", response.get("success"));
        out.add("message", response.has("message") ? response.get("message") : JsonNull.INSTANCE);
        var body = response.get("body");
        String type = body == null ? "absent" : body.isJsonNull() ? "null" : body.isJsonObject() ? "object" : body.isJsonArray() ? "array" : body.getAsJsonPrimitive().isString() ? "string" : "other";
        out.addProperty("bodyType", type);
        if (type.equals("string")) out.addProperty("firstLine", body.getAsString().lines().findFirst().orElse(""));
        if (type.equals("object") && body.getAsJsonObject().has("message")) out.add("bodyMessage", body.getAsJsonObject().get("message"));
        return out;
    }
}
