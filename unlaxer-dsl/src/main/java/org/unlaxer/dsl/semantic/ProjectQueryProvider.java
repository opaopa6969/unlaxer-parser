package org.unlaxer.dsl.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.unlaxer.dsl.semantic.ProjectSymbolIndex.*;
import org.unlaxer.dsl.semantic.ProjectSymbolIndex.Module;
import org.unlaxer.source.DocumentSnapshot;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageQueries;
import org.unlaxer.source.LanguageQueries.*;
import org.unlaxer.source.LanguageRegions.Operation;
import org.unlaxer.source.LanguageRegions.State;
import org.unlaxer.source.SegmentSourceMap.Location;

/** Query adapter for an existing immutable semantic project; never runs a compiler or user code. */
public final class ProjectQueryProvider implements LanguageQueries.Provider {
    private final ProjectSymbolIndex index;
    public ProjectQueryProvider(ProjectSymbolIndex index) { this.index = java.util.Objects.requireNonNull(index); }
    @Override public Set<Operation> capabilities() { return Set.of(Operation.COMPLETION, Operation.HOVER, Operation.DEFINITION); }
    @Override public Response query(Request request) {
        DocumentSnapshot snapshot = request.region().sourceMap().output();
        if (false == request.project().id().equals(index.project()) || request.project().version() != index.version()) {
            throw new IllegalArgumentException("stale semantic project");
        }
        Module module = index.modules().stream().filter(candidate -> candidate.model().uri().equals(snapshot.uri())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown semantic document"));
        if (false == snapshot(module).equals(snapshot)) { throw new IllegalArgumentException("stale semantic document"); }
        List<Item> items = new ArrayList<>();
        State state = State.COMPLETE;
        if (request.operation() == Operation.COMPLETION) {
            for (Completion completion : index.complete(module.id(), index.version(), snapshot.version(), request.cursor(),
                    request.parameters().getOrDefault("prefix", ""), request.parameters().getOrDefault("expectedType", SemanticModel.UNKNOWN))) {
                Definition definition = completion.candidate().definition();
                ProjectSymbolIndex.Edit edit = completion.edit();
                if (false == edit.uri().equals(snapshot.uri()) || edit.version() != snapshot.version()) {
                    throw new IllegalArgumentException("completion edit owns another snapshot");
                }
                items.add(new Item(completion.candidate().name(), completion.reason(), List.of(location(definition)),
                        List.of(new TextEdit(new Location(snapshot, new Span(edit.span().start(), edit.span().end())), edit.text()))));
            }
        } else if (request.operation() == Operation.HOVER || request.operation() == Operation.DEFINITION) {
            String name = request.parameters().getOrDefault("name", "");
            if (name.isEmpty()) { throw new IllegalArgumentException("query requires a token name"); }
            Binding binding = index.resolve(module.id(), index.version(), snapshot.version(), request.cursor(), name);
            if (false == binding.status().equals("RESOLVED")) { state = binding.candidates().isEmpty() ? State.FAILED : State.PARTIAL; }
            for (Candidate candidate : binding.candidates()) {
                items.add(new Item(candidate.name(), candidate.type(), List.of(location(candidate.definition())), List.of()));
            }
        } else { state = State.UNSUPPORTED; }
        return new Response(snapshot, index.project(), index.version(), state, items);
    }
    private static DocumentSnapshot snapshot(Module module) {
        SemanticModel model = module.model();
        return new DocumentSnapshot(model.uri(), model.version(), model.source());
    }
    private Location location(Definition definition) {
        List<Module> modules = index.modules();
        if (false == definition.identity().dependency().isEmpty()) {
            modules = index.dependencies().stream().filter(dependency -> dependency.id().equals(definition.identity().dependency()))
                    .findFirst().orElseThrow().modules();
        }
        Module owner = modules.stream().filter(module -> module.id().equals(definition.identity().module())).findFirst().orElseThrow();
        DocumentSnapshot snapshot = snapshot(owner);
        if (false == snapshot.uri().equals(definition.uri()) || snapshot.version() != definition.version()) {
            throw new IllegalArgumentException("stale definition snapshot");
        }
        return new Location(snapshot, new Span(definition.span().start(), definition.span().end()));
    }
}
