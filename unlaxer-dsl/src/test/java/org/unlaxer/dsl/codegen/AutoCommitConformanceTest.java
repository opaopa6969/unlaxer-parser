package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.*;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.TokenPrinter;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.dsl.bootstrap.AutoCommit;
import org.unlaxer.dsl.bootstrap.UBNFAST;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;

/**
 * {@code @autoCommit} and the cut-position lint (#702) against {@code spec-corpus/auto-commit}, the
 * corpus the native {@code rust/unlaxer-ubnf/tests/auto_commit.rs} reads. Each runtime row is an
 * annotated grammar, the same grammar with the commits written by hand, and a handwritten oracle;
 * the grammar without the annotation must accept exactly the same inputs with the same CST.
 */
public class AutoCommitConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    private static JsonArray corpus(String file) throws Exception {
        return JsonParser.parseString(Files.readString(ROOT.resolve("spec-corpus/auto-commit/" + file))).getAsJsonArray();
    }

    private static String plain(String grammar) {
        return grammar.replace("@autoCommit ", "").replace("@autoCommit\n  ", "");
    }

    @Test public void lintReportsTheCorpusPositions() throws Exception {
        var rows = corpus("lint.json");
        assertEquals(23, rows.size());
        int reported = 0;
        for (var raw : rows) {
            var row = raw.getAsJsonObject();
            String name = row.get("name").getAsString(), source = row.get("grammar").getAsString();
            var snapshot = UBNFMapper.parseWithSource(source);
            var findings = AutoCommit.lint(source);
            var expected = row.getAsJsonArray("findings");
            assertEquals(name + " " + findings, expected.size(), findings.size());
            for (int i = 0; i < findings.size(); i++) {
                var finding = findings.get(i);
                var want = expected.get(i).getAsJsonObject();
                assertEquals(name, want.get("rule").getAsString(), finding.rule());
                var span = finding.span().orElseThrow();
                assertEquals(name, want.get("alternative").getAsString(), snapshot.slice(span));
                assertEquals(name, want.get("first").getAsString(),
                    snapshot.slice(new UBNFSourceSnapshot.Span(span.start(), finding.cut())));
                List<String> wanted = new ArrayList<>();
                want.getAsJsonArray("commits").forEach(value -> wanted.add(value.getAsString()));
                assertEquals(name, wanted, finding.commits().stream().map(AutoCommit.Commit::name).toList());
                for (var commit : finding.commits()) {
                    String written = snapshot.slice(commit.span().orElseThrow());
                    assertTrue(name + " " + written, written.equals(commit.name())
                        || written.startsWith(commit.name().split("_")[0]));
                }
                assertEquals(name, want.get("inserted").getAsBoolean(), finding.inserted());
                assertEquals("info", finding.severity());
                assertTrue(name, finding.message().startsWith("I-AUTO-COMMIT: "));
                reported++;
            }
        }
        assertEquals(21, reported);
    }

    /** {@code W-AUTO-COMMIT-SKIPPED} for annotations the analysis cannot use, located at the annotation. */
    @Test public void skippedAnnotationsAreWarnedAbout() throws Exception {
        var rows = corpus("warnings.json");
        assertEquals(5, rows.size());
        for (var raw : rows) {
            var row = raw.getAsJsonObject();
            String name = row.get("name").getAsString(), source = row.get("grammar").getAsString();
            var snapshot = AutoCommit.parseBeforeAutoCommit(source);
            var warnings = AutoCommit.lintWarnings(source);
            var expected = row.getAsJsonArray("warnings");
            assertEquals(name + " " + warnings, expected.size(), warnings.size());
            for (int i = 0; i < warnings.size(); i++) {
                var warning = warnings.get(i);
                var want = expected.get(i).getAsJsonObject();
                assertEquals(name, want.get("rule").getAsString(), warning.rule());
                assertEquals(name, want.get("reason").getAsString(), warning.reason());
                assertEquals("W-AUTO-COMMIT-SKIPPED", warning.code());
                assertEquals("warning", warning.severity());
                assertEquals(name, "@autoCommit", snapshot.slice(warning.span().orElseThrow()));
                assertTrue(name, warning.message().startsWith("W-AUTO-COMMIT-SKIPPED: "));
            }
            // The rewritten tree no longer carries the annotation.
            var rewritten = UBNFMapper.parseWithSource(source);
            for (var grammar : rewritten.ast().grammars()) assertEquals(name, List.of(), AutoCommit.warnings(grammar, rewritten));
        }
    }

    /** {@code findings} describes the tree before the rewrite; the rewritten tree hides inserted commits. */
    @Test public void findingsTakeTheTreeBeforeTheRewrite() throws Exception {
        String source = corpus("lint.json").get(1).getAsJsonObject().get("grammar").getAsString();
        var before = AutoCommit.parseBeforeAutoCommit(source);
        assertSame(before.ast().getClass(), UBNFMapper.parseWithSourceBeforeAutoCommit(source).ast().getClass());
        var findings = AutoCommit.findings(before.ast().grammars().get(0), before);
        assertEquals(AutoCommit.lint(source), findings);
        assertEquals(2, findings.stream().filter(AutoCommit.Finding::inserted).count());
        var after = UBNFMapper.parseWithSource(source);
        assertEquals(0, AutoCommit.findings(after.ast().grammars().get(0), after).stream()
            .filter(f -> f.rule().equals("Statement")).count());
    }

    /** Positions are those of the native frontend for every node the rewrite creates or rebuilds. */
    @Test public void rewrittenElementsKeepTheirSourceSpans() {
        String source = "grammar G {\n  @root\n  Root ::= { S } ;\n  @autoCommit\n  S ::= 'k' N @n ;\n  N ::= 'n' ;\n}\n";
        var snapshot = UBNFMapper.parseWithSource(source);
        var rule = snapshot.ast().grammars().get(0).rules().get(1);
        assertTrue(rule.annotations().isEmpty());
        var sequence = ((UBNFAST.ChoiceBody) rule.body()).alternatives().get(0);
        var wrapper = sequence.elements().get(1);
        var commit = (UBNFAST.CommitElement) wrapper.element();
        assertEquals(Optional.empty(), commit.message());
        var body = (UBNFAST.ChoiceBody) commit.body();
        var inner = body.alternatives().get(0).elements().get(0);
        assertEquals(Optional.of("n"), inner.captureName());
        var span = snapshot.spanOf(inner).orElseThrow();
        assertEquals("N @n", snapshot.slice(span));
        for (Object node : List.of(wrapper, commit, body, body.alternatives().get(0))) {
            assertEquals(Optional.of(span), snapshot.spanOf(node));
            assertEquals(UBNFSourceSnapshot.Kind.SYNTHETIC, snapshot.originOf(node).orElseThrow().kind());
        }
        assertEquals(Optional.empty(), snapshot.captureSpan(wrapper));
        assertTrue(snapshot.captureSpan(inner).isPresent());
        for (Object node : List.of(sequence, rule, rule.body(), snapshot.ast().grammars().get(0))) {
            assertTrue(snapshot.originOf(node).isPresent());
        }
        assertEquals(UBNFMapper.parse(source), snapshot.ast());
    }

    /** The rewrite equals the hand-written grammar and generates byte-identical code on every path. */
    @Test public void rewriteEqualsTheHandWrittenCommits() throws Exception {
        var rows = corpus("cases.json");
        assertEquals(6, rows.size());
        for (var raw : rows) {
            var row = raw.getAsJsonObject();
            String name = row.get("name").getAsString();
            var auto = UBNFMapper.parse(row.get("grammar").getAsString());
            var manual = UBNFMapper.parse(row.get("manual").getAsString());
            assertEquals(name, manual, auto);
            assertEquals(name, manual, UBNFMapper.parseWithSource(row.get("grammar").getAsString()).ast());
            var grammar = auto.grammars().get(0);
            assertEquals(name, List.of(), RaisingOperators.problems(grammar));
            var unannotated = UBNFMapper.parse(plain(row.get("grammar").getAsString())).grammars().get(0);
            assertSame(name, unannotated, AutoCommit.apply(unannotated));
            assertSame(name, grammar, AutoCommit.apply(grammar));
            var manualGrammar = manual.grammars().get(0);
            for (CodeGenerator generator : List.<CodeGenerator>of(new ParserGenerator(), new ASTGenerator(),
                    new MapperGenerator(), new LSPGenerator())) {
                assertEquals(name, generator.generate(manualGrammar).source(), generator.generate(grammar).source());
            }
            assertEquals(name, new RustBackend().generate(manualGrammar), new RustBackend().generate(grammar));
            // Every inserted commit is a lint finding of an annotated rule, and only those are.
            long inserted = AutoCommit.lint(row.get("grammar").getAsString()).stream().filter(AutoCommit.Finding::inserted)
                .mapToLong(f -> f.commits().size()).sum();
            assertEquals(name, row.get("manual").getAsString().split("COMMIT\\(", -1).length - 1, inserted);
        }
    }

    /** outcome, offset, message, label: the tuple every host prints for one input and option. */
    private static String expected(JsonObject value) {
        String outcome = value.get("outcome").getAsString();
        String offset = value.has("offset") ? value.get("offset").getAsString() : "";
        String message = value.has("message") ? value.get("message").getAsString() : "";
        return outcome + "\t" + offset + "\t" + message + "\t";
    }

    private Path compileJava(String source) throws Exception {
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        var units = new ArrayList<JavaFileObject>();
        for (CodeGenerator generator : List.<CodeGenerator>of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
            var generated = generator.generate(grammar);
            units.add(source(generated.packageName() + "." + generated.className(), generated.source()));
        }
        Path classes = temporary.newFolder().toPath();
        var compiler = org.unlaxer.dsl.Java21TestSupport.compiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            assertTrue(diagnostics.getDiagnostics().toString(), compiler.getTask(null, manager, diagnostics,
                List.of("--release", "21", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                null, units).call());
        }
        return classes;
    }

    /** One line per input and option: the oracle tuple, then the CST of an accepted parse. */
    private List<String> runJava(String source, JsonArray cases) throws Exception {
        List<String> lines = new ArrayList<>();
        try (var loader = new URLClassLoader(new URL[] {compileJava(source).toUri().toURL()}, getClass().getClassLoader())) {
            var parser = (Parser) loader.loadClass("cutprobe.CutParsers").getMethod("getRootParser").invoke(null);
            var diagnose = loader.loadClass("cutprobe.CutMapper").getMethod("diagnose", String.class, ParseOptions.class);
            for (var value : cases) {
                String input = value.getAsJsonObject().get("input").getAsString();
                for (var memo : Memoization.values()) for (var mode : ParseOptions.Diagnostics.values()) {
                    var options = ParseOptions.withMemoization(memo).withDiagnostics(mode);
                    String cst = "";
                    try (var context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
                        var parsed = parser.parse(context);
                        if (parsed.isSucceeded() && context.getConsumedPosition().value() == input.codePointCount(0, input.length())) {
                            cst = TokenPrinter.get(parsed.getRootToken()).replace("\n", "|");
                        }
                    }
                    var diagnostic = (Optional<?>) diagnose.invoke(null, input, options);
                    String line;
                    if (diagnostic.isEmpty()) {
                        line = "success\t\t\t";
                    } else {
                        var record = diagnostic.get();
                        String kind = (String) record.getClass().getMethod("kind").invoke(record);
                        int offset = (int) record.getClass().getMethod("offset").invoke(record);
                        @SuppressWarnings("unchecked")
                        var hints = (List<String>) record.getClass().getMethod("expected").invoke(record);
                        line = kind.equals("commit") || kind.equals("label")
                            ? kind + "\t" + offset + "\t" + String.join("|", hints) + "\t" : "syntax\t\t\t";
                    }
                    assertEquals(input + " " + memo + " " + mode, line.startsWith("success"), !cst.isEmpty());
                    lines.add(line + "\t" + cst);
                }
            }
        }
        return lines;
    }

    private static final int OPTIONS = Memoization.values().length * ParseOptions.Diagnostics.values().length;

    /** Checks one host's lines for auto, manual and the unannotated grammar against the oracle. */
    private static int compare(String label, JsonArray cases, List<String> auto, List<String> manual, List<String> plain) {
        assertEquals(label, cases.size() * 6, auto.size());
        assertEquals(label + " hand-written commits", auto, manual);
        int index = 0, accepted = 0;
        for (var value : cases) {
            for (int option = 0; option < 6; option++, index++) {
                String line = auto.get(index), before = plain.get(index);
                assertEquals(label + " " + value + " option=" + option, expected(value.getAsJsonObject()),
                    line.substring(0, line.lastIndexOf('\t')));
                boolean success = line.startsWith("success");
                assertEquals(label + " acceptance before insertion " + value, success, before.startsWith("success"));
                if (success) {
                    assertEquals(label + " CST before insertion " + value, before, line);
                    accepted++;
                }
            }
        }
        return accepted;
    }

    @Test public void javaParsersMatchTheOracleAndTheUnannotatedGrammar() throws Exception {
        assertEquals(6, OPTIONS);
        int observations = 0, accepted = 0;
        for (var raw : corpus("cases.json")) {
            var row = raw.getAsJsonObject();
            var cases = row.getAsJsonArray("cases");
            String grammar = row.get("grammar").getAsString();
            var auto = runJava(grammar, cases);
            accepted += compare(row.get("name").getAsString() + " Java", cases, auto,
                runJava(row.get("manual").getAsString(), cases), runJava(plain(grammar), cases));
            observations += auto.size();
        }
        assertEquals(49 * 6, observations);
        assertEquals(15 * 6, accepted);
    }

    @Test public void bothRustHostsMatchTheOracleAndTheUnannotatedGrammar() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] AutoCommitConformanceTest Rust hosts require -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        run(List.of("cargo", "build", "--locked", "--manifest-path", ROOT.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), 0);
        int accepted = 0;
        for (var raw : corpus("cases.json")) {
            var row = raw.getAsJsonObject();
            String name = row.get("name").getAsString();
            var cases = row.getAsJsonArray("cases");
            Path dir = temporary.newFolder().toPath();
            Map<String, String> sources = new LinkedHashMap<>();
            sources.put("auto", row.get("grammar").getAsString());
            sources.put("manual", row.get("manual").getAsString());
            sources.put("plain", plain(row.get("grammar").getAsString()));
            var cargo = new StringBuilder("[package]\nname=\"auto-commit-probe\"\nversion=\"0.1.0\"\nedition=\"2021\"\n");
            for (var entry : sources.entrySet()) {
                String variant = entry.getKey();
                Path input = dir.resolve(variant + ".ubnf"), out = dir.resolve(variant);
                Files.writeString(input, entry.getValue());
                run(List.of(ROOT.resolve("rust/target/debug/unlaxer").toString(), "generate", "--grammar", input.toString(),
                    "--output", out.toString()), 0);
                var grammar = UBNFMapper.parse(entry.getValue()).grammars().get(0);
                for (var file : new RustBackend().generate(grammar)) {
                    assertEquals(name + " " + variant + " " + file.relativePath(), file.content(), Files.readString(out.resolve(file.relativePath())));
                    if (variant.equals("manual")) {
                        assertEquals(name + " hand-written " + file.relativePath(),
                            Files.readString(dir.resolve("auto").resolve(file.relativePath())), file.content());
                    }
                }
                Path java = Files.createDirectories(dir.resolve(variant + "-java"));
                for (var file : new RustBackend().generate(grammar)) Files.writeString(java.resolve(file.relativePath()), file.content());
                Files.writeString(dir.resolve(variant + ".rs"), RUST_PROBE.replace("mod generated;", "#[path=\"" + variant + "/mod.rs\"] mod generated;"));
                Files.writeString(dir.resolve(variant + "-java.rs"), RUST_PROBE.replace("mod generated;", "#[path=\"" + variant + "-java/mod.rs\"] mod generated;"));
                cargo.append("[[bin]]\nname=\"").append(variant).append("\"\npath=\"").append(variant).append(".rs\"\n");
                cargo.append("[[bin]]\nname=\"").append(variant).append("-java\"\npath=\"").append(variant).append("-java.rs\"\n");
            }
            cargo.append("[dependencies]\nunlaxer-runtime={path=\"").append(ROOT.resolve("rust/unlaxer-runtime")).append("\"}\n");
            Files.writeString(dir.resolve("Cargo.toml"), cargo.toString());
            Path target = ROOT.resolve("rust/target/auto-commit-probe");
            run(List.of("cargo", "build", "--offline", "--quiet", "--manifest-path", dir.resolve("Cargo.toml").toString(), "--target-dir", target.toString()), 0);
            Map<String, List<String>> outputs = new HashMap<>();
            for (String binary : List.of("auto", "auto-java", "manual", "manual-java", "plain", "plain-java")) {
                var command = new ArrayList<String>(List.of(target.resolve("debug/" + binary).toString()));
                for (var value : cases) command.add(value.getAsJsonObject().get("input").getAsString());
                outputs.put(binary, run(command, 0).lines().toList());
            }
            for (String host : List.of("", "-java")) {
                accepted += compare(name + " Rust" + host, cases, outputs.get("auto" + host), outputs.get("manual" + host), outputs.get("plain" + host));
            }
            assertEquals(name + " independently executed Rust hosts", outputs.get("auto"), outputs.get("auto-java"));
        }
        assertEquals(2 * 15 * 6, accepted);
    }

    private static final String RUST_PROBE = """
        #![allow(dead_code, unused_imports, unused_variables)]
        mod generated;
        use unlaxer_runtime::*;
        fn main() {
            for input in std::env::args().skip(1) {
                for memo in [Memoization::Off, Memoization::SafeFailures] {
                    for mode in [Diagnostics::Auto, Diagnostics::Detailed, Diagnostics::DetailedOnFailure] {
                        let options = ParseOptions::with_memoization(memo).with_diagnostics(mode);
                        let line = match generated::parser::parse_tree_detailed_with_options(&input, options) {
                            Ok(tree) => format!("success\\t\\t\\t\\t{}|{:?}", tree.root, tree.nodes),
                            Err(error) if error.kind == "commit" || error.kind == "label" => {
                                format!("{}\\t{}\\t{}\\t\\t", error.kind, error.offset, error.expected.join("|"))
                            }
                            Err(_) => "syntax\\t\\t\\t\\t".to_owned(),
                        };
                        println!("{line}");
                    }
                }
            }
        }
        """;

    private String run(List<String> command, int expected) throws Exception {
        Path log = temporary.newFile().toPath();
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("CARGO_INCREMENTAL", "0");
        var process = builder.start();
        if (!process.waitFor(300, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("timeout " + command); }
        String output = Files.readString(log);
        if (expected == 0) assertEquals(command + "\n" + output, 0, process.exitValue());
        return output;
    }

    private static JavaFileObject source(String name, String body) {
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return body; }
        };
    }
}
