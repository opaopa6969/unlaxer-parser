package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.dsl.runtime.ScopeStore;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.WordParser;

/** Independent CP oracles distinguish match-only publication from PEG assertion rollback. */
public class AssertionContractConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void existingPositiveContractsHaveExplicitIndependentOracles() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] assertion contracts require -DrustConformance=true and Rust 1.85.0");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path fixtures = repo.resolve("docs/fixtures/assertion-contracts");
        JsonArray corpus = JsonParser.parseString(Files.readString(fixtures.resolve("cases.json"))).getAsJsonArray();
        Path directory = temporary.newFolder().toPath();
        String rustc = run(List.of("rustup", "which", "rustc", "--toolchain", "1.85.0")).trim();
        Path runtime = directory.resolve("libunlaxer_runtime.rlib");
        run(List.of(rustc, "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime", repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()));
        StringBuilder rust = new StringBuilder(Files.readString(fixtures.resolve("probe.rs"))).append("\nfn main() {\n");
        for (JsonElement element : corpus) {
            JsonObject row = element.getAsJsonObject();
            rust.append("observe(").append(quote(row.get("id").getAsString())).append(',')
                .append(quote(row.get("source").getAsString())).append(',')
                .append(quote(row.get("prefix").getAsString())).append(',')
                .append(quote(row.get("child").getAsString())).append(',')
                .append(row.get("suffix").isJsonNull() ? "None" : "Some(" + quote(row.get("suffix").getAsString()) + ")").append(");\n");
        }
        rust.append("}\n");
        Path probe = directory.resolve("probe.rs"); Files.writeString(probe, rust);
        Path executable = directory.resolve("probe");
        run(List.of(rustc, "--edition=2021", "--extern", "unlaxer_runtime=" + runtime, probe.toString(), "-o", executable.toString()));
        List<String> rustRows = run(List.of(executable.toString())).lines().toList();
        assertEquals(corpus.size() * 6, rustRows.size());
        var report = new ArrayList<String>(List.of("fixture\tmemo\tdiagnostics\texpected_java\tjava\texpected_rust\trust"));
        int i = 0;
        for (JsonElement element : corpus) {
            JsonObject row = element.getAsJsonObject();
            for (Memoization memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES)) {
                for (ParseOptions.Diagnostics policy : List.of(ParseOptions.Diagnostics.DETAILED, ParseOptions.Diagnostics.DETAILED_ON_FAILURE, ParseOptions.Diagnostics.AUTO)) {
                    JsonObject java = observeJava(row, memo, policy);
                    JsonObject rustActual = JsonParser.parseString(rustRows.get(i++)).getAsJsonObject();
                    JsonObject javaExpected = expected(row, "java", policy);
                    JsonObject rustExpected = expected(row, "rust", policy);
                    String label = row.get("id").getAsString() + "/" + memo + "/" + policy;
                    report.add(label + "\t" + memo + "\t" + policy + "\t" + javaExpected + "\t" + java + "\t" + rustExpected + "\t" + rustActual);
                }
            }
        }
        Files.write(Path.of("target/assertion-contracts.tsv"), report);
        for (String line : report.subList(1, report.size())) {
            String[] fields = line.split("\t", -1);
            assertEquals(fields[0] + " Java", JsonParser.parseString(fields[3]), JsonParser.parseString(fields[4]));
            assertEquals(fields[0] + " Rust", JsonParser.parseString(fields[5]), JsonParser.parseString(fields[6]));
        }
    }

    /** Only an invocation observer: acceptance and transactional state never depend on its log. */
    public static class RetryAssertion extends MatchOnly implements DiagnosticsAgnostic {
        static final List<ParseContext> calls = new ArrayList<>();
        public RetryAssertion() { super(new Chain(new WordParser("a"), new WordParser("b"), new org.unlaxer.parser.combinator.Optional(new WordParser("!")))); }
        @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
            calls.add(context); return super.parse(context, kind, invert);
        }
    }

    @Test public void realFullInputEntryPointsRetryFailedAssertionsWithBackendNativeDiagnostics() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] assertion retries require -DrustConformance=true and Rust 1.85.0");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path fixture = repo.resolve("docs/fixtures/assertion-contracts");
        var rows = JsonParser.parseString(Files.readString(fixture.resolve("retries.json"))).getAsJsonArray();
        var grammar = org.unlaxer.dsl.bootstrap.UBNFMapper.parse(Files.readString(fixture.resolve("Retry.ubnf"))).grammars().get(0);
        var sources = List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar), new MapperGenerator().generate(grammar));
        var units = sources.stream().map(source -> new javax.tools.SimpleJavaFileObject(java.net.URI.create("string:///example/assertions/" + source.className() + ".java"), javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return source.source(); }
        }).toList();
        Path directory = temporary.newFolder().toPath();
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            assertTrue(diagnostics.getDiagnostics().toString(), compiler.getTask(null, manager, diagnostics, List.of("--release", "17", "-classpath", System.getProperty("java.class.path"), "-d", directory.toString()), null, units).call());
        }
        String rustc = run(List.of("rustup", "which", "rustc", "--toolchain", "1.85.0")).trim();
        Path runtime = directory.resolve("libunlaxer_runtime.rlib");
        run(List.of(rustc, "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime", repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()));
        var source = new StringBuilder(Files.readString(fixture.resolve("probe.rs"))).append("\nfn main() {\n");
        for (JsonElement row : rows) source.append("retry(").append(quote(row.getAsJsonObject().get("source").getAsString())).append(");\n");
        source.append("}\n"); Path probe = directory.resolve("probe.rs"); Files.writeString(probe, source);
        Path executable = directory.resolve("probe");
        run(List.of(rustc, "--edition=2021", "--extern", "unlaxer_runtime=" + runtime, probe.toString(), "-o", executable.toString()));
        var rust = run(List.of(executable.toString())).lines().toList(); assertEquals(rows.size() * 6, rust.size());
        var report = new ArrayList<String>(List.of("fixture\tmemo\tdiagnostics\tjava\trust\tpasses")); int index = 0;
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, getClass().getClassLoader())) {
            var diagnose = loader.loadClass("example.assertions.RetryMapper").getMethod("diagnose", String.class, ParseOptions.class);
            for (JsonElement element : rows) {
                var row = element.getAsJsonObject(); String input = row.get("source").getAsString();
                for (Memoization memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES)) {
                    for (ParseOptions.Diagnostics policy : List.of(ParseOptions.Diagnostics.DETAILED, ParseOptions.Diagnostics.DETAILED_ON_FAILURE, ParseOptions.Diagnostics.AUTO)) {
                        RetryAssertion.calls.clear();
                        var result = (java.util.Optional<?>) diagnose.invoke(null, input, ParseOptions.withMemoization(memo).withDiagnostics(policy));
                        boolean accepted = result.isEmpty();
                        int passes = accepted || policy == ParseOptions.Diagnostics.DETAILED ? 1 : 2;
                        assertEquals(row + " retry count " + policy, passes, RetryAssertion.calls.size());
                        if (passes == 2) {
                            assertNotSame(RetryAssertion.calls.get(0), RetryAssertion.calls.get(1));
                            assertEquals(ParseOptions.Diagnostics.DETAILED_ON_FAILURE, RetryAssertion.calls.get(0).getOptions().diagnostics());
                            assertEquals(ParseOptions.Diagnostics.DETAILED, RetryAssertion.calls.get(1).getOptions().diagnostics());
                        }
                        var actual = new JsonObject(); actual.addProperty("accepted", accepted);
                        actual.addProperty("farthest", accepted ? -1 : RetryAssertion.calls.get(passes - 1).getParseFailureDiagnostics().getFarthestOffset());
                        var hints = new JsonArray();
                        if (!accepted) RetryAssertion.calls.get(passes - 1).getParseFailureDiagnostics().getExpectedTokens().stream().sorted().forEach(hints::add);
                        actual.add("expected", hints);
                        assertEquals(row.get("id") + " Java " + memo + "/" + policy, row.get("java"), actual);
                        var rustActual = JsonParser.parseString(rust.get(index++)).getAsJsonObject();
                        assertEquals(passes, rustActual.remove("passes").getAsInt());
                        assertEquals(row.get("id") + " Rust " + memo + "/" + policy, row.get("rust"), rustActual);
                        report.add(row.get("id").getAsString() + "\t" + memo + "\t" + policy + "\t" + actual + "\t" + rustActual + "\t" + passes);
                    }
                }
            }
        } finally { RetryAssertion.calls.clear(); }
        Files.write(Path.of("target/assertion-retries.tsv"), report);
    }

    private JsonObject expected(JsonObject row, String backend, ParseOptions.Diagnostics policy) {
        JsonObject result = row.getAsJsonObject(backend).deepCopy();
        if (policy == ParseOptions.Diagnostics.DETAILED_ON_FAILURE) {
            result.addProperty("farthest", 0); result.add("expected", new JsonArray());
        }
        return result;
    }

    private JsonObject observeJava(JsonObject row, Memoization memo, ParseOptions.Diagnostics policy) {
        String input = row.get("source").getAsString();
        State state = new State();
        try (var context = ParseContext.withOptions(StringSource.createRootSource(input), ParseOptions.withMemoization(memo).withDiagnostics(policy))) {
            context.registerTransactionalState(state);
            ScopeStore.declare(context, "before", 0);
            ScopeStore.addReference(context, "before", 0, 1);
            ScopeStore.addDiagnostic(context, "baseline", 0, 1, ScopeStore.Severity.INFO);
            var elements = new ArrayList<Parser>();
            String prefix = row.get("prefix").getAsString();
            if (!prefix.isEmpty()) elements.add(new WordParser(prefix));
            var assertion = new ObservedAssertion(child(row.get("child").getAsString(), state));
            elements.add(assertion);
            if (!row.get("suffix").isJsonNull()) elements.add(new WordParser(row.get("suffix").getAsString()));
            Parsed parsed = new Chain(elements.toArray(Parser[]::new)).parse(context);
            var result = new JsonObject(); result.addProperty("accepted", parsed.isSucceeded());
            result.addProperty("consumed", context.getConsumedPosition().value());
            result.addProperty("matched", context.getMatchedPosition().value());
            var leaves = new JsonArray(); var captures = new JsonArray();
            if (parsed.isSucceeded()) collect(parsed.getRootToken(false), leaves, captures);
            result.add("tokens", leaves); result.add("captures", captures);
            result.add("assertion", assertion.observation);
            result.addProperty("farthest", context.getParseFailureDiagnostics().getFarthestOffset());
            var expected = new JsonArray(); context.getParseFailureDiagnostics().getExpectedTokens().stream().sorted().forEach(expected::add);
            result.add("expected", expected);
            result.addProperty("state", state.value);
            result.addProperty("scopeDepth", ScopeStore.currentScopeDepth(context));
            result.addProperty("declarations", ScopeStore.getAllDeclarations(context).size());
            result.addProperty("references", ScopeStore.getAllReferences(context).size());
            result.addProperty("semanticDiagnostics", ScopeStore.getDiagnostics(context).size());
            assertEquals("before", ScopeStore.getAllDeclarations(context).get(0).name());
            assertEquals("before", ScopeStore.getAllReferences(context).get(0).name());
            assertEquals("baseline", ScopeStore.getDiagnostics(context).get(0).message());
            assertEquals(0, ScopeStore.getAllDeclarations(context).get(0).sourceOffset());
            assertEquals(0, ScopeStore.getAllReferences(context).get(0).offset());
            assertEquals(1, ScopeStore.getAllReferences(context).get(0).length());
            assertEquals(0, ScopeStore.getDiagnostics(context).get(0).offset());
            assertEquals(1, ScopeStore.getDiagnostics(context).get(0).length());
            assertEquals(ScopeStore.Severity.INFO, ScopeStore.getDiagnostics(context).get(0).severity());
            return result;
        }
    }

    private Parser child(String kind, State state) {
        Parser a = new WordParser("a"), b = new WordParser("b"), bang = new WordParser("!");
        return switch (kind) {
            case "literal", "java-literal" -> new WordParser("ab");
            case "unicode" -> new WordParser("😀\r\n界");
            case "optional" -> new Chain(a, b, new org.unlaxer.parser.combinator.Optional(bang));
            case "choice" -> new Choice(new Chain(a, b, bang), new WordParser("ab"));
            case "failure" -> new Chain(a, b, bang);
            case "nested" -> new MatchOnly(new WordParser("ab"));
            case "nested-sibling" -> new Chain(new MatchOnly(a), b);
            case "state" -> new Chain(new CaptureSite(new EffectWord(state, false)), new EffectWord(state, true));
            case "state-failure" -> new Chain(new CaptureSite(new EffectWord(state, false)), new EffectWord(state, true), bang);
            default -> throw new AssertionError(kind);
        };
    }

    private final class ObservedAssertion extends MatchOnly {
        JsonObject observation;
        ObservedAssertion(Parser child) { super(child); }
        @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
            Parsed parsed = super.parse(context, kind, invert);
            observation = new JsonObject(); observation.addProperty("accepted", parsed.isSucceeded());
            observation.addProperty("consumed", context.getConsumedPosition().value());
            observation.addProperty("matched", context.getMatchedPosition().value());
            var tokens = new JsonArray(); var captures = new JsonArray();
            if (parsed.isSucceeded()) for (Token token : parsed.getOriginalTokens()) AssertionContractConformanceTest.this.collect(token, tokens, captures);
            observation.add("tokens", tokens); observation.add("captures", captures);
            observation.add("span", parsed.isSucceeded() ? span(parsed.getRootToken(false)) : JsonNull.INSTANCE);
            return parsed;
        }
    }
    private static final class State implements TransactionalState {
        int value = 7;
        public Runnable checkpoint() { int before = value; return () -> value = before; }
    }
    private static final class CaptureSite extends Chain {
        CaptureSite(Parser child) { super(child); }
    }
    private static final class EffectWord extends WordParser implements DiagnosticsAgnostic {
        private final State state; private final boolean read;
        EffectWord(State state, boolean read) { super(read ? "b" : "a"); this.state = state; this.read = read; }
        @Override public Token getToken(ParseContext context, TokenKind kind, boolean invert) {
            if (read) { assertEquals(9, state.value); assertTrue(ScopeStore.isDeclared(context, "inside")); }
            Token token = super.getToken(context, kind, invert);
            if (!token.source.isEmpty() && !read) {
                state.value = 9; ScopeStore.enter(context);
                ScopeStore.declare(context, "inside", token.source.cursorRange().startInRoot().value());
                ScopeStore.addReference(context, "inside", 0, 1);
                ScopeStore.addDiagnostic(context, "speculation", 0, 1, ScopeStore.Severity.WARNING);
            }
            return token;
        }
    }
    private void collect(Token token, JsonArray leaves, JsonArray captures) {
        if (token.parser instanceof WordParser) leaves.add(span(token));
        if (token.parser instanceof CaptureSite) captures.add(span(token));
        token.getChildren(value -> true, Token.ChildrenKind.original).forEach(child -> collect(child, leaves, captures));
    }
    private JsonArray span(Token token) {
        var result = new JsonArray(); result.add(token.tokenKind.name());
        result.add(token.source.cursorRange().startInRoot().value()); result.add(token.source.cursorRange().endInRoot().value());
        result.add(token.source.sourceAsString()); return result;
    }
    private String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }
    private String run(List<String> command) throws Exception {
        Path log = temporary.newFile().toPath();
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("timeout " + command); }
        String output = Files.readString(log); assertEquals(command + "\n" + output, 0, process.exitValue()); return output;
    }
}
