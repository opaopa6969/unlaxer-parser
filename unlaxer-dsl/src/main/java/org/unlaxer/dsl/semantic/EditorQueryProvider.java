package org.unlaxer.dsl.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.unlaxer.source.DocumentSnapshot;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageQueries;
import org.unlaxer.source.LanguageQueries.*;
import org.unlaxer.source.LanguageRegions.Operation;
import org.unlaxer.source.LanguageRegions.State;
import org.unlaxer.source.SegmentSourceMap.Location;

/** Snapshot-bound partial editor completion, before the host layer applies unique source mapping. */
public final class EditorQueryProvider implements LanguageQueries.Provider {
    private final String project;
    private final long version;
    private final Function<Request, EditorParseResult<?>> parser;
    public EditorQueryProvider(String project, long version, Function<Request, EditorParseResult<?>> parser) {
        this.project = Objects.requireNonNull(project); this.version = version; this.parser = Objects.requireNonNull(parser);
        if (project.isEmpty() || version < 0) { throw new IllegalArgumentException("invalid editor project"); }
    }
    @Override public Set<Operation> capabilities() { return Set.of(Operation.COMPLETION); }
    @Override public Response query(Request request) {
        if (!request.project().id().equals(project) || request.project().version() != version) { throw new IllegalArgumentException("stale editor project"); }
        DocumentSnapshot snapshot = request.region().sourceMap().output();
        if (request.operation() != Operation.COMPLETION) { return new Response(snapshot,project,version,State.UNSUPPORTED,List.of()); }
        EditorParseResult<?> result = parser.apply(request);
        if (!result.uri().equals(snapshot.uri()) || result.version() != snapshot.version() || !result.source().equals(snapshot.text())) { throw new IllegalArgumentException("stale editor result"); }
        State state = State.valueOf(result.status().name());
        if (state == State.FAILED) { return new Response(snapshot,project,version,state,List.of()); }
        String prefix = request.parameters().getOrDefault("prefix", "");
        int start = request.cursor() - prefix.codePointCount(0,prefix.length());
        if (start < 0 || !snapshot.slice(new Span(start,request.cursor())).equals(prefix)) { throw new IllegalArgumentException("completion prefix is not original source"); }
        List<Item> items = new ArrayList<>();
        for (SemanticModel.Completion completion : result.completeAt(request.cursor(), request.region().id(), snapshot.version(), prefix)) {
            SemanticModel.Symbol symbol = completion.symbol();
            items.add(new Item(symbol.name(),symbol.type()+"; expected="+String.join(",",completion.expectedTypes()),
                List.of(new Location(snapshot,new Span(symbol.declaration().start(),symbol.declaration().end()))),
                List.of(new TextEdit(new Location(snapshot,new Span(start,request.cursor())),symbol.name()))));
        }
        return new Response(snapshot,project,version,state,items);
    }
}
