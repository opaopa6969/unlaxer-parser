package org.unlaxer.source;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.Test;
import org.unlaxer.source.LanguageRegions.Operation;
import org.unlaxer.source.ProviderProtocol.*;

public class ProviderProtocolTest {
    private final Path fixtures = Path.of("../docs/fixtures/language-providers/protocol");
    @Test public void sharedMalformedProtocolCases() throws Exception {
        Request request = ProviderProtocol.readRequest(Files.readString(fixtures.resolve("request.wire")));
        Frame frame = ProviderProtocol.encode(request);
        for (String line : Files.readAllLines(fixtures.resolve("cases.tsv"))) {
            String[] row = line.split("\t"); String wire = Files.readString(fixtures.resolve(row[1]));
            boolean accepted;
            try { if (row[0].equals("request")) ProviderProtocol.readRequest(wire); else ProviderProtocol.decode(request, frame, wire); accepted = true; }
            catch (IllegalArgumentException e) { accepted = false; }
            assertEquals(row[1], Boolean.parseBoolean(row[2]), accepted);
        }
        assertThrows(IllegalArgumentException.class, () -> ProviderProtocol.decode(request, new Frame(frame.text(), "old"), Files.readString(fixtures.resolve("response-ok.wire"))));
    }
    @Test public void diagnosticProjectionRejectsStaleSnapshot() throws Exception {
        Request request = ProviderProtocol.readRequest(Files.readString(fixtures.resolve("request.wire")));
        var span = new DocumentSnapshot.Span(0, request.snapshot().length());
        var origin = new DocumentSnapshot("host", 7, request.snapshot().text());
        var map = new SegmentSourceMap(request.snapshot(), List.of(new SegmentSourceMap.Segment(span, SegmentSourceMap.Kind.COPY, new SegmentSourceMap.Location(origin, span))));
        for (var stale : List.of(new DocumentSnapshot(request.snapshot().uri(), 8, request.snapshot().text()), new DocumentSnapshot(request.snapshot().uri(), 7, "changed"))) {
            var diagnostic = new Diagnostic("E", "error", "ERROR", List.of(new SegmentSourceMap.Location(stale, new DocumentSnapshot.Span(0, 1))));
            var response = new Response(Status.DIAGNOSTICS, Set.of(), List.of(diagnostic), List.of());
            assertThrows(IllegalArgumentException.class, () -> ProviderProtocol.mapDiagnostics(response, map));
        }
    }
    @Test public void processFailureStatesAndNoExecutionGate() throws Exception {
        Request request = ProviderProtocol.readRequest(Files.readString(fixtures.resolve("request.wire")));
        for (String line : Files.readAllLines(fixtures.resolve("transport.tsv"))) {
            String[] row = line.split("\t");
            List<String> command = row[0].equals("missing") ? List.of("/unlaxer-provider-missing") : List.of("python3", fixtures.resolve("transport.py").toString(), row[0]);
            var process = new ProviderProcess(command, request.provider(), Set.of(Operation.HOVER), Duration.ofMillis(200));
            assertEquals(row[0], Status.valueOf(row[1]), process.invoke(request).status());
        }
        Path marker = Files.createTempFile("provider-execute", ".marker"); Files.delete(marker);
        var process = new ProviderProcess(List.of("python3", fixtures.resolve("transport.py").toString(), "marker", marker.toString()), request.provider(), Set.of(Operation.HOVER), Duration.ofSeconds(1));
        Request execution = new Request(request.id(), request.provider(), request.language(), request.region(), request.snapshot(), request.project(), request.operation(), request.cursor(), request.parameters(), true);
        assertEquals(Status.UNSUPPORTED, process.invoke(execution).status()); assertFalse(Files.exists(marker));
    }
}
