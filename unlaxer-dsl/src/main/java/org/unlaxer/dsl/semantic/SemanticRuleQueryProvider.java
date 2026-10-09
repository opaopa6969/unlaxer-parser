package org.unlaxer.dsl.semantic;

import java.util.*;
import java.util.function.Function;
import org.unlaxer.editor.EditorCst;
import org.unlaxer.source.DocumentSnapshot;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageQueries;
import org.unlaxer.source.LanguageQueries.*;
import org.unlaxer.source.LanguageRegions.Language;
import org.unlaxer.source.LanguageRegions.Operation;
import org.unlaxer.source.LanguageRegions.State;
import org.unlaxer.source.SegmentSourceMap.Location;

/** Bind portable rules to an exact language/package/entry and snapshot-aware host query dispatch. */
public final class SemanticRuleQueryProvider implements LanguageQueries.Provider {
    private final SemanticRules.Program program;
    private final SemanticRules.Inventory inventory;
    private final Language language;
    private final String project;
    private final long version;
    private final Function<Request,EditorCst> parser;
    public SemanticRuleQueryProvider(SemanticRules.Program program,SemanticRules.Inventory inventory,Language language,String project,long version,Function<Request,EditorCst> parser) {
        this.program=Objects.requireNonNull(program);this.inventory=Objects.requireNonNull(inventory);this.language=Objects.requireNonNull(language);
        this.project=Objects.requireNonNull(project);this.version=version;this.parser=Objects.requireNonNull(parser);
        if(project.isEmpty()||version<0||!language.grammar().equals(inventory.grammar())||!inventory.nodes().containsKey(language.entry())||!program.inventory().equals(inventory))throw new IllegalArgumentException("invalid semantic provider binding");
    }
    @Override public Set<Operation> capabilities() {return Set.of(Operation.VALIDATE,Operation.COMPLETION);}
    /** Diagnostic collection can retain errors even when no complete semantic model exists. */
    @Override public DiagnosticResponse diagnostics(Request request) {
        if(!request.project().id().equals(project)||request.project().version()!=version||!request.region().language().equals(language))throw new IllegalArgumentException("stale semantic provider binding");
        if(request.operation()!=Operation.VALIDATE)return LanguageQueries.Provider.super.diagnostics(request);
        var response=query(request);
        var diagnostics=response.items().stream().map(item->new org.unlaxer.source.ProviderProtocol.Diagnostic(
            item.label(),item.label()+": "+item.detail()+" [analysis="+response.state().name()+"]","ERROR",item.locations())).toList();
        // FAILED describes the semantic model; PARTIAL describes a usable collection of its errors.
        var state=response.state()==State.FAILED&&!diagnostics.isEmpty()?State.PARTIAL:response.state();
        return new DiagnosticResponse(response.snapshot(),response.project(),response.projectVersion(),state,diagnostics);
    }
    @Override public Response query(Request request) {
        if(!request.project().id().equals(project)||request.project().version()!=version||!request.region().language().equals(language))throw new IllegalArgumentException("stale semantic provider binding");
        DocumentSnapshot snapshot=request.region().sourceMap().output();
        if(!capabilities().contains(request.operation()))return new Response(snapshot,project,version,State.UNSUPPORTED,List.of());
        EditorCst cst=parser.apply(request);
        if(!cst.source().equals(snapshot.text()))throw new IllegalArgumentException("stale semantic parser result");
        var analysis=SemanticRuleEngine.analyze(program,inventory,snapshot.uri(),snapshot.version(),cst);List<Item> items=new ArrayList<>();
        State state=State.valueOf(analysis.status().name());
        if(request.operation()==Operation.VALIDATE) {
            for(var diagnostic:analysis.diagnostics())items.add(new Item(diagnostic.code(),diagnostic.rule(),List.of(new Location(snapshot,new Span(diagnostic.span().start(),diagnostic.span().end()))),List.of()));
        }else {
            var query=SemanticRuleEngine.query(analysis,snapshot.uri(),snapshot.version(),request.cursor(),request.parameters().getOrDefault("prefix",""));
            if(query.isPresent()) {
                var value=query.get();String expected=String.join(",",value.expected().stream().map(TypeSystem.TypeRef::name).toList());
                for(var completion:value.completions()) {
                    var symbol=completion.value();items.add(new Item(symbol.name(),symbol.type().name()+"; expected="+expected+"; "+completion.decision().status()+":"+completion.decision().rule(),
                        List.of(new Location(snapshot,new Span(symbol.span().start(),symbol.span().end()))),
                        List.of(new TextEdit(new Location(snapshot,new Span(value.edit().start(),value.edit().end())),symbol.name()))));
                }
            }
        }
        return new Response(snapshot,project,version,state,items);
    }
}
