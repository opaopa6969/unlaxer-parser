package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.*;
import org.junit.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.ClassicDapProtocolConformanceTest.Wire;

/** Shared independent fixture for projected text and logical CP ranges over real stdio. */
public class ProjectedTokenDapProtocolConformanceTest {
    @Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path FIXTURES = ROOT.resolve("docs/fixtures/projected-token-dap");

    @Test public void projectedLeavesFollowTheSharedRollbackOracle() throws Exception {
        var rows = JsonParser.parseString(Files.readString(FIXTURES.resolve("rollback.json"))).getAsJsonArray();
        assertEquals(5, rows.size());
        for (var element : rows) {
            var row = element.getAsJsonObject(); String name = row.get("name").getAsString(); int[] calls = {0};
            var source = org.unlaxer.StringSource.createRootSource(row.get("input").getAsString());
            org.unlaxer.parser.Parser projected = new org.unlaxer.parser.combinator.Chain() {
                @Override public org.unlaxer.Token collect(List<org.unlaxer.Token> tokens, org.unlaxer.TokenKind kind, java.util.function.Predicate<org.unlaxer.Token> filter) {
                    calls[0]++;
                    var pieces = new org.unlaxer.TokenList();
                    var part = source.subSource(new org.unlaxer.CodePointIndex(0), new org.unlaxer.CodePointIndex(1));
                    pieces.add(new org.unlaxer.Token(org.unlaxer.TokenKind.consumed, part, this));
                    pieces.add(new org.unlaxer.Token(org.unlaxer.TokenKind.consumed, part, this));
                    return new org.unlaxer.Token(org.unlaxer.TokenKind.consumed, pieces.toSource(org.unlaxer.Source.SourceKind.subSource), this);
                }
            };
            var a = new org.unlaxer.parser.elementary.WordParser("a");
            var miss = new org.unlaxer.parser.combinator.Chain(projected, new org.unlaxer.parser.elementary.WordParser("x"));
            org.unlaxer.parser.Parser parser = switch (name) {
                case "committed" -> projected;
                case "choice-loser" -> new org.unlaxer.parser.combinator.Choice(miss, a);
                case "lookahead" -> new org.unlaxer.parser.combinator.Chain(new org.unlaxer.parser.combinator.PegLookahead(projected), a);
                case "negative-lookahead" -> new org.unlaxer.parser.combinator.Chain(new org.unlaxer.parser.combinator.PegLookahead(miss, false), a);
                case "outer-failure" -> miss;
                default -> throw new AssertionError(name);
            };
            try (var context = new org.unlaxer.context.ParseContext(source)) {
                var parsed = parser.parse(context);
                assertEquals(name, row.get("accepted").getAsBoolean(), parsed.isSucceeded());
                assertEquals(name+" callback actually ran", 1, calls[0]);
                var leaves = new ArrayList<org.unlaxer.Token>();
                if (parsed.isSucceeded()) TokenTraceConformanceTest.terminalTokens(parsed.getRootToken(), leaves);
                assertEquals(name, row.get("projected").getAsInt(), leaves.stream().filter(t -> t.parser == projected).count());
                for (var leaf : leaves) if (leaf.parser == projected) {
                    assertEquals(row.get("text").getAsString(), leaf.source.sourceAsString());
                    assertEquals(row.getAsJsonArray("span").get(0).getAsInt(), leaf.source.offsetFromRoot().value());
                    assertEquals(row.getAsJsonArray("span").get(1).getAsInt(), leaf.source.offsetFromRoot().value() + leaf.source.codePointLength().value());
                }
                if (!parsed.isSucceeded()) {
                    assertEquals(0, context.getConsumedPosition().value());
                    assertTrue(context.getCurrent().getTokens().isEmpty());
                    assertEquals("a", a.parse(context).getConsumed().source.sourceAsString());
                }
            }
        }
    }

