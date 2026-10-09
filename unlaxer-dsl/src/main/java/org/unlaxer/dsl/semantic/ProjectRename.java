package org.unlaxer.dsl.semantic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.unlaxer.dsl.semantic.ProjectSymbolIndex.*;
import org.unlaxer.dsl.semantic.ProjectSymbolIndex.Module;
import org.unlaxer.source.DocumentSnapshot;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageRegions.Edit;
import org.unlaxer.source.SourceEdits;

/** Conservative symbol-identity rename over an explicit, complete reference inventory. */
public final class ProjectRename {
    private ProjectRename() {}
    public record Inventory(String module, SourceEdits source, List<String> referenceTokens, boolean complete) {
        public Inventory { referenceTokens = List.copyOf(referenceTokens); }
    }
    /** Import index identifies the exact immutable Module.imports entry; empty aliasToken means no explicit alias. */
    public record ImportSite(String module, int importIndex, String sourceToken, String aliasToken) {}
    public static List<SourceEdits.Plan> prepare(ProjectSymbolIndex index, Definition target, String replacement,
                                                Predicate<String> identifierValidator, List<Inventory> inventories) {
        return prepare(index,target,replacement,identifierValidator,inventories,List.of());
    }
    public static List<SourceEdits.Plan> prepare(ProjectSymbolIndex index, Definition target, String replacement,
                                                Predicate<String> identifierValidator, List<Inventory> inventories, List<ImportSite> importSites) {
        if (identifierValidator == null || false == identifierValidator.test(replacement)) {
            throw new IllegalArgumentException("unsupported or invalid identifier");
        }
        Identity identity = target.identity();
        if (false == identity.project().equals(index.project()) || false == identity.dependency().isEmpty()
                || false == identity.dependencyVersion().isEmpty()) {
            throw new IllegalArgumentException("foreign or read-only symbol");
        }
        Module selected = index.modules().stream().filter(module -> module.id().equals(identity.module())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("missing target module"));
        SemanticModel.Symbol symbol = selected.model().symbols().get(identity.symbol());
        if (symbol == null || false == selected.model().uri().equals(target.uri()) || selected.model().version() != target.version()
                || false == symbol.declaration().equals(target.span())) { throw new IllegalArgumentException("stale target"); }
        Map<String, Inventory> byModule = new HashMap<>();
        for (Inventory inventory : inventories) {
            if (false == inventory.complete || byModule.put(inventory.module, inventory) != null) {
                throw new IllegalArgumentException("incomplete or duplicate reference inventory");
            }
        }
        Map<String,Map<Integer,ImportSite>> sites=validateSites(index,byModule,importSites);
        for (Module module : index.modules()) {
            Inventory inventory = byModule.get(module.id());
            DocumentSnapshot expected = new DocumentSnapshot(module.model().uri(), module.model().version(), module.model().source());
            if (inventory == null || false == expected.equals(inventory.source.snapshot())) {
                throw new IllegalArgumentException("missing or stale source inventory");
            }
            for (int i=0;i<module.imports().size();i++) {
                Import imported=module.imports().get(i);
                if (targets(imported,selected,symbol)) {
                    ImportSite site=sites.getOrDefault(module.id(),Map.of()).get(i);
                    if(site==null)throw new IllegalArgumentException("missing import source-name metadata");
                    if(site.aliasToken().isEmpty()&&!replacement.equals(symbol.name()))checkCapture(module,inventory,replacement,-1);
                    for(int j=0;j<module.imports().size();j++)if(j!=i&&module.imports().get(j).name().equals(imported.name()))
                        throw new IllegalArgumentException("ambiguous import rename metadata");
                }
            }
        }
        if (byModule.size() != index.modules().size()) { throw new IllegalArgumentException("unknown inventory module"); }
        if (false == replacement.equals(symbol.name())) {
            if (selected.imports().stream().anyMatch(imported -> imported.name().equals(replacement))) {
                throw new IllegalArgumentException("rename may capture an import");
            }
            for (SemanticModel.Symbol other : selected.model().symbols().values()) {
                if (other.name().equals(replacement)) { throw new IllegalArgumentException("rename may capture a symbol"); }
            }
        }
        List<SourceEdits.Plan> result = new ArrayList<>();
        for (Module module : index.modules()) {
            Inventory inventory = byModule.get(module.id());
            List<Edit> edits = new ArrayList<>();
            Set<Span> changed = new HashSet<>();
            if (module.id().equals(identity.module())) {
                Span declaration = new Span(symbol.declaration().start(), symbol.declaration().end());
                if (false == inventory.source.snapshot().slice(declaration).equals(symbol.name())) {
                    throw new IllegalArgumentException("declaration is not a source-name token");
                }
                edits.add(new Edit(declaration, replacement)); changed.add(declaration);
            }
            Set<Integer> preservedAliases=new HashSet<>();
            for(int i=0;i<module.imports().size();i++)if(targets(module.imports().get(i),selected,symbol)) {
                ImportSite site=sites.get(module.id()).get(i);
                Span sourceSpan=inventory.source.token(site.sourceToken()).span();
                if(changed.add(sourceSpan))edits.add(new Edit(sourceSpan,replacement));
                if(!site.aliasToken().isEmpty())preservedAliases.add(i);
            }
            Set<String> references = new HashSet<>();
            for (String tokenId : inventory.referenceTokens) {
                if (false == references.add(tokenId)) { throw new IllegalArgumentException("duplicate reference token"); }
                Span span = inventory.source.token(tokenId).span();
                String name = inventory.source.snapshot().slice(span);
                Binding binding = index.resolve(module.id(), index.version(), module.model().version(), span.start(), name);
                if (false == replacement.equals(symbol.name()) && module.id().equals(identity.module()) && name.equals(replacement)) {
                    throw new IllegalArgumentException("rename may capture an existing reference");
                }
                if (false == binding.status().equals("RESOLVED")) {
                    if (name.equals(symbol.name())) { throw new IllegalArgumentException("unresolved or ambiguous target reference"); }
                    continue;
                }
                if (false == binding.candidates().get(0).definition().identity().equals(identity)) { continue; }
                if(preservedAliases.contains(importBinding(module,span.start(),name)))continue;
                Binding collision = index.resolve(module.id(), index.version(), module.model().version(), span.start(), replacement);
                if (false == collision.diagnostics().isEmpty() || collision.candidates().stream()
                        .anyMatch(candidate -> false == candidate.definition().identity().equals(identity))) {
                    throw new IllegalArgumentException("rename may capture a reference");
                }
                if (changed.add(span)) { edits.add(new Edit(span, replacement)); }
            }
            if (false == edits.isEmpty()) {
                result.add(inventory.source.plan(SourceEdits.Operation.RENAME,
                        new Span(0, inventory.source.snapshot().length()), edits));
            }
        }
        return List.copyOf(result);
    }
    private static boolean targets(Import imported,Module selected,SemanticModel.Symbol symbol) {
        return imported.target().dependency().isEmpty()&&imported.target().module().equals(selected.id())
            &&imported.symbol().equals(symbol.name())&&selected.exports().contains(symbol.id());
    }
    private static Map<String,Map<Integer,ImportSite>> validateSites(ProjectSymbolIndex index,Map<String,Inventory> inventories,List<ImportSite> sites) {
        Map<String,Map<Integer,ImportSite>> result=new HashMap<>();Set<String> tokenKeys=new HashSet<>();
        for(ImportSite site:sites) {
            Module module=index.modules().stream().filter(m->m.id().equals(site.module())).findFirst().orElseThrow(()->new IllegalArgumentException("unknown import module"));
            Inventory inventory=inventories.get(site.module());
            if(inventory==null||site.importIndex()<0||site.importIndex()>=module.imports().size())throw new IllegalArgumentException("invalid import metadata");
            if(result.computeIfAbsent(site.module(),ignored->new HashMap<>()).put(site.importIndex(),site)!=null)throw new IllegalArgumentException("duplicate import metadata");
            Import imported=module.imports().get(site.importIndex());
            Span whole=new Span(imported.span().start(),imported.span().end());
            Span source=inventory.source.token(site.sourceToken()).span();
            if(!whole.contains(source)||!inventory.source.snapshot().slice(source).equals(imported.symbol())||!tokenKeys.add(site.module()+":"+site.sourceToken()))throw new IllegalArgumentException("invalid import source token");
            if(site.aliasToken().isEmpty()) {
                if(!imported.name().equals(imported.symbol()))throw new IllegalArgumentException("missing explicit alias token");
            } else {
                Span alias=inventory.source.token(site.aliasToken()).span();
                if(!whole.contains(alias)||!inventory.source.snapshot().slice(alias).equals(imported.name())||!tokenKeys.add(site.module()+":"+site.aliasToken()))throw new IllegalArgumentException("invalid import alias token");
            }
            if(inventory.referenceTokens.contains(site.sourceToken())||(!site.aliasToken().isEmpty()&&inventory.referenceTokens.contains(site.aliasToken())))throw new IllegalArgumentException("import token is also a reference");
        }
        return result;
    }
    private static void checkCapture(Module module,Inventory inventory,String replacement,int excludedImport) {
        if(module.model().symbols().values().stream().anyMatch(s->s.name().equals(replacement)))throw new IllegalArgumentException("rename may capture a symbol");
        for(int i=0;i<module.imports().size();i++)if(i!=excludedImport&&module.imports().get(i).name().equals(replacement))throw new IllegalArgumentException("rename may capture an import");
        for(String token:inventory.referenceTokens)if(inventory.source.snapshot().slice(inventory.source.token(token).span()).equals(replacement))throw new IllegalArgumentException("rename may capture an existing reference");
    }
    /** Rename an explicit local alias, preserving its imported source name and target declaration. */
    public static SourceEdits.Plan prepareAlias(ProjectSymbolIndex index,ImportSite target,String replacement,
            Predicate<String> identifierValidator,List<Inventory> inventories,List<ImportSite> importSites) {
        if(identifierValidator==null||!identifierValidator.test(replacement))throw new IllegalArgumentException("unsupported or invalid identifier");
        Map<String,Inventory> byModule=new HashMap<>();
        for(Inventory inventory:inventories)if(!inventory.complete||byModule.put(inventory.module,inventory)!=null)throw new IllegalArgumentException("incomplete or duplicate reference inventory");
        if(byModule.size()!=index.modules().size())throw new IllegalArgumentException("unknown inventory module");
        for(Module m:index.modules()) {
            Inventory inv=byModule.get(m.id());
            if(inv==null||!inv.source.snapshot().equals(new DocumentSnapshot(m.model().uri(),m.model().version(),m.model().source())))throw new IllegalArgumentException("missing or stale source inventory");
        }
        var sites=validateSites(index,byModule,importSites);
        if(!target.equals(sites.getOrDefault(target.module(),Map.of()).get(target.importIndex()))||target.aliasToken().isEmpty())throw new IllegalArgumentException("explicit alias metadata required");
        Module module=index.modules().stream().filter(m->m.id().equals(target.module())).findFirst().orElseThrow();
        Inventory inventory=byModule.get(module.id());Import imported=module.imports().get(target.importIndex());
        for(int i=0;i<module.imports().size();i++)if(i!=target.importIndex()&&module.imports().get(i).name().equals(imported.name()))throw new IllegalArgumentException("ambiguous alias identity");
        if(!replacement.equals(imported.name()))checkCapture(module,inventory,replacement,target.importIndex());
        List<Module> targets=index.modules();String dependencyVersion="";
        if(!imported.target().dependency().isEmpty()) {
            Dependency dependency=index.dependencies().stream().filter(d->d.id().equals(imported.target().dependency())).findFirst().orElseThrow(()->new IllegalArgumentException("unresolved alias target"));
            targets=dependency.modules();dependencyVersion=dependency.version();
        }
        Module owner=targets.stream().filter(m->m.id().equals(imported.target().module())).findFirst().orElseThrow(()->new IllegalArgumentException("unresolved alias target"));
        var candidates=owner.model().symbols().values().stream().filter(s->s.name().equals(imported.symbol())&&owner.exports().contains(s.id())).toList();
        if(candidates.size()!=1)throw new IllegalArgumentException("unresolved alias target");
        Identity identity=new Identity(index.project(),imported.target().dependency(),dependencyVersion,owner.id(),candidates.get(0).id());
        List<Edit> edits=new ArrayList<>();edits.add(new Edit(inventory.source.token(target.aliasToken()).span(),replacement));Set<String> seen=new HashSet<>();
        for(String token:inventory.referenceTokens) {
            if(!seen.add(token))throw new IllegalArgumentException("duplicate reference token");
            Span span=inventory.source.token(token).span();String name=inventory.source.snapshot().slice(span);
            if(!name.equals(imported.name()))continue;
            Binding resolved=index.resolve(module.id(),index.version(),module.model().version(),span.start(),name);
            if(!resolved.status().equals("RESOLVED"))throw new IllegalArgumentException("unresolved or ambiguous alias reference");
            if(resolved.candidates().get(0).definition().identity().equals(identity)&&importBinding(module,span.start(),name)==target.importIndex())edits.add(new Edit(span,replacement));
        }
        return inventory.source.plan(SourceEdits.Operation.RENAME,new Span(0,inventory.source.snapshot().length()),edits);
    }

    private static int importBinding(Module module,int cursor,String name) {
        var scopes=module.model().scopes();int length=module.model().source().codePointCount(0,module.model().source().length());
        var scope=scopes.values().stream().filter(s->s.parent()==null).findFirst().orElseThrow();
        while(true) {
            String parent=scope.id();var child=scopes.values().stream().filter(s->parent.equals(s.parent())&&s.span().start()<=cursor&&(cursor<s.span().end()||cursor==length&&s.span().end()==length)).findFirst();
            if(child.isEmpty())break;scope=child.get();
        }
        while(scope!=null) {
            String id=scope.id();
            if(module.model().symbols().values().stream().anyMatch(s->s.scope().equals(id)&&s.name().equals(name)&&s.visibleFrom()<=cursor))return -1;
            for(int i=0;i<module.imports().size();i++) {Import imported=module.imports().get(i);if(imported.scope().equals(id)&&imported.name().equals(name)&&imported.visibleFrom()<=cursor)return i;}
            scope=scope.parent()==null?null:scopes.get(scope.parent());
        }
        return -1;
    }

}
