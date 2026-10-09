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
    public static List<SourceEdits.Plan> prepare(ProjectSymbolIndex index, Definition target, String replacement,
                                                Predicate<String> identifierValidator, List<Inventory> inventories) {
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
        for (Module module : index.modules()) {
            Inventory inventory = byModule.get(module.id());
            DocumentSnapshot expected = new DocumentSnapshot(module.model().uri(), module.model().version(), module.model().source());
            if (inventory == null || false == expected.equals(inventory.source.snapshot())) {
                throw new IllegalArgumentException("missing or stale source inventory");
            }
            for (Import imported : module.imports()) {
                if (imported.target().dependency().isEmpty() && imported.target().module().equals(identity.module())
                        && imported.symbol().equals(symbol.name())) {
                    throw new IllegalArgumentException("import source-name spans are unsupported");
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
}
