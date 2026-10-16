package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Same recognizer, handwritten cursors/CST spans, and finite retention through both Rust hosts. */
public class MemoScalingConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();
    private int command;

    @Test
    public void bothRustHostsPreserveTheScalingFixtureWithinFiniteBudgets() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println(
                    "[assumption] MemoScalingConformanceTest requires -DrustConformance=true");
        assumeTrue(
                "requires -DrustConformance=true and rustc/cargo",
                Boolean.getBoolean("rustConformance"));
        Path corpus = repo.resolve("conformance/memo-scaling");
        Path java = temporary.newFolder("java").toPath();
        var grammar =
                UBNFMapper.parse(Files.readString(corpus.resolve("scale.ubnf"))).grammars().get(0);
        for (var file : new RustBackend().generate(grammar))
            Files.writeString(java.resolve(file.relativePath()), file.content());
        Path nativeTarget = temporary.newFolder("native-target").toPath();
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
                        nativeTarget.toString()),
                Map.of());
        Path nativeOutput = temporary.getRoot().toPath().resolve("native");
        run(
                List.of(
                        nativeTarget.resolve("debug/unlaxer").toString(),
                        "generate",
                        "--target",
                        "rust",
                        "--grammar",
                        corpus.resolve("scale.ubnf").toString(),
                        "--output",
                        nativeOutput.toString()),
                Map.of());
        var names = new TreeSet<String>();
        try (var files = Files.list(java)) {
            files.forEach(p -> names.add(p.getFileName().toString()));
        }
        var nativeNames = new TreeSet<String>();
        try (var files = Files.list(nativeOutput)) {
            files.forEach(p -> nativeNames.add(p.getFileName().toString()));
        }
        assertEquals(names, nativeNames);
        for (String name : names)
            assertArrayEquals(
                    name,
                    Files.readAllBytes(java.resolve(name)),
                    Files.readAllBytes(nativeOutput.resolve(name)));
        Path runtime = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        run(
                List.of(
                        "rustc",
                        "--edition=2021",
                        "--crate-type=rlib",
                        "--crate-name=unlaxer_runtime",
                        repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),
                        "-o",
                        runtime.toString()),
                Map.of());
        var report =
                new ArrayList<>(
                        List.of(
                                "host\tbudget_bytes\tscale\tmemo\tconsumed_cp\tmatched_cp"
                                    + "\tcst_end_cp\tentries\tcharge\tbudget_misses"
                                    + "\tactual_success_hits"));
        for (Path host : List.of(java, nativeOutput)) {
            Path main = host.resolve("probe.rs");
            Files.writeString(
                    main,
                    "#[path="
                            + quote(host.resolve("mod.rs"))
                            + "] mod generated;\ninclude!("
                            + quote(corpus.resolve("main.rs"))
                            + ");\n");
            Path binary = host.resolve("probe");
            run(
                    List.of(
                            "rustc",
                            "--edition=2021",
                            "-Awarnings",
                            main.toString(),
                            "--extern",
                            "unlaxer_runtime=" + runtime,
                            "-o",
                            binary.toString()),
                    Map.of());
            for (long budget : new long[] {16L * 1024 * 1024, 0, 64L * 1024 * 1024, 65536}) {
                String output =
                        run(
                                List.of(
                                        binary.toString(),
                                        corpus.resolve("cases.tsv").toString(),
                                        Long.toString(budget)),
                                Map.of(
                                        "UNLAXER_SUCCESS_MEMO_MAX_BYTES",
                                        Long.toString(budget),
                                        "UNLAXER_SUCCESS_MEMO_MAX_ENTRY_BYTES",
                                        "2097152",
                                        "UNLAXER_SUCCESS_MEMO_MAX_ENTRIES",
                                        "65536"));
                List<String> observations =
                        output.lines().filter(l -> l.startsWith("MEMO_SCALE\t")).toList();
                assertEquals(output, 8, observations.size());
                for (String line : observations)
                    report.add(
                            host.getFileName()
                                    + "\t"
                                    + budget
                                    + "\t"
                                    + line.substring("MEMO_SCALE\t".length()));
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/memo-scaling-rust.tsv"), report);
    }

    private static String quote(Path path) {
        return "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String run(List<String> args, Map<String, String> environment) throws Exception {
        Path log = temporary.newFile("command-" + command++ + ".log").toPath();
        var builder =
                new ProcessBuilder(args)
                        .directory(repo.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            fail("timeout: " + args + "\n" + Files.readString(log));
        }
        String output = Files.readString(log);
        assertEquals(args + "\n" + output, 0, process.exitValue());
        return output;
    }
}