    @Test public void projectedSourcesShareTextPositionsAndLifecycleOverStdio() throws Exception {
        Assume.assumeTrue("[assumption] projected token DAP requires -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        Path evidenceFile = Path.of("target/projected-token-dap-protocol.tsv");
        Files.deleteIfExists(evidenceFile);
        var evidence = new ArrayList<String>(List.of("backend\tfixture\tbase\toffset\tindex\tlabel\tstart_cp\tend_cp\tline\tcolumn\tend_line\tend_column\tvalue"));
        try (var wire = javaWire()) { exercise(wire, "java", evidence); }
        try (var wire = rustWire("cases.json")) { exercise(wire, "rust", evidence); }
        assertEquals(evidence.stream().filter(s -> s.startsWith("java\t")).map(s -> s.substring(5)).toList(), evidence.stream().filter(s -> s.startsWith("rust\t")).map(s -> s.substring(5)).toList());
        assertEquals(161, evidence.size());
        Files.write(evidenceFile, evidence);
    }

    private void exercise(Wire wire, String backend, List<String> evidence) throws Exception {
        wire.failures = new DapFailureConformance("projected", backend);
        wire.probeFailures("fresh");
        assertEquals(8, rows().size());
        for (boolean base : List.of(true, false)) for (int offset : List.of(0, 7)) {
            wire.request("initialize", Map.of("adapterID", "projected", "linesStartAt1", base, "columnsStartAt1", base));
            for (var element : rows()) {
                var row = element.getAsJsonObject(); String name = row.get("name").getAsString();
                var steps = row.getAsJsonArray("steps"); Path program = launch(wire, name, offset);
                for (int i = 0; i < steps.size(); i++) {
                    var expected = steps.get(i).getAsJsonArray(); String label = expected.get(0).getAsString();
                    var frame = wire.request("stackTrace", Map.of("threadId", 1)).getAsJsonArray("stackFrames").get(0).getAsJsonObject();
                    assertEquals(name, label+" ("+(i+1)+"/"+steps.size()+")", frame.get("name").getAsString());
                    assertEquals(program.toString(), frame.getAsJsonObject("source").get("path").getAsString());
                    var keys = List.of("line", "column", "endLine", "endColumn");
                    for (int k = 0; k < keys.size(); k++) assertEquals(name+" "+keys.get(k), expected.get(4+k).getAsInt()+(k%2 == 0 ? offset : 0)-(base ? 0 : 1), frame.get(keys.get(k)).getAsInt());
                    assertEquals("Current Token", wire.request("scopes", Map.of("frameId", 0)).getAsJsonArray("scopes").get(0).getAsJsonObject().get("name").getAsString());
                    var vars = variables(wire); var token = vars.get(label);
                    String value = "\""+expected.get(1).getAsString().strip().replace("\"", "\\\"")+"\"";
                    assertEquals(name, value, token.get("value").getAsString()); assertEquals("Token", token.get("type").getAsString());
                    assertEquals(0, token.get("variablesReference").getAsInt());
                    assertEquals("inspection-token", vars.get("runtimeMode").get("value").getAsString());
                    assertEquals("token", vars.get("steppingMode").get("value").getAsString());
                    assertEquals("0", vars.get("astNodeCount").get("value").getAsString());
                    assertEquals("", vars.get("astCurrentNode").get("value").getAsString());
                    evidence.add(backend+"\t"+name+"\t"+base+"\t"+offset+"\t"+i+"\t"+label+"\t"+expected.get(2)+"\t"+expected.get(3)+"\t"+frame.get("line")+"\t"+frame.get("column")+"\t"+frame.get("endLine")+"\t"+frame.get("endColumn")+"\t"+new Gson().toJson(value));
                    wire.request("next", Map.of("threadId", 1));
                    if (i+1 < steps.size()) assertEquals("step", wire.event("stopped").get("reason").getAsString());
                    else { assertEquals("stdout", wire.event("output").get("category").getAsString()); wire.event("terminated"); assertEquals(0, wire.event("exited").get("exitCode").getAsInt()); }
                }
                assertTrue(wire.request("stackTrace", Map.of("threadId", 1)).getAsJsonArray("stackFrames").isEmpty());
                assertTrue(wire.request("scopes", Map.of("frameId", 0)).getAsJsonArray("scopes").isEmpty());
                DapFailureConformance.invalidRequest(wire.response("next", Map.of("threadId", 1)));
            }
            Path program = launch(wire, "trace-order", offset);
            wire.request("setBreakpoints", Map.of("source", Map.of("path", program.toString()), "breakpoints", List.of(Map.of("line", 2+offset-(base ? 0 : 1)))));
            wire.request("continue", Map.of("threadId", 1));
            assertEquals("breakpoint", wire.event("stopped").get("reason").getAsString());
            assertEquals("\"xx\"", variables(wire).get("ProjectedToken").get("value").getAsString());
            wire.request("continue", Map.of("threadId", 1)); wire.event("output"); wire.event("terminated"); wire.event("exited");
        }
        wire.failures.complete(); wire.failures = null;
        DapArgumentConformance.check(wire, "projected", backend, temporary.getRoot().toPath());
        wire.request("disconnect", Map.of());
    }

    @Test public void javaProjectedSourcesKeepExistingTextAndCoordinates() throws Exception {
        var evidence = new ArrayList<String>();
        try (var wire = javaWire()) { exercise(wire, "java", evidence); }
        assertEquals(80, evidence.size());
        Files.write(Path.of("target/projected-token-dap-java.tsv"), evidence);
    }

    private Wire javaWire() throws Exception {
        return javaWire("cases.json");
    }
    private Wire javaWire(String fixtures) throws Exception {
        var grammar = UBNFMapper.parse("grammar ProjectedInspection { @package: wire.projected @root Root ::= 'x'; }").grammars().get(0);
        var adapter = new DAPGenerator().generate(grammar);
        String main = Files.readString(FIXTURES.resolve("JavaMain.java.txt"));
        var sources = List.of(source(adapter.packageName()+"."+adapter.className(), adapter.source()), source("wire.projected.JavaMain", main));
        Path classes = temporary.newFolder().toPath(); var errors = new StringWriter();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null,null,null)) {
            boolean ok = compiler.getTask(errors, manager, null, List.of("--release", "17", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()), null, sources).call();
            assertTrue(errors.toString(), ok);
        }
        return new Wire(new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", classes+File.pathSeparator+System.getProperty("java.class.path"), "wire.projected.JavaMain", FIXTURES.resolve(fixtures).toString()).redirectError(temporary.newFile()).start());
    }
    private Path launch(Wire wire, String fixture, int offset) throws Exception {
        var row = rows().asList().stream().map(JsonElement::getAsJsonObject).filter(r -> r.get("name").getAsString().equals(fixture)).findFirst().orElseThrow();
        return launch(wire, row, offset);
    }
    private Path launch(Wire wire, JsonObject row, int offset) throws Exception {
        Path program = temporary.newFile().toPath();
        Files.writeString(program, "outer\n".repeat(offset)+row.get("input").getAsString());
        wire.request("launch", Map.of("program", program.toString(), "fixture", row.get("name").getAsString(), "stopOnEntry", true, "runtimeMode", "inspection-token", "lineOffset", offset));
        wire.event("initialized"); wire.request("configurationDone", Map.of());
        var stopped = wire.oneOf("stopped", "terminated");
        assertEquals(stopped.toString(), "stopped", stopped.get("event").getAsString());
        assertEquals("entry", stopped.getAsJsonObject("body").get("reason").getAsString());
        wire.probeFailures("stopped");
        return program;
    }
    private static JsonArray rows() throws Exception { return JsonParser.parseString(Files.readString(FIXTURES.resolve("cases.json"))).getAsJsonArray(); }
    private Wire rustWire(String fixtures) throws Exception {
        Path log = temporary.newFile().toPath();
        var build = new ProcessBuilder("cargo", "build", "--offline", "--locked", "--manifest-path", ROOT.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-dap", "--example", "projected_token_dap").redirectErrorStream(true).redirectOutput(log.toFile());
        build.environment().put("CARGO_TARGET_DIR", ROOT.resolve("rust/target").toString());
        var process = build.start();
        try { assertTrue("Rust build timeout", process.waitFor(180, TimeUnit.SECONDS)); assertEquals(Files.readString(log), 0, process.exitValue()); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
        return new Wire(new ProcessBuilder(ROOT.resolve("rust/target/debug/examples/projected_token_dap").toString(), FIXTURES.resolve(fixtures).toString()).redirectError(temporary.newFile()).start());
    }

    @Test public void projectedOverflowKeepsFailuresLocalAndLifecycleOverStdio() throws Exception {
        Assume.assumeTrue("[assumption] projected token DAP requires -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        Path evidenceFile = Path.of("target/projected-token-dap-overflow.tsv");
        Files.deleteIfExists(evidenceFile);
        var javaEvidence = new ArrayList<String>();
        var rustEvidence = new ArrayList<String>();
        try (var wire = javaWire("overflow.json")) { exerciseOverflow(wire, javaEvidence); }
        try (var wire = rustWire("overflow.json")) { exerciseOverflow(wire, rustEvidence); }
        assertEquals(javaEvidence, rustEvidence);
        assertEquals(164, javaEvidence.size());
        var evidence = new ArrayList<String>(List.of("backend\tfixture\tbase\toffset\tmode\tindex\tobservation"));
        javaEvidence.forEach(line -> evidence.add("java\t"+line));
        rustEvidence.forEach(line -> evidence.add("rust\t"+line));
        Files.write(evidenceFile, evidence);
    }

    private void exerciseOverflow(Wire wire, List<String> evidence) throws Exception {
        var rows = JsonParser.parseString(Files.readString(FIXTURES.resolve("overflow.json"))).getAsJsonArray();
        assertEquals(4, rows.size());
        for (boolean base : List.of(true, false)) for (int offset : List.of(0, 7)) {
            wire.request("initialize", Map.of("adapterID", "projected", "linesStartAt1", base, "columnsStartAt1", base));
            for (var element : rows) for (String mode : List.of("next", "continue", "breakpoints")) {
                var row = element.getAsJsonObject(); var steps = row.getAsJsonArray("steps");
                Path program = launch(wire, row, offset);
                String prefix = row.get("name").getAsString()+"\t"+base+"\t"+offset+"\t"+mode+"\t";
                if (mode.equals("breakpoints")) {
                    // All start lines, including the invalid frame's line, are real source positions.
                    var breakpoints = new ArrayList<Map<String,Integer>>();
                    int lines = row.get("input").getAsString().split("\n", -1).length;
                    for (int line = 0; line < lines; line++) breakpoints.add(Map.of("line", line+offset+(base ? 1 : 0)));
                    var set = wire.request("setBreakpoints", Map.of("source", Map.of("path", program.toString()), "breakpoints", breakpoints));
                    for (var bp : set.getAsJsonArray("breakpoints")) assertTrue(bp.getAsJsonObject().get("verified").getAsBoolean());
                }
                for (int i = 0; i < steps.size(); i++) {
                    var step = steps.get(i).getAsJsonArray();
                    boolean overflow = step.get(4).isJsonNull();
                    var response = wire.response("stackTrace", Map.of("threadId", 1));
                    var observation = new JsonObject();
                    observation.add("success", response.get("success"));
                    assertEquals(prefix+i, !overflow, response.get("success").getAsBoolean());
                    if (overflow) {
                        assertEquals("Internal error.", response.get("message").getAsString());
                        assertTrue(response.get("body").isJsonPrimitive());
                        assertTrue(response.getAsJsonPrimitive("body").isString());
                        String body = response.get("body").getAsString();
                        String diagnostic = "java.lang.IllegalArgumentException: outside snapshot";
                        assertEquals(diagnostic, body.lines().findFirst().orElseThrow());
                        // Java/JDK call frames are host-specific; never erase the error body itself.
                        assertTrue(body.lines().skip(1).allMatch(line -> line.startsWith("\tat ")));
                        observation.add("message", response.get("message"));
                        observation.addProperty("body", diagnostic);
                        var repeated = wire.response("stackTrace", Map.of("threadId", 1));
                        for (String key : List.of("success", "message", "body")) assertEquals(response.get(key), repeated.get(key));
                    } else {
                        var frames = response.getAsJsonObject("body").getAsJsonArray("stackFrames");
                        assertEquals(1, frames.size());
                        var frame = frames.get(0).getAsJsonObject();
                        assertEquals(step.get(0).getAsString()+" ("+(i+1)+"/"+steps.size()+")", frame.get("name").getAsString());
                        assertEquals(program.toString(), frame.getAsJsonObject("source").get("path").getAsString());
                        var keys = List.of("line", "column", "endLine", "endColumn");
                        for (int k = 0; k < keys.size(); k++) assertEquals(prefix+i+" "+keys.get(k), step.get(4+k).getAsInt()+(k%2 == 0 ? offset : 0)-(base ? 0 : 1), frame.get(keys.get(k)).getAsInt());
                        var position = new JsonObject();
                        for (String key : List.of("id", "name", "line", "column", "endLine", "endColumn")) position.add(key, frame.get(key));
                        observation.add("frame", position);
                    }
                    assertEquals("Current Token", wire.request("scopes", Map.of("frameId", 0)).getAsJsonArray("scopes").get(0).getAsJsonObject().get("name").getAsString());
                    var vars = variables(wire);
                    assertEquals(5, vars.size());
                    var token = vars.get(step.get(0).getAsString());
                    assertEquals("\""+step.get(1).getAsString().strip().replace("\"", "\\\"")+"\"", token.get("value").getAsString());
                    assertEquals("Token", token.get("type").getAsString()); assertEquals(0, token.get("variablesReference").getAsInt());
                    assertEquals("inspection-token", vars.get("runtimeMode").get("value").getAsString());
                    assertEquals("token", vars.get("steppingMode").get("value").getAsString());
                    assertEquals("0", vars.get("astNodeCount").get("value").getAsString());
                    assertEquals("", vars.get("astCurrentNode").get("value").getAsString());
                    var observedVariables = new TreeMap<String,List<Object>>();
                    vars.forEach((name, v) -> observedVariables.put(name, List.of(v.get("value").getAsString(), v.get("type").getAsString(), v.get("variablesReference").getAsInt())));
                    observation.add("variables", new Gson().toJsonTree(observedVariables));
                    evidence.add(prefix+i+"\t"+observation);
                    boolean finish = i+1 == steps.size() || (mode.equals("continue") && overflow);
                    boolean resume = mode.equals("breakpoints") || (mode.equals("continue") && overflow);
                    var body = wire.request(resume ? "continue" : "next", Map.of("threadId", 1));
                    if (resume) assertTrue(body.get("allThreadsContinued").getAsBoolean());
                    if (finish) {
                        var output = wire.event("output");
                        assertEquals("stdout", output.get("category").getAsString());
                        assertEquals("Completed: "+program+"\n", output.get("output").getAsString());
                        wire.event("terminated"); assertEquals(0, wire.event("exited").get("exitCode").getAsInt());
                        break;
                    }
                    var stopped = wire.event("stopped");
                    assertEquals(resume ? "breakpoint" : "step", stopped.get("reason").getAsString());
                    assertEquals(1, stopped.get("threadId").getAsInt()); assertTrue(stopped.get("allThreadsStopped").getAsBoolean());
                }
                assertTrue(wire.request("stackTrace", Map.of("threadId", 1)).getAsJsonArray("stackFrames").isEmpty());
                assertTrue(wire.request("scopes", Map.of("frameId", 0)).getAsJsonArray("scopes").isEmpty());
                var ended = variables(wire);
                assertEquals(Set.of("runtimeMode", "steppingMode"), ended.keySet());
                assertEquals("inspection-token", ended.get("runtimeMode").get("value").getAsString());
                assertEquals("token", ended.get("steppingMode").get("value").getAsString());
                for (String command : List.of("next", "continue")) DapFailureConformance.invalidRequest(wire.response(command, Map.of("threadId", 1)));
                assertTrue("unexpected lifecycle event: "+wire.pending, wire.pending.isEmpty());
                evidence.add(prefix+"end\tterminated/exited:0; empty frames/scopes; no token; next/continue rejected");
            }
        }
        wire.request("disconnect", Map.of());
    }
    @Test public void crlfInteriorPositionsFailOnlyTheJavaRequestsOverStdio() throws Exception {
        Assume.assumeTrue("[assumption] CRLF DAP requires -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        Path evidenceFile = Path.of("target/token-dap-crlf.tsv");
        Files.deleteIfExists(evidenceFile);
        var javaEvidence = new ArrayList<String>(); var rustEvidence = new ArrayList<String>();
        try (var wire = javaWire("crlf.json")) { exerciseCrlf(wire, javaEvidence); }
        try (var wire = rustWire("crlf.json")) { exerciseCrlf(wire, rustEvidence); }
        assertEquals(javaEvidence, rustEvidence);
        assertEquals(120, javaEvidence.stream().filter(line -> line.contains("\tend\t")).count());
        var evidence = new ArrayList<String>(List.of("backend\tfixture\tbase\toffset\tmode\tstep\tobservation"));
        javaEvidence.forEach(line -> evidence.add("java\t"+line));
        rustEvidence.forEach(line -> evidence.add("rust\t"+line));
        Files.write(evidenceFile, evidence);
    }

    private void exerciseCrlf(Wire wire, List<String> evidence) throws Exception {
        var rows = JsonParser.parseString(Files.readString(FIXTURES.resolve("crlf.json"))).getAsJsonArray();
        assertEquals(5, rows.size());
        String diagnostic = "java.lang.IllegalArgumentException: inside CRLF";
        for (boolean base : List.of(true, false)) for (int offset : List.of(0, 7)) {
            wire.request("initialize", Map.of("linesStartAt1", base, "columnsStartAt1", base));
            for (var element : rows) for (String mode : List.of("next", "continue", "breakpoints", "configuration-breakpoints", "configuration-negative-breakpoints", "run")) {
                var row = element.getAsJsonObject(); var steps = row.getAsJsonArray("steps");
                var starts = row.getAsJsonArray("startLines");
                boolean configuration = mode.startsWith("configuration-") || mode.equals("run");
                boolean breakpoints = mode.contains("breakpoints");
                String prefix = row.get("name").getAsString()+"\t"+base+"\t"+offset+"\t"+mode+"\t";
                Path program = temporary.newFile().toPath(); Files.writeString(program, "outer\n".repeat(offset)+row.get("input").getAsString());
                wire.request("launch", Map.of("program", program.toString(), "fixture", row.get("name").getAsString(), "stopOnEntry", !configuration, "runtimeMode", "inspection-token", "lineOffset", offset));
                wire.event("initialized");
                wire.request("setBreakpoints", Map.of("source", Map.of("path", program.toString()), "breakpoints", breakpoints ? List.of(Map.of("line", mode.contains("negative") ? -1 : 2+offset-(base ? 0 : 1))) : List.of()));
                // Java keeps the unmatched negative line, so configurationDone still searches every start.
                var configured = wire.response("configurationDone", Map.of());
                boolean badStart = starts.asList().stream().anyMatch(JsonElement::isJsonNull);
                if (configuration && breakpoints && badStart) {
                    DapFailureConformance.assertError(configured, diagnostic);
                    DapFailureConformance.assertError(wire.response("configurationDone", Map.of()), "java.util.concurrent.CompletionException: java.lang.IllegalStateException: configurationDone requires a new launch");
                } else {
                    assertTrue(configured.toString(), configured.get("success").getAsBoolean());
                    if (!configuration) assertEquals("entry", wire.event("stopped").get("reason").getAsString());
                }
                evidence.add(prefix+"configurationDone\t"+DapFailureConformance.shape(configured));
                if (!configuration || (breakpoints && badStart)) for (int i = 0; i < steps.size(); i++) {
                    var step = steps.get(i).getAsJsonArray();
                    var response = wire.response("stackTrace", Map.of("threadId", 1));
                    var observation = DapFailureConformance.shape(response);
                    if (step.get(4).isJsonNull()) {
                        DapFailureConformance.assertError(response, diagnostic);
                        assertEquals(observation, DapFailureConformance.shape(wire.response("stackTrace", Map.of("threadId", 1))));
                    } else {
                        assertTrue(response.toString(), response.get("success").getAsBoolean());
                        var frame = response.getAsJsonObject("body").getAsJsonArray("stackFrames").get(0).getAsJsonObject();
                        assertEquals(step.get(0).getAsString()+" ("+(i+1)+"/"+steps.size()+")", frame.get("name").getAsString());
                        assertEquals(program.toString(), frame.getAsJsonObject("source").get("path").getAsString());
                        var keys = List.of("line", "column", "endLine", "endColumn");
                        for (int k = 0; k < keys.size(); k++) assertEquals(step.get(4+k).getAsInt()+(k%2 == 0 ? offset : 0)-(base ? 0 : 1), frame.get(keys.get(k)).getAsInt());
                        var position = new JsonObject();
                        for (String key : keys) position.add(key, frame.get(key));
                        observation.add("position", position);
                    }
                    var vars = variables(wire); assertEquals(5, vars.size());
                    assertEquals("\""+step.get(1).getAsString().strip()+"\"", vars.get(step.get(0).getAsString()).get("value").getAsString());
                    var observed = new TreeMap<String,List<Object>>();
                    vars.forEach((name, v) -> observed.put(name, List.of(v.get("value").getAsString(), v.get("type").getAsString(), v.get("variablesReference").getAsInt())));
                    observation.add("variables", new Gson().toJsonTree(observed));
                    assertEquals("Current Token", wire.request("scopes", Map.of("frameId", 0)).getAsJsonArray("scopes").get(0).getAsJsonObject().get("name").getAsString());
                    evidence.add(prefix+i+"\t"+observation);
                    boolean next = mode.equals("next");
                    boolean fails = !next && starts.asList().subList(i+1, starts.size()).stream().anyMatch(JsonElement::isJsonNull);
                    if (fails) {
                        var failed = wire.response("continue", Map.of("threadId", 1));
                        DapFailureConformance.assertError(failed, diagnostic);
                        DapFailureConformance.assertError(wire.response("continue", Map.of("threadId", 1)), diagnostic);
                        assertEquals(vars, variables(wire));
                        assertEquals(DapFailureConformance.shape(response), DapFailureConformance.shape(wire.response("stackTrace", Map.of("threadId", 1))));
                        evidence.add(prefix+i+"-continue\t"+DapFailureConformance.shape(failed));
                        next = true; // Recover from the unchanged current step.
                    }
                    var resumed = wire.response(next ? "next" : "continue", Map.of("threadId", 1));
                    assertTrue(resumed.toString(), resumed.get("success").getAsBoolean());
                    if (!next) assertTrue(resumed.getAsJsonObject("body").get("allThreadsContinued").getAsBoolean());
                    evidence.add(prefix+i+"-resume\t"+DapFailureConformance.shape(resumed));
                    if (!next || i+1 == steps.size()) break;
                    assertEquals("step", wire.event("stopped").get("reason").getAsString());
                }
                var output = wire.event("output"); assertEquals("stdout", output.get("category").getAsString());
                assertEquals((configuration && !(breakpoints && badStart) ? "Parsed successfully: " : "Completed: ")+program+"\n", output.get("output").getAsString());
                wire.event("terminated"); assertEquals(0, wire.event("exited").get("exitCode").getAsInt());
                assertTrue(wire.request("stackTrace", Map.of("threadId", 1)).getAsJsonArray("stackFrames").isEmpty());
                assertTrue(wire.request("scopes", Map.of("frameId", 0)).getAsJsonArray("scopes").isEmpty());
                var ended = variables(wire); assertEquals(Set.of("runtimeMode", "steppingMode"), ended.keySet());
                assertEquals("inspection-token", ended.get("runtimeMode").get("value").getAsString());
                assertEquals("token", ended.get("steppingMode").get("value").getAsString());
                for (String command : List.of("next", "continue")) DapFailureConformance.invalidRequest(wire.response(command, Map.of("threadId", 1)));
                wire.request("threads", Map.of()); assertTrue("unexpected events: "+wire.pending, wire.pending.isEmpty());
                evidence.add(prefix+"end\tterminated/exited:0; runtimeMode=inspection-token; steppingMode=token");
            }
        }
        wire.request("disconnect", Map.of());
    }

    private static Map<String,JsonObject> variables(Wire wire) throws Exception {
        var out = new HashMap<String,JsonObject>();
        for (var item : wire.request("variables", Map.of("variablesReference", 1)).getAsJsonArray("variables")) {
            var v = item.getAsJsonObject(); out.put(v.get("name").getAsString(), v);
        }
        return out;
    }
    private static JavaFileObject source(String name, String body) {
        return new SimpleJavaFileObject(URI.create("string:///"+name.replace('.', '/')+".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return body; }
        };
    }
}
