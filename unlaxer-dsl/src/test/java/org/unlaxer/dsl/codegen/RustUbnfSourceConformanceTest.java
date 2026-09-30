package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFAST;
import org.unlaxer.dsl.bootstrap.UBNFAST.AnnotatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot.Span;

/** Shared source corpus, including all the existing frontend-positive syntax cases. */
public class RustUbnfSourceConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void everyNodeHasTheSameCodepointSpanAndOriginalText() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) {
            System.out.println("[assumption] RustUbnfSourceConformanceTest requires -DrustConformance=true and rustc");
        }
        assumeTrue("enable with -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        Path crate = repo.resolve("rust/unlaxer-ubnf");
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_ubnf.rlib");
        run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_ubnf",
            crate.resolve("src/lib.rs").toString(), "-o", library.toString()));
        Path binary = temporary.getRoot().toPath().resolve("source-spans");
        run(List.of("rustc", "--edition=2021", crate.resolve("examples/source_spans.rs").toString(),
            "--extern", "unlaxer_ubnf=" + library, "-o", binary.toString()));
        var inputs = new ArrayList<Path>();
        try (var paths = Files.list(crate.resolve("tests/fixtures/positive"))) {
            inputs.addAll(paths.sorted().toList());
        }
        inputs.add(crate.resolve("tests/fixtures/source/locations.ubnf"));
        inputs.add(crate.resolve("tests/fixtures/known/namespace.ubnf"));
        inputs.add(repo.resolve("unlaxer-dsl/grammar/ubnf.ubnf"));
        inputs.add(repo.resolve("unlaxer-dsl/tinycalc-vscode/grammar/tinycalc.ubnf"));
        inputs.add(repo.resolve("unlaxer-dsl/src/test/resources/cardinality/Cardinality.ubnf"));
        for (int i = 0; i < 4; i++) {
            inputs.add(repo.resolve("unlaxer-dsl/src/test/resources/evolution/" + i + "/Evolution.ubnf"));
        }
        var report = new ArrayList<>(List.of("case\tline_endings\tnodes\tresult"));
        var astOracle = new RustUbnfFrontendConformanceTest();
        for (Path input : inputs) {
            String original = Files.readString(input).replace("\r\n", "\n");
            for (String ending : List.of("LF", "CRLF")) {
                String source = ending.equals("LF") ? original : original.replace("\n", "\r\n");
                Path actualInput = temporary.newFile().toPath();
                Files.writeString(actualInput, source);
                var nativeResult = JsonParser.parseString(run(List.of(binary.toString(), actualInput.toString())))
                    .getAsJsonObject();
                var snapshot = UBNFMapper.parseWithSource(source);
                assertEquals(input.toString(), UBNFMapper.parse(source), snapshot.ast());
                assertEquals(input.toString(), astOracle.canonical(snapshot.ast()), nativeResult.get("ast"));
                var java = new JsonObject();
                walk(snapshot, java, "file", snapshot.ast());
                var rust = nativeResult.getAsJsonObject("spans");
                assertEquals(input + " " + ending + " paths", java.keySet(), rust.keySet());
                for (String path : java.keySet()) {
                    assertEquals(input + " " + ending + " " + path, rust.get(path), java.get(path));
                }
                report.add(input.getFileName() + "\t" + ending + "\t" + java.size() + "\tequal");
            }
        }
        Files.write(Path.of("target/rust-ubnf-source.tsv"), report);
    }

    private static void walk(UBNFSourceSnapshot snapshot, JsonObject rows, String path, Object node) throws Exception {
        if (node instanceof Optional<?> optional) {
            if (optional.isPresent()) walk(snapshot, rows, path, optional.get());
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) walk(snapshot, rows, path + "[" + i + "]", list.get(i));
        } else if (node instanceof UBNFAST) {
            Span span = snapshot.spanOf(node).orElseThrow(() -> new AssertionError("missing span: " + path + " " + node));
            row(snapshot, rows, path, span);
            // Java represents the postfix ?/* synthetic body directly as SequenceBody;
            // Rust represents it as a one-alternative RuleBody. Preserve both layers in the oracle.
            if (node instanceof SequenceBody && path.endsWith(".body")) {
                walk(snapshot, rows, path + ".alternatives[0]", node);
                return;
            }
            if (node instanceof AnnotatedElement annotated) {
                assertEquals(path, annotated.captureName().isPresent(), snapshot.captureSpan(annotated).isPresent());
                if (snapshot.captureSpan(annotated).isPresent()) {
                    row(snapshot, rows, path + ".capture", snapshot.captureSpan(annotated).orElseThrow());
                }
            }
            for (var component : node.getClass().getRecordComponents()) {
                walk(snapshot, rows, path + "." + component.getName(), component.getAccessor().invoke(node));
            }
        }
    }

    private static void row(UBNFSourceSnapshot snapshot, JsonObject rows, String path, Span span) {
        var row = new JsonArray();
        row.add(span.start());
        row.add(span.end());
        row.add(snapshot.slice(span));
        assertFalse(path, rows.has(path));
        rows.add(path, row);
    }

    private String run(List<String> command) throws Exception {
        Path log = temporary.newFile().toPath();
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("process timed out: " + command);
        }
        String output = Files.readString(log);
        assertEquals(command + "\n" + output, 0, process.exitValue());
        return output;
    }
}
