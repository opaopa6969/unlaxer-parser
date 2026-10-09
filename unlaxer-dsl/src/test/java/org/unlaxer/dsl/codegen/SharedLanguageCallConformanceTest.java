package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;
import org.unlaxer.source.SharedGrammarCalls;

/** Shared original-source cursor, isolated child CST/state, and fixed independent prefix oracles. */
public class SharedLanguageCallConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();
    @Test public void generatedAdaptersRetainOwnedChildrenAndRollbackOnBothHosts() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] shared language call conformance requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path fixture = repo.resolve("docs/fixtures/shared-language-calls");
        Path directory = temporary.newFolder().toPath();
        var units = new ArrayList<JavaFileObject>();
        for (String name : List.of("ChildA", "ChildB", "Parent", "Boundary")) {
            Path input = fixture.resolve(name + ".ubnf");
            var grammar = UBNFMapper.parse(Files.readString(input)).grammars().get(0);
            GrammarValidator.validateOrThrow(grammar);
            for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
                var generated = generator.generate(grammar);
                units.add(new SimpleJavaFileObject(URI.create("string:///" + generated.packageName().replace('.', '/') + "/" + generated.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                    public CharSequence getCharContent(boolean ignored) { return generated.source(); }
                });
            }
            Path output = directory.resolve(name.toLowerCase(Locale.ROOT));
            run(List.of("cargo", "run", "--quiet", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator", "--", "generate", "--grammar", input.toString(), "--output", output.toString()));
            for (var generated : new RustBackend().generate(grammar)) assertEquals(name + " " + generated.relativePath(), generated.content(), Files.readString(output.resolve(generated.relativePath())));
        }
        try (var paths = Files.list(fixture.resolve("java"))) {
            for (Path path : paths.toList()) {
                String source = Files.readString(path);
                units.add(new SimpleJavaFileObject(URI.create("string:///example/shared/" + path.getFileName()), JavaFileObject.Kind.SOURCE) {
                    public CharSequence getCharContent(boolean ignored) { return source; }
                });
            }
        }
        Path classes = Files.createDirectory(directory.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler(); var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            assertTrue(diagnostics.getDiagnostics().toString(), compiler.getTask(null, manager, diagnostics, List.of("-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()), null, units).call());
        }
        var controlExpected = Files.readAllLines(fixture.resolve("controls.tsv"));
        var expected = new ArrayList<String>(controlExpected);
        expected.addAll(controlExpected);
        try (var loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            assertEquals(Files.readAllLines(fixture.resolve("lexical-sessions.tsv")), loader.loadClass("example.shared.Checks").getMethod("lexicalSessions").invoke(null));
            for (Memoization memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES))
                assertEquals(controlExpected, loader.loadClass("example.shared.Checks").getMethod("run", Memoization.class).invoke(null, memo));
            var parser = (Parser) loader.loadClass("example.shared.ParentParsers").getMethod("getRootParser").invoke(null);
            for (String row : Files.readAllLines(fixture.resolve("cases.tsv"))) {
                String[] f = row.split("\t", -1); String source = new String(HexFormat.of().parseHex(f[1]), StandardCharsets.UTF_8);
                for (Memoization memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES)) {
                    try (var context = ParseContext.withOptions(StringSource.createRootSource(source), ParseOptions.withMemoization(memo))) {
                        context.getGlobalScopeTreeMap().put(Name.of("sentinel"), "parent");
                        Parsed parsed = parser.parse(context);
                        assertEquals(f[0], Boolean.parseBoolean(f[2]), parsed.isSucceeded());
                        assertEquals(f[0], Integer.parseInt(f[3]), context.getPosition(TokenKind.consumed).value());
                        assertEquals("parent", context.getGlobalScopeTreeMap().get(Name.of("sentinel")));
                        assertTrue(context.getParserContextScopeTreeMap().values().stream().noneMatch(map -> map.containsKey(Name.of("private"))));
                        if (parsed.isSucceeded()) {
                            Token root = parsed.getRootToken(false); var calls = new ArrayList<SharedGrammarCalls.Call>(); collect(root, calls);
                            assertEquals(f[0], f[5].equals("-") ? 0 : 1, calls.size());
                            if (!calls.isEmpty()) {
                                var call = calls.get(0); assertEquals(f[5], call.language().grammar());
                                assertEquals(Integer.parseInt(f[6]), call.span().start()); assertEquals(Integer.parseInt(f[7]), call.span().end());
                                var childMapper = loader.loadClass("example.shared." + f[5] + "Mapper");
                                Object mapped = childMapper.getMethod("mapParsedTokenWithSourceMap", Token.class).invoke(null, call.tree());
                                Object ast = mapped.getClass().getMethod("ast").invoke(mapped);
                                assertEquals(f[5].equals("ChildA") ? "AValue" : "BValue", ast.getClass().getSimpleName());
                                assertEquals("a", ast.getClass().getMethod("value").invoke(ast));
                                var span = (Optional<int[]>) mapped.getClass().getMethod("sourceSpanOf", Object.class).invoke(mapped, ast);
                                assertArrayEquals(f[0], new int[]{Integer.parseInt(f[6]), Integer.parseInt(f[9])}, span.orElseThrow());
                            }
                            Object mapped = loader.loadClass("example.shared.ParentMapper").getMethod("mapParsedTokenWithSourceMap", Token.class).invoke(null, root);
                            Object ast = mapped.getClass().getMethod("ast").invoke(mapped);
                            assertEquals(f[8], ast.getClass().getMethod("value").invoke(ast));
                        } else assertEquals(f[0], Integer.parseInt(f[4]), context.getParseFailureDiagnostics().getFarthestOffset());
                    }
                }
                expected.add(f[0] + "\tPASS");
            }
        }
        String rustc = run(List.of("rustup", "which", "rustc", "--toolchain", "1.85.0")).trim();
        Path runtime = directory.resolve("libunlaxer_runtime.rlib");
        run(List.of(rustc, "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime", repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()));
        Path probe = directory.resolve("probe.rs"); Files.copy(fixture.resolve("probe.rs"), probe);
        run(List.of(rustc, "--edition=2021", "--extern", "unlaxer_runtime=" + runtime, probe.toString(), "-o", directory.resolve("probe").toString()));
        assertEquals(expected, run(List.of(directory.resolve("probe").toString(), fixture.resolve("cases.tsv").toString())).lines().toList());
        var sessions = Files.readAllLines(fixture.resolve("lexical-sessions.tsv"));
        String unitOutput = run(List.of("cargo", "test", "--quiet", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-runtime", "--lib", "direct_entry_isolates_and_restores", "--", "--nocapture", "--test-threads=1"));
        assertEquals(sessions, unitOutput.lines().filter(line -> line.contains("SHARED_SESSION\t")).map(line -> line.substring(line.indexOf("SHARED_SESSION\t") + "SHARED_SESSION\t".length())).toList());
        for (String row : sessions) expected.add("lexical-session\t" + row);
        Files.write(Path.of("target/shared-language-calls.tsv"), expected);
    }
    private void collect(Token token, List<SharedGrammarCalls.Call> calls) {
        SharedGrammarCalls.metadata(token).ifPresent(calls::add);
        for (Token child : token.getChildren(value -> true, Token.ChildrenKind.original).toList()) collect(child, calls);
    }
    private String run(List<String> command) throws Exception {
        Path log = temporary.newFile().toPath(); Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("timeout " + command); }
        String output = Files.readString(log); assertEquals(command + "\n" + output, 0, process.exitValue()); return output;
    }
}
