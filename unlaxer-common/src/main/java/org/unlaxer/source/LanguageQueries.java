package org.unlaxer.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageRegions.*;
import org.unlaxer.source.SegmentSourceMap.Location;
import org.unlaxer.source.SegmentSourceMap.Mapping;

/** Cursor/project-aware query forwarding with ownership-aware result mapping. */
public final class LanguageQueries {
    public record Project(String id, long version, Map<String, DocumentSnapshot> documents, Map<String, String> configuration) {
        public Project {
            if (Objects.requireNonNull(id).isEmpty() || version < 0) { throw new IllegalArgumentException("invalid project"); }
            documents = Map.copyOf(documents); configuration = Map.copyOf(configuration);
            for (Map.Entry<String, DocumentSnapshot> entry : documents.entrySet()) {
                if (false == entry.getKey().equals(entry.getValue().uri())) { throw new IllegalArgumentException("document key differs from URI"); }
            }
        }
    }
    public record Request(Region region, Operation operation, int cursor, Project project, Map<String, String> parameters) {
        public Request {
            region.sourceMap().output().check(new Span(cursor, cursor)); parameters = Map.copyOf(parameters);
        }
    }
    public record TextEdit(Location location, String replacement) {
        public TextEdit { Objects.requireNonNull(location); Objects.requireNonNull(replacement); }
    }
    public record Item(String label, String detail, List<Location> locations, List<TextEdit> edits) {
        public Item { Objects.requireNonNull(label); Objects.requireNonNull(detail); locations = List.copyOf(locations); edits = List.copyOf(edits); }
    }
    public record Response(DocumentSnapshot snapshot, String project, long projectVersion, State state, List<Item> items) {
        public Response {
            Objects.requireNonNull(snapshot); Objects.requireNonNull(project); Objects.requireNonNull(state); items = List.copyOf(items);
            if (state != State.COMPLETE && state != State.PARTIAL && items.stream().anyMatch(item -> false == item.edits().isEmpty())) {
                throw new IllegalArgumentException("failed response contains edits");
            }
        }
    }
    public interface Provider {
        Set<Operation> capabilities();
        Response query(Request request);
    }
    /** Completion items are alternatives; their edits are checked per item, never combined. */
    public record MappedItem(String label, String detail, List<Mapping> locations, List<Edit> edits) {
        public MappedItem { locations = List.copyOf(locations); edits = List.copyOf(edits); }
    }
    public record Result(String region, State state, List<MappedItem> items) {
        public Result { items = List.copyOf(items); }
    }
    private final LanguageRegions regions;
    private final Project project;
    private final Map<Language, Provider> providers;
    public LanguageQueries(LanguageRegions regions, Project project, Map<Language, Provider> providers) {
        this.regions = Objects.requireNonNull(regions); this.project = Objects.requireNonNull(project); this.providers = Map.copyOf(providers);
        if (false == regions.host().equals(project.documents().get(regions.host().uri()))) {
            throw new IllegalArgumentException("project host snapshot missing or stale");
        }
    }
    public DocumentSnapshot host() { return regions.host(); }
    public Project project() { return project; }
    public LanguageQueryView view(DocumentSnapshot currentHost, Project currentProject, int hostCursor,
                                  Operation operation, Map<String, String> parameters) {
        Result result = query(currentHost, currentProject, hostCursor, operation, parameters);
        Region region = regions.at(hostCursor);
        Provider provider = region == null ? null : providers.get(region.language());
        return new LanguageQueryView(currentHost, operation, hostCursor,
            provider == null ? Set.of() : provider.capabilities(), result);
    }
    public Result query(DocumentSnapshot currentHost, Project currentProject, int hostCursor,
                        Operation operation, Map<String, String> parameters) {
        if (false == regions.host().equals(currentHost) || false == project.equals(currentProject)) {
            throw new IllegalArgumentException("stale query context");
        }
        Region region = regions.at(hostCursor);
        if (region == null) { return new Result("", State.UNSUPPORTED, List.of()); }
        Provider provider = providers.get(region.language());
        if (provider == null) { return new Result(region.id(), State.UNAVAILABLE, List.of()); }
        if (false == provider.capabilities().contains(operation)) { return new Result(region.id(), State.UNSUPPORTED, List.of()); }
        OptionalInt cursor = region.sourceMap().cursor(new Location(currentHost, new Span(hostCursor, hostCursor)));
        if (cursor.isEmpty()) { return new Result(region.id(), State.UNSUPPORTED, List.of()); }
        Response response = provider.query(new Request(region, operation, cursor.getAsInt(), project, parameters));
        if (false == response.snapshot().equals(region.sourceMap().output()) || false == response.project().equals(project.id())
                || response.projectVersion() != project.version()) { throw new IllegalArgumentException("stale provider response"); }
        List<MappedItem> items = new ArrayList<>();
        for (Item item : response.items()) {
            List<Mapping> locations = new ArrayList<>();
            for (Location location : item.locations()) {
                if (location.snapshot().equals(region.sourceMap().output())) {
                    locations.addAll(region.sourceMap().diagnostics(location.span()));
                } else {
                    checkKnown(location.snapshot(), region);
                    locations.add(new Mapping(location, true));
                }
            }
            List<Edit> edits = new ArrayList<>();
            for (TextEdit edit : item.edits()) {
                Location location = edit.location();
                if (location.snapshot().equals(region.sourceMap().output())) { location = region.sourceMap().edit(location.span()); }
                else { checkKnown(location.snapshot(), region); }
                if (false == location.snapshot().equals(currentHost) || false == region.body().contains(location.span())) {
                    throw new IllegalArgumentException("query edits must stay in the current host body");
                }
                edits.add(new Edit(location.span(), edit.replacement()));
            }
            LanguageRegions.validateEdits(edits);
            items.add(new MappedItem(item.label(), item.detail(), locations, edits));
        }
        return new Result(region.id(), response.state(), items);
    }
    private void checkKnown(DocumentSnapshot snapshot, Region region) {
        if (snapshot.equals(regions.host())) { return; }
        if (snapshot.uri().equals(region.sourceMap().output().uri()) || false == snapshot.equals(project.documents().get(snapshot.uri()))) {
            throw new IllegalArgumentException("unknown or stale result document");
        }
    }
}
