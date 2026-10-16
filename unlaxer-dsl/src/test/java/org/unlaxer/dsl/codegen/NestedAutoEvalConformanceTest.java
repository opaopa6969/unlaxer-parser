package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.dsl.Java21TestSupport;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.tools.*;

/** Existing automatic evaluation composed with typed nested mapped leaves; no new owner shapes. */
public class NestedAutoEvalConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();
    private final Path corpus = repo.resolve("spec-corpus/nested-auto-eval");
    private int command;

    private JsonArray cases(String name) throws Exception {
        return JsonParser.parseString(Files.readString(corpus.resolve(name + "-cases.json")))
                .getAsJsonArray();
    }

    @Test
    public void javaEvaluationAndRetainedPreferredSnapshotsMatchIndependentOracle()
            throws Exception {
        var report =
                new ArrayList<>(
                        List.of("grammar\tcase\tmemo\tdiagnostics\tselection\tphase\tresult"));
        for (String name : List.of("LiteralLeaf", "PassthroughLeaf")) {
            var grammar =
                    UBNFMapper.parse(Files.readString(corpus.resolve(name + ".ubnf")))
                            .grammars()
                            .get(0);
            try (var loader = compileJava(name)) {
                String pkg = new ParserGenerator().generate(grammar).packageName();
                var mapper = loader.loadClass(pkg + "." + name + "Mapper");
                var parser =
                        (Parser)
                                loader.loadClass(pkg + "." + name + "Parsers")
                                        .getMethod("getRootParser")
                                        .invoke(null);
                var probe = loader.loadClass(pkg + ".Probe").getMethod("run", Object.class);
                for (var raw : cases(name)) {
                    var row = raw.getAsJsonObject();
                    String input = row.get("input").getAsString();
                    for (var memo : Memoization.values())
                        for (var diagnostics : ParseOptions.Diagnostics.values()) {
                            Object tree;
                            try (var context =
                                    ParseContext.withOptions(
                                            StringSource.createRootSource(input),
                                            ParseOptions.withMemoization(memo)
                                                    .withDiagnostics(diagnostics))) {
                                assertTrue(input, parser.parse(context).isSucceeded());
                                if (row.has("error")) {
                                    assertTrue(
                                            "nullable grammar accepts a prefix; consumer must"
                                                + " require EOF",
                                            context.position()
                                                    < input.codePointCount(0, input.length()));
                                    assertEquals(
                                            row.get("error_cp").getAsInt(), context.position());
                                    assertEquals(context.position(), context.matchedPosition());
                                    report.add(
                                            name
                                                    + "\t"
                                                    + row.get("id").getAsString()
                                                    + "\t"
                                                    + memo
                                                    + "\t"
                                                    + diagnostics
                                                    + "\t-\t-\tfull-input-rejected@"
                                                    + context.position());
                                    continue;
                                }
                                assertEquals(row.get("consumed_cp").getAsInt(), context.position());
                                assertEquals(context.position(), context.matchedPosition());
                                Token token =
                                        context.getCurrent().getTokens().stream()
                                                .filter(t -> t.parser == parser)
                                                .findFirst()
                                                .orElseThrow();
                                assertEquals(input, token.source.toString());
                                tree =
                                        mapper.getMethod("mapParsedTree", Token.class)
                                                .invoke(null, token);
                            }
                            // The parser context is already closed before any callback runs.
                            var snapshots = new ArrayList<Object>();
                            var asts = new ArrayList<Object>();
                            for (String selection : List.of("default", "leaf")) {
                                Object snapshot = select(tree, selection);
                                snapshots.add(snapshot);
                                asts.add(snapshot.getClass().getMethod("ast").invoke(snapshot));
                                assertEquals(
                                        name + "/" + row.get("id") + "/" + selection,
                                        row.get(selection),
                                        probe.invoke(null, snapshot));
                                report.add(
                                        name
                                                + "\t"
                                                + row.get("id").getAsString()
                                                + "\t"
                                                + memo
                                                + "\t"
                                                + diagnostics
                                                + "\t"
                                                + selection
                                                + "\tinitial\tchecked");
                            }
                            // Equal leaf values at different CP positions must not replace the old
                            // span map.
                            try (var interference =
                                    ParseContext.withOptions(
                                            StringSource.createRootSource("!😀"),
                                            ParseOptions.withMemoization(memo)
                                                    .withDiagnostics(diagnostics))) {
                                assertTrue(parser.parse(interference).isSucceeded());
                                Token token =
                                        interference.getCurrent().getTokens().stream()
                                                .filter(t -> t.parser == parser)
                                                .findFirst()
                                                .orElseThrow();
                                mapper.getMethod("mapParsedTree", Token.class).invoke(null, token);
                            }
                            for (int i = 0; i < 2; i++) {
                                String selection = i == 0 ? "default" : "leaf";
                                Object selected = select(tree, selection);
                                assertSame(
                                        asts.get(i),
                                        selected.getClass().getMethod("ast").invoke(selected));
                                assertEquals(
                                        row.get(selection), probe.invoke(null, snapshots.get(i)));
                                assertEquals(row.get(selection), probe.invoke(null, selected));
                                report.add(
                                        name
                                                + "\t"
                                                + row.get("id").getAsString()
                                                + "\t"
                                                + memo
                                                + "\t"
                                                + diagnostics
                                                + "\t"
                                                + selection
                                                + "\tretained\tchecked");
                            }
                        }
                }
            }
        }
        assertEquals("all handwritten configurations", 265, report.size());
        Files.write(Path.of("target/nested-auto-eval-java.tsv"), report);
    }

    private Object select(Object tree, String selection) throws Exception {
        Object result =
                selection.equals("default")
                        ? tree.getClass().getMethod("selectDefault").invoke(tree)
                        : tree.getClass().getMethod("select", String.class).invoke(tree, "Leaf");
        return result.getClass().getMethod("sourceMap").invoke(result);
    }

    private URLClassLoader compileJava(String name) throws Exception {
        JavaCompiler compiler = Java21TestSupport.compiler();
        var grammar =
                UBNFMapper.parse(Files.readString(corpus.resolve(name + ".ubnf")))
                        .grammars()
                        .get(0);
        var units = new ArrayList<JavaFileObject>();
        for (CodeGenerator generator :
                List.of(
                        new ParserGenerator(),
                        new ASTGenerator(),
                        new MapperGenerator(),
                        new EvaluatorGenerator(21))) {
            var file = generator.generate(grammar);
            units.add(source(file.packageName() + "." + file.className(), file.source()));
        }
        String pkg = new ParserGenerator().generate(grammar).packageName();
        units.add(
                source(pkg + ".Probe", Files.readString(corpus.resolve(name + "-probe.java.txt"))));
        Path output = temporary.newFolder().toPath();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            boolean compiled =
                    compiler.getTask(
                                    null,
                                    manager,
                                    diagnostics,
                                    List.of(
                                            "--release",
                                            "21",
                                            "-classpath",
                                            System.getProperty("java.class.path"),
                                            "-d",
                                            output.toString()),
                                    null,
                                    units)
                            .call();
            assertTrue(diagnostics.getDiagnostics().toString(), compiled);
        }
        return new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader());
    }

    @Test
    public void bothRustHostsEvaluateTheSameNestedAndRetainedSnapshots() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println(
                    "[assumption] NestedAutoEvalConformanceTest requires -DrustConformance=true");
        assumeTrue(
                "requires -DrustConformance=true and rustc/cargo",
                Boolean.getBoolean("rustConformance"));
        Path target = temporary.newFolder("native-target").toPath();
        run(
                List.of(
                        "cargo",
                        "build",
                        "--locked",
                        "--manifest-path",
                        repo.resolve("rust/Cargo.toml").toString(),
                        "-p",
                        "unlaxer-generator",
                        "--target-dir",
                        target.toString()));
        Path deps = target.resolve("debug/deps");
        Path runtime = library(deps, "unlaxer_runtime"), json = library(deps, "serde_json");
        var report = new ArrayList<>(List.of("grammar\thost\tobservation"));
        for (String name : List.of("LiteralLeaf", "PassthroughLeaf")) {
            Path grammarFile = corpus.resolve(name + ".ubnf");
            var grammar = UBNFMapper.parse(Files.readString(grammarFile)).grammars().get(0);
            Path directory = temporary.newFolder(name).toPath(),
                    java = Files.createDirectory(directory.resolve("java")),
                    nativeOut = directory.resolve("native");
            for (var file : new RustBackend().generate(grammar))
                Files.writeString(java.resolve(file.relativePath()), file.content());
            run(
                    List.of(
                            target.resolve("debug/unlaxer").toString(),
                            "generate",
                            "--target",
                            "rust",
                            "--grammar",
                            grammarFile.toString(),
                            "--output",
                            nativeOut.toString()));
            List<String> javaFiles;
            List<String> nativeFiles;
            try (var files = Files.list(java)) {
                javaFiles = files.map(p -> p.getFileName().toString()).sorted().toList();
            }
            try (var files = Files.list(nativeOut)) {
                nativeFiles = files.map(p -> p.getFileName().toString()).sorted().toList();
            }
            assertEquals(javaFiles, nativeFiles);
            for (String file : javaFiles)
                assertArrayEquals(
                        file,
                        Files.readAllBytes(java.resolve(file)),
                        Files.readAllBytes(nativeOut.resolve(file)));
            for (Path host : List.of(java, nativeOut)) {
                Path main = host.resolve("main.rs"), binary = host.resolve("probe");
                Files.writeString(
                        main,
                        "#[path="
                                + quote(host.resolve("mod.rs"))
                                + "] mod generated;\ninclude!("
                                + quote(corpus.resolve(name + "-probe.rs"))
                                + ");\n");
                run(
                        List.of(
                                "rustc",
                                "--edition=2021",
                                "-Awarnings",
                                main.toString(),
                                "-L",
                                "dependency=" + deps,
                                "--extern",
                                "unlaxer_runtime=" + runtime,
                                "--extern",
                                "serde_json=" + json,
                                "-o",
                                binary.toString()));
                String output =
                        run(
                                List.of(
                                        binary.toString(),
                                        corpus.resolve(name + "-cases.json").toString()));
                var observations =
                        output.lines().filter(line -> line.startsWith("NESTED_AUTO\t")).toList();
                assertEquals(output, 132, observations.size());
                for (String line : observations)
                    report.add(
                            name
                                    + "\t"
                                    + host.getFileName()
                                    + "\t"
                                    + line.substring("NESTED_AUTO\t".length()));
            }
        }
        assertEquals("both actual Rust consumers", 529, report.size());
        Files.write(Path.of("target/nested-auto-eval-rust.tsv"), report);
    }

    private static Path library(Path deps, String name) throws Exception {
        try (var files = Files.list(deps)) {
            var matches =
                    files.filter(
                                    p ->
                                            p.getFileName()
                                                            .toString()
                                                            .startsWith("lib" + name + "-")
                                                    && p.toString().endsWith(".rlib"))
                            .toList();
            assertEquals(matches.toString(), 1, matches.size());
            return matches.get(0);
        }
    }

    private String run(List<String> args) throws Exception {
        Path log = temporary.newFile("command-" + command++ + ".log").toPath();
        var process =
                new ProcessBuilder(args)
                        .directory(repo.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        try {
            assertTrue("timeout " + args, process.waitFor(180, TimeUnit.SECONDS));
            String output = Files.readString(log);
            assertEquals(args + "\n" + output, 0, process.exitValue());
            return output;
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private static String quote(Path path) {
        return "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static JavaFileObject source(String name, String text) {
        return new SimpleJavaFileObject(
                URI.create("string:///" + name.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
    }
}
