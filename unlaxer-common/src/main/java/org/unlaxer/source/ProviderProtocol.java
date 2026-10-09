package org.unlaxer.source;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageRegions.Operation;
import org.unlaxer.source.SegmentSourceMap.Location;

/** Version-one wire contract shared by native analysis processes and editor queries. */
public final class ProviderProtocol {
    private ProviderProtocol() {}
    public enum Status { OK, DIAGNOSTICS, UNAVAILABLE, UNSUPPORTED, TIMEOUT, FAILED }
    public record Identity(String id, String version) {}
    public record Request(String id, Identity provider, LanguageRegions.Language language, String region, DocumentSnapshot snapshot, LanguageQueries.Project project,
                          Operation operation, int cursor, Map<String, String> parameters, boolean executeUserCode) {
        public Request {
            snapshot.check(new Span(cursor, cursor)); parameters = Map.copyOf(parameters);
            if (region.isEmpty() || id.isEmpty() || provider.id.isEmpty() || provider.version.isEmpty()) { throw new IllegalArgumentException("empty provider identity"); }
            DocumentSnapshot known = project.documents().get(snapshot.uri());
            if (known != null && false == known.equals(snapshot)) { throw new IllegalArgumentException("project snapshot collision"); }
        }
    }
    public record Diagnostic(String code, String message, String severity, List<Location> locations) {
        public Diagnostic { locations = List.copyOf(locations); }
    }
    public record Response(Status status, Set<String> capabilities, List<Diagnostic> diagnostics, List<LanguageQueries.Item> items) {
        public Response { capabilities = Set.copyOf(capabilities); diagnostics = List.copyOf(diagnostics); items = List.copyOf(items); }
    }
    public record MappedDiagnostic(String code, String message, String severity, List<SegmentSourceMap.Mapping> locations) {
        public MappedDiagnostic { locations = List.copyOf(locations); }
    }
    public static List<MappedDiagnostic> mapDiagnostics(Response response, SegmentSourceMap sourceMap) {
        List<MappedDiagnostic> result = new ArrayList<>();
        for (Diagnostic diagnostic : response.diagnostics) {
            List<SegmentSourceMap.Mapping> locations = new ArrayList<>();
            for (Location location : diagnostic.locations) {
                if (location.snapshot().equals(sourceMap.output())) { locations.addAll(sourceMap.diagnostics(location.span())); }
                else {
                    if (location.snapshot().uri().equals(sourceMap.output().uri())) { throw new IllegalArgumentException("stale diagnostic snapshot"); }
                    locations.add(new SegmentSourceMap.Mapping(location, true));
                }
            }
            result.add(new MappedDiagnostic(diagnostic.code, diagnostic.message, diagnostic.severity, locations));
        }
        return List.copyOf(result);
    }
    public record Frame(String text, String fingerprint) {}
    public static Frame encode(Request request) {
        StringBuilder out = new StringBuilder("UNLAXER-PROVIDER\t1\n");
        out.append("request\t").append(hex(request.id)).append('\t').append(hex(request.provider.id)).append('\t').append(hex(request.provider.version))
            .append('\t').append(request.operation).append('\t').append(request.cursor).append('\t').append(request.executeUserCode).append('\n');
        out.append("project\t").append(hex(request.project.id())).append('\t').append(request.project.version()).append('\n');
        out.append("language\t").append(hex(request.language.id())).append('\t').append(hex(request.language.packageId())).append('\t')
            .append(hex(request.language.version())).append('\t').append(hex(request.language.grammar())).append('\t').append(hex(request.language.entry())).append('\t').append(hex(request.region)).append('\n');
        document(out, "snapshot", request.snapshot);
        request.project.documents().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> document(out, "document", entry.getValue()));
        request.project.configuration().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> pair(out, "config", entry));
        request.parameters.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> pair(out, "parameter", entry));
        if (out.length() > 512 * 1024) { throw new IllegalArgumentException("provider request exceeds 512 KiB"); }
        String fingerprint = hex(out.toString());
        out.append("end\n");
        return new Frame(out.toString(), fingerprint);
    }
    private static void document(StringBuilder out, String kind, DocumentSnapshot snapshot) {
        out.append(kind).append('\t').append(hex(snapshot.uri())).append('\t').append(snapshot.version()).append('\t').append(hex(snapshot.text())).append('\n');
    }
    private static void pair(StringBuilder out, String kind, Map.Entry<String, String> entry) {
        out.append(kind).append('\t').append(hex(entry.getKey())).append('\t').append(hex(entry.getValue())).append('\n');
    }
    public static String hex(String value) {
        if (value.codePoints().anyMatch(c -> c >= 0xd800 && c <= 0xdfff)) { throw new IllegalArgumentException("invalid Unicode field"); }
        return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String unhex(String value) {
        byte[] bytes = HexFormat.of().parseHex(value);
        String result = new String(bytes, StandardCharsets.UTF_8);
        if (false == java.util.Arrays.equals(bytes, result.getBytes(StandardCharsets.UTF_8))) { throw new IllegalArgumentException("invalid UTF-8"); }
        return result;
    }
    public static Response decode(Request request, Frame frame, String wire) {
        if (false == frame.equals(encode(request))) { throw new IllegalArgumentException("stale request frame"); }
        if (wire.length() > 4 * 1024 * 1024 || wire.codePoints().anyMatch(c -> c > 127) || false == wire.endsWith("\n")) { throw new IllegalArgumentException("invalid response framing"); }
        String[] lines = wire.split("\n", -1);
        if (lines.length < 4 || false == lines[0].equals("UNLAXER-PROVIDER\t1") || false == lines[lines.length - 2].equals("end")) {
            throw new IllegalArgumentException("invalid protocol/version");
        }
        String[] header = fields(lines[1], 8);
        if (false == header[0].equals("response") || false == unhex(header[1]).equals(request.id)
                || false == unhex(header[2]).equals(request.provider.id) || false == unhex(header[3]).equals(request.provider.version)
                || false == header[4].equals(frame.fingerprint) || false == unhex(header[5]).equals(request.project.id())
                || Long.parseLong(header[6]) != request.project.version()) { throw new IllegalArgumentException("stale or wrong provider response"); }
        Status status = Status.valueOf(header[7]);
        Set<String> capabilities = new java.util.HashSet<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<LanguageQueries.Item> items = new ArrayList<>();
        Map<Integer, List<Location>> locations = new HashMap<>();
        Map<Integer, List<LanguageQueries.TextEdit>> edits = new HashMap<>();
        for (int index = 2; index < lines.length - 2; index++) {
            String[] row = lines[index].split("\t", -1);
            switch (row[0]) {
                case "capability" -> { fields(lines[index], 2); if (false == capabilities.add(row[1])) { throw new IllegalArgumentException("duplicate capability"); } }
                case "diagnostic" -> {
                    fields(lines[index], 8);
                    diagnostics.add(new Diagnostic(unhex(row[1]), unhex(row[2]), row[3], List.of(location(request, row, 4))));
                }
                case "origin" -> {
                    fields(lines[index], 6); int diagnosticIndex = itemIndex(row[1], diagnostics.size());
                    Diagnostic diagnostic = diagnostics.get(diagnosticIndex);
                    var origins = new ArrayList<>(diagnostic.locations()); origins.add(location(request, row, 2));
                    diagnostics.set(diagnosticIndex, new Diagnostic(diagnostic.code(), diagnostic.message(), diagnostic.severity(), origins));
                }
                case "item" -> { fields(lines[index], 3); items.add(new LanguageQueries.Item(unhex(row[1]), unhex(row[2]), List.of(), List.of())); }
                case "location" -> {
                    fields(lines[index], 6); int item = itemIndex(row[1], items.size());
                    locations.computeIfAbsent(item, ignored -> new ArrayList<>()).add(location(request, row, 2));
                }
                case "edit" -> {
                    fields(lines[index], 7); int item = itemIndex(row[1], items.size());
                    edits.computeIfAbsent(item, ignored -> new ArrayList<>()).add(new LanguageQueries.TextEdit(location(request, row, 2), unhex(row[6])));
                }
                default -> throw new IllegalArgumentException("unknown response field");
            }
        }
        for (int index = 0; index < items.size(); index++) {
            LanguageQueries.Item item = items.get(index);
            items.set(index, new LanguageQueries.Item(item.label(), item.detail(), locations.getOrDefault(index, List.of()), edits.getOrDefault(index, List.of())));
        }
        if ((status == Status.OK && false == diagnostics.isEmpty()) || (status == Status.DIAGNOSTICS && diagnostics.isEmpty())
                || (status != Status.OK && status != Status.DIAGNOSTICS && (false == diagnostics.isEmpty() || false == items.isEmpty()))) {
            throw new IllegalArgumentException("response status disagrees with results");
        }
        return new Response(status, capabilities, diagnostics, items);
    }
    /** Server-side reader. Unknown and duplicate fields are errors, never ignored configuration. */
    public static Request readRequest(String wire) {
        if (wire.length() > 512 * 1024 + 4 || false == wire.endsWith("end\n") || wire.codePoints().anyMatch(c -> c > 127)) {
            throw new IllegalArgumentException("invalid request frame");
        }
        String[] lines = wire.split("\n", -1);
        if (lines.length < 7 || false == lines[0].equals("UNLAXER-PROVIDER\t1") || false == lines[lines.length - 2].equals("end")) { throw new IllegalArgumentException("invalid protocol/version"); }
        String[] request = fields(lines[1], 7);
        String[] project = fields(lines[2], 3);
        String[] language = fields(lines[3], 7);
        String[] snapshot = fields(lines[4], 4);
        if (false == request[0].equals("request") || false == project[0].equals("project") || false == language[0].equals("language") || false == snapshot[0].equals("snapshot")
                || false == Set.of("true", "false").contains(request[6])) { throw new IllegalArgumentException("invalid request header"); }
        Map<String, DocumentSnapshot> documents = new HashMap<>();
        Map<String, String> configuration = new HashMap<>();
        Map<String, String> parameters = new HashMap<>();
        for (int index = 5; index < lines.length - 2; index++) {
            String[] row = lines[index].split("\t", -1);
            switch (row[0]) {
                case "document" -> {
                    fields(lines[index], 4);
                    DocumentSnapshot document = new DocumentSnapshot(unhex(row[1]), Long.parseLong(row[2]), unhex(row[3]));
                    if (documents.put(document.uri(), document) != null) { throw new IllegalArgumentException("duplicate document"); }
                }
                case "config", "parameter" -> {
                    fields(lines[index], 3);
                    Map<String, String> values = row[0].equals("config") ? configuration : parameters;
                    if (values.put(unhex(row[1]), unhex(row[2])) != null) { throw new IllegalArgumentException("duplicate configuration"); }
                }
                default -> throw new IllegalArgumentException("unknown request field");
            }
        }
        return new Request(unhex(request[1]), new Identity(unhex(request[2]), unhex(request[3])),
            new LanguageRegions.Language(unhex(language[1]), unhex(language[2]), unhex(language[3]), unhex(language[4]), unhex(language[5])), unhex(language[6]),
            new DocumentSnapshot(unhex(snapshot[1]), Long.parseLong(snapshot[2]), unhex(snapshot[3])),
            new LanguageQueries.Project(unhex(project[1]), Long.parseLong(project[2]), documents, configuration),
            Operation.valueOf(request[4]), Integer.parseInt(request[5]), parameters, Boolean.parseBoolean(request[6]));
    }
    public static String reply(Request request, String requestWire, Response response) {
        String echo = hex(requestWire.substring(0, requestWire.length() - 4));
        StringBuilder out = new StringBuilder("UNLAXER-PROVIDER\t1\nresponse\t").append(hex(request.id)).append('\t')
            .append(hex(request.provider.id)).append('\t').append(hex(request.provider.version)).append('\t').append(echo)
            .append('\t').append(hex(request.project.id())).append('\t').append(request.project.version()).append('\t').append(response.status).append('\n');
        response.capabilities.stream().sorted().forEach(capability -> out.append("capability\t").append(capability).append('\n'));
        int diagnosticIndex = 0;
        for (Diagnostic diagnostic : response.diagnostics) {
            if (diagnostic.locations.isEmpty()) { throw new IllegalArgumentException("diagnostic requires an origin"); }
            boolean first = true;
            for (Location location : diagnostic.locations) {
                if (first) { out.append("diagnostic\t").append(hex(diagnostic.code)).append('\t').append(hex(diagnostic.message)).append('\t').append(diagnostic.severity).append('\t'); }
                else { out.append("origin\t").append(diagnosticIndex).append('\t'); }
                appendLocation(out, location); out.append('\n'); first = false;
            }
            diagnosticIndex++;
        }
        int index = 0;
        for (LanguageQueries.Item item : response.items) {
            out.append("item\t").append(hex(item.label())).append('\t').append(hex(item.detail())).append('\n');
            for (Location location : item.locations()) {
                out.append("location\t").append(index).append('\t'); appendLocation(out, location); out.append('\n');
            }
            for (LanguageQueries.TextEdit edit : item.edits()) {
                out.append("edit\t").append(index).append('\t'); appendLocation(out, edit.location()); out.append('\t').append(hex(edit.replacement())).append('\n');
            }
            index++;
        }
        return out.append("end\n").toString();
    }
    private static void appendLocation(StringBuilder out, Location location) {
        out.append(hex(location.snapshot().uri())).append('\t').append(location.snapshot().version()).append('\t').append(location.span().start()).append('\t').append(location.span().end());
    }
    private static int itemIndex(String field, int size) {
        int index = Integer.parseInt(field);
        if (index < 0 || index >= size) { throw new IllegalArgumentException("unknown item"); }
        return index;
    }
    private static String[] fields(String line, int count) {
        String[] fields = line.split("\t", -1);
        if (fields.length != count) { throw new IllegalArgumentException("wrong field count"); }
        return fields;
    }
    private static Location location(Request request, String[] row, int offset) {
        String uri = unhex(row[offset]); long version = Long.parseLong(row[offset + 1]);
        DocumentSnapshot snapshot = uri.equals(request.snapshot.uri()) ? request.snapshot : request.project.documents().get(uri);
        if (snapshot == null || snapshot.version() != version) { throw new IllegalArgumentException("unknown or stale diagnostic document"); }
        return new Location(snapshot, new Span(Integer.parseInt(row[offset + 2]), Integer.parseInt(row[offset + 3])));
    }
}
