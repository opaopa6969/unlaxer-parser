package org.unlaxer.pipeline;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.unlaxer.pipeline.AnalysisPipeline.*;
import org.unlaxer.source.DocumentSnapshot;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.SegmentSourceMap;
import org.unlaxer.source.SegmentSourceMap.*;

public class AnalysisPipelineTest {
    private static Phase phase(String id, List<String> dependencies, List<String> inputs, List<String> keys) {
        return new Phase(id, dependencies, inputs, keys, false);
    }
    private static Artifact source(DocumentSnapshot snapshot) {
        return new Artifact(State.COMPLETE, snapshot.text(), List.of(new Location(snapshot, new Span(0, snapshot.length()))), List.of());
    }
    private static AnalysisPipeline pipeline() {
        return new AnalysisPipeline(List.of(
                phase("resolve", List.of("collect"), List.of(), List.of()),
                phase("collect", List.of("include"), List.of("main"), List.of("active")),
                phase("include", List.of(), List.of("included"), List.of())), Map.of(
                "include", request -> source(request.inputs().get("included")),
                "collect", request -> {
                    if (request.configuration().getOrDefault("active", "true").equals("false")) { return Artifact.empty(State.INACTIVE); }
                    DocumentSnapshot main = request.inputs().get("main");
                    Artifact included = request.dependencies().get("include");
                    State state = main.text().contains("?") ? State.PARTIAL : main.text().equals("bad") ? State.FAILED : State.COMPLETE;
                    return new Artifact(state, main.text() + "|" + included.payload(),
                            List.of(new Location(main, new Span(0, main.length())), included.origins().get(0)), List.of());
                },
                "resolve", request -> request.dependencies().get("collect")));
    }
    private static Path fixture() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && false == Files.isDirectory(directory.resolve("docs/fixtures/pipeline"))) { directory = directory.getParent(); }
        if (directory == null) { throw new AssertionError("fixture directory missing"); }
        return directory.resolve("docs/fixtures/pipeline/evaluations.tsv");
    }
    @Test public void sharedDependencyAndConfigurationCorpus() throws Exception {
        AnalysisPipeline pipeline = pipeline();
        long previousRevision = 0;
        for (String line : Files.readAllLines(fixture())) {
            if (line.startsWith("#")) { continue; }
            String[] fields = line.split("\t");
            DocumentSnapshot main = new DocumentSnapshot("main", Long.parseLong(fields[1]), fields[2]);
            DocumentSnapshot included = new DocumentSnapshot("included", Long.parseLong(fields[3]), fields[4]);
            Result result = pipeline.evaluate("resolve", Map.of("main", main, "included", included),
                    Map.of("active", fields[5], "unrelated", fields[6]), 3, false);
            assertEquals(fields[0], State.valueOf(fields[7]), result.artifact().state());
            assertEquals(fields[0], fields[8].equals("-") ? "" : fields[8], result.artifact().payload());
            assertEquals(fields[0], fields[9].equals("-") ? "" : fields[9], String.join(",", result.evaluated()));
            assertEquals(fields[0], fields[10].equals("-") ? "" : fields[10], String.join(",", result.reused()));
            assertTrue(result.revision().isPresent());
            if (result.evaluated().isEmpty()) { assertEquals(previousRevision, result.revision().getAsLong()); }
            else { assertTrue(result.revision().getAsLong() > previousRevision); }
            previousRevision = result.revision().getAsLong();
            if (result.artifact().state() != State.INACTIVE) {
                assertEquals(List.of(main, included), result.artifact().origins().stream().map(Location::snapshot).toList());
            }
        }
    }
    @Test public void incompleteStatesDoNotReuseSuccess() {
        AnalysisPipeline pipeline = pipeline();
        Map<String, DocumentSnapshot> input = Map.of("main", new DocumentSnapshot("main", 1, "x"), "included", new DocumentSnapshot("included", 1, "y"));
        assertEquals(State.COMPLETE, pipeline.evaluate("resolve", input, Map.of(), 3, false).artifact().state());
        assertEquals(State.DEFERRED, pipeline.evaluate("resolve", Map.of(), Map.of(), 3, false).artifact().state());
        Result limited = pipeline.evaluate("resolve", input, Map.of(), 2, false);
        assertEquals(State.LIMIT, limited.artifact().state()); assertTrue(limited.revision().isEmpty());
        assertEquals(State.COMPLETE, pipeline.evaluate("resolve", input, Map.of(), 3, false).artifact().state());
        pipeline.clearCache();
        assertEquals(List.of("include", "collect", "resolve"), pipeline.evaluate("resolve", input, Map.of(), 3, false).evaluated());
        assertThrows(IllegalArgumentException.class, () -> pipeline.evaluate("resolve", input, Map.of(), 257, false));
    }
    @Test public void cyclesMissingProvidersAndExecutionPermission() {
        AtomicInteger called = new AtomicInteger();
        Executor executor = request -> { called.incrementAndGet(); return Artifact.empty(State.COMPLETE); };
        AnalysisPipeline cyclic = new AnalysisPipeline(List.of(
                phase("a", List.of("b"), List.of(), List.of()), phase("b", List.of("a"), List.of(), List.of())),
                Map.of("a", executor, "b", executor));
        assertEquals(State.CYCLE, cyclic.evaluate("a", Map.of(), Map.of(), 2, false).artifact().state());
        assertEquals(0, called.get());
        AnalysisPipeline missing = new AnalysisPipeline(List.of(phase("a", List.of(), List.of(), List.of())), Map.of());
        assertEquals(State.UNSUPPORTED, missing.evaluate("a", Map.of(), Map.of(), 1, false).artifact().state());
        AnalysisPipeline external = new AnalysisPipeline(List.of(new Phase("a", List.of(), List.of(), List.of(), true)), Map.of("a", executor));
        assertEquals(State.UNSUPPORTED, external.evaluate("a", Map.of(), Map.of(), 1, false).artifact().state());
        assertEquals(0, called.get());
        assertEquals(State.COMPLETE, external.evaluate("a", Map.of(), Map.of(), 1, true).artifact().state());
        assertEquals(1, called.get());
        assertEquals(State.UNSUPPORTED, external.evaluate("a", Map.of(), Map.of(), 1, false).artifact().state());
        assertEquals(1, called.get());
    }
    @Test public void generatedDiagnosticOriginsSurviveArtifactsAndRepair() {
        DocumentSnapshot original = new DocumentSnapshot("original", 3, "日😀");
        DocumentSnapshot generated = new DocumentSnapshot("generated", 3, "[日😀]");
        SegmentSourceMap map = new SegmentSourceMap(generated, List.of(
                new Segment(new Span(0, 1), Kind.GENERATED, new Location(original, new Span(0, 0))),
                new Segment(new Span(1, 3), Kind.COPY, new Location(original, new Span(0, 2))),
                new Segment(new Span(3, 4), Kind.GENERATED, new Location(original, new Span(2, 2)))));
        Artifact partial = new Artifact(State.PARTIAL, "recovered", List.of(new Location(original, new Span(0, 2))), map.diagnostics(new Span(2, 3)));
        AnalysisPipeline pipeline = new AnalysisPipeline(List.of(
                phase("repair", List.of("parse"), List.of(), List.of()), phase("parse", List.of(), List.of("main"), List.of())), Map.of(
                "parse", request -> partial,
                "repair", request -> { Artifact input = request.dependencies().get("parse"); return new Artifact(State.COMPLETE, input.payload(), input.origins(), input.diagnostics()); }));
        Result result = pipeline.evaluate("repair", Map.of("main", original), Map.of(), 2, false);
        assertEquals(State.COMPLETE, result.artifact().state());
        assertEquals(new Location(original, new Span(1, 2)), result.artifact().diagnostics().get(0).location());
        assertThrows(IllegalArgumentException.class, () -> map.edit(new Span(0, 1)));
    }
    @Test public void sharedDependenciesAreEvaluatedOnceWithinBudget() {
        Executor executor = request -> new Artifact(State.COMPLETE, request.phase().id(), List.of(), List.of());
        AnalysisPipeline pipeline = new AnalysisPipeline(List.of(
                phase("root", List.of("left", "right"), List.of(), List.of()),
                phase("left", List.of("shared"), List.of(), List.of()),
                phase("right", List.of("shared"), List.of(), List.of()),
                phase("shared", List.of(), List.of(), List.of())),
                Map.of("root", executor, "left", executor, "right", executor, "shared", executor));
        Result first = pipeline.evaluate("root", Map.of(), Map.of(), 4, false);
        assertEquals(List.of("shared", "left", "right", "root"), first.evaluated());
        Result second = pipeline.evaluate("root", Map.of(), Map.of(), 4, false);
        assertEquals(first.revision(), second.revision());
        assertEquals(List.of("shared", "left", "right", "root"), second.reused());
        assertThrows(IllegalArgumentException.class, () -> new AnalysisPipeline(
                List.of(phase("a", List.of("missing"), List.of(), List.of())), Map.of()));
    }
}
