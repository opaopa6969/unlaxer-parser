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
        Path log = temporary.newFile().toPath();
        var build = new ProcessBuilder("cargo", "build", "--offline", "--locked", "--manifest-path", ROOT.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-dap", "--example", "projected_token_dap").redirectErrorStream(true).redirectOutput(log.toFile());
        build.environment().put("CARGO_TARGET_DIR", ROOT.resolve("rust/target").toString());
        var process = build.start();
        try { assertTrue("Rust build timeout", process.waitFor(180, TimeUnit.SECONDS)); assertEquals(Files.readString(log), 0, process.exitValue()); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
        try (var wire = new Wire(new ProcessBuilder(ROOT.resolve("rust/target/debug/examples/projected_token_dap").toString(), FIXTURES.resolve("cases.json").toString()).redirectError(temporary.newFile()).start())) {
            exercise(wire, "rust", evidence);
        }
        assertEquals(evidence.stream().filter(s -> s.startsWith("java\t")).map(s -> s.substring(5)).toList(), evidence.stream().filter(s -> s.startsWith("rust\t")).map(s -> s.substring(5)).toList());
        assertEquals(161, evidence.size());
        Files.write(evidenceFile, evidence);
    }

    private void exercise(Wire wire, String backend, List<String> evidence) throws Exception {
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
                assertFalse(wire.response("next", Map.of("threadId", 1)).get("success").getAsBoolean());
            }
            Path program = launch(wire, "trace-order", offset);
            wire.request("setBreakpoints", Map.of("source", Map.of("path", program.toString()), "breakpoints", List.of(Map.of("line", 2+offset-(base ? 0 : 1)))));
            wire.request("continue", Map.of("threadId", 1));
            assertEquals("breakpoint", wire.event("stopped").get("reason").getAsString());
            assertEquals("\"xx\"", variables(wire).get("ProjectedToken").get("value").getAsString());
            wire.request("continue", Map.of("threadId", 1)); wire.event("output"); wire.event("terminated"); wire.event("exited");
        }
        wire.request("disconnect", Map.of());
    }

    @Test public void javaProjectedSourcesKeepExistingTextAndCoordinates() throws Exception {
        var evidence = new ArrayList<String>();
        try (var wire = javaWire()) { exercise(wire, "java", evidence); }
        assertEquals(80, evidence.size());
        Files.write(Path.of("target/projected-token-dap-java.tsv"), evidence);
    }

    private Wire javaWire() throws Exception {
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
        return new Wire(new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", classes+File.pathSeparator+System.getProperty("java.class.path"), "wire.projected.JavaMain", FIXTURES.resolve("cases.json").toString()).redirectError(temporary.newFile()).start());
    }
    private Path launch(Wire wire, String fixture, int offset) throws Exception {
        var row = rows().asList().stream().map(JsonElement::getAsJsonObject).filter(r -> r.get("name").getAsString().equals(fixture)).findFirst().orElseThrow();
        Path program = temporary.newFile().toPath();
        Files.writeString(program, "outer\n".repeat(offset)+row.get("input").getAsString());
        wire.request("launch", Map.of("program", program.toString(), "fixture", fixture, "stopOnEntry", true, "runtimeMode", "inspection-token", "lineOffset", offset));
        wire.event("initialized"); wire.request("configurationDone", Map.of());
        assertEquals("entry", wire.event("stopped").get("reason").getAsString());
        return program;
    }
    private static JsonArray rows() throws Exception { return JsonParser.parseString(Files.readString(FIXTURES.resolve("cases.json"))).getAsJsonArray(); }
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
