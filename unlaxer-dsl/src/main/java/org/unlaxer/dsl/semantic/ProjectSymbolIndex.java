package org.unlaxer.dsl.semantic;

import java.util.*;
import org.unlaxer.dsl.semantic.SemanticModel.*;

/** Immutable, offline project/dependency snapshot. Grammar imports are unrelated to these imports. */
public final class ProjectSymbolIndex {
    public record ModuleRef(String dependency, String module) {}
    public record Import(String name, ModuleRef target, String symbol, String scope, Span span, int visibleFrom) {}
    public record Module(String id, SemanticModel model, Set<String> exports, List<Import> imports) {
        public Module { exports = Set.copyOf(exports); imports = List.copyOf(imports); }
    }
    /** A dependency is an already resolved, locked snapshot; this API never fetches it. */
    public record Dependency(String id, String version, String sha256, List<Module> modules) {
        public Dependency { modules = List.copyOf(modules); }
    }
    public record Identity(String project, String dependency, String dependencyVersion, String module, String symbol) {}
    public record Definition(Identity identity, String uri, long version, Span span) {}
    public record Candidate(String name, String type, Definition definition) {}
    public record Diagnostic(String code, String uri, Span span) {}
    public record Binding(String name, List<Candidate> candidates, List<Diagnostic> diagnostics) {
        public Binding { candidates = List.copyOf(candidates); diagnostics = List.copyOf(diagnostics); }
        public String status() {
            return candidates.size() > 1 || diagnostics.stream().anyMatch(d -> d.code().equals("AMBIGUOUS_IMPORT")) ? "AMBIGUOUS"
                    : !diagnostics.isEmpty() || candidates.isEmpty() ? "UNRESOLVED" : "RESOLVED";
        }
    }
    public record Edit(String uri, long version, Span span, String text) {}
    public record Completion(Candidate candidate, Compatibility compatibility, String reason, Edit edit) {}
    public static final class ProjectException extends IllegalArgumentException {
        private final Diagnostic diagnostic;
        private ProjectException(Diagnostic diagnostic) { super(diagnostic.toString()); this.diagnostic = diagnostic; }
        public Diagnostic diagnostic() { return diagnostic; }
    }
    private record Key(String dependency, String module) {}
    private final String project;
    private final long version;
    private final List<Module> projectModules;
    private final List<Dependency> dependencies;
    private final Map<Key, Module> modules = new LinkedHashMap<>();
    private final Map<String, Dependency> libraries = new LinkedHashMap<>();
    private final Map<String, Type> types = new LinkedHashMap<>();

    public ProjectSymbolIndex(String project, long version, List<Module> modules, List<Dependency> dependencies) {
        if (project == null || project.isEmpty() || version < 0) throw error("INVALID_PROJECT", "", new Span(0, 0));
        this.project = project; this.version = version;
        this.projectModules = List.copyOf(modules); this.dependencies = List.copyOf(dependencies);
        for (Module module : modules) add("", module);
        for (Dependency dependency : dependencies) {
            if (dependency.id() == null || dependency.id().isEmpty() || dependency.version() == null
                    || !dependency.version().matches("[0-9][A-Za-z0-9._+-]*")
                    || dependency.sha256() == null || !dependency.sha256().matches("[0-9a-f]{64}"))
                throw error("INVALID_DEPENDENCY_LOCK", "", new Span(0, 0));
            if (libraries.putIfAbsent(dependency.id(), dependency) != null)
                throw error("DUPLICATE_DEPENDENCY", "", new Span(0, 0));
            for (Module module : dependency.modules()) add(dependency.id(), module);
        }
    }
    private static ProjectException error(String code, String uri, Span span) {
        return new ProjectException(new Diagnostic(code, uri, span));
    }
    private static int compareText(String a, String b) { return Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray()); }
    private void add(String dependency, Module module) {
        SemanticModel model = module.model();
        if (module.id() == null || module.id().isEmpty()) throw error("EMPTY_MODULE_ID", model.uri(), new Span(0, 0));
        if (modules.putIfAbsent(new Key(dependency, module.id()), module) != null)
            throw error("DUPLICATE_MODULE", model.uri(), new Span(0, 0));
        if (modules.entrySet().stream().anyMatch(entry -> entry.getKey().dependency().equals(dependency)
                && entry.getValue() != module && entry.getValue().model().uri().equals(model.uri())))
            throw error("DUPLICATE_DOCUMENT", model.uri(), new Span(0, 0));
        for (String id : module.exports()) {
            Symbol symbol = model.symbols().get(id);
            if (symbol == null) throw error("UNDEFINED_EXPORT", model.uri(), new Span(0, 0));
            if (model.scopes().get(symbol.scope()).parent() != null)
                throw error("NON_ROOT_EXPORT", model.uri(), symbol.declaration());
        }
        for (Import imported : module.imports()) {
            Scope scope = model.scopes().get(imported.scope());
            if (imported.name() == null || imported.name().isEmpty() || imported.symbol() == null || imported.symbol().isEmpty()
                    || imported.target() == null || imported.target().dependency() == null
                    || imported.target().module() == null || imported.target().module().isEmpty())
                throw error("INVALID_IMPORT", model.uri(), imported.span());
            if (scope == null || !scope.span().contains(imported.span()) || imported.visibleFrom() < scope.span().start()
                    || imported.visibleFrom() > scope.span().end())
                throw error("IMPORT_OUTSIDE_SCOPE", model.uri(), imported.span());
        }
        for (Type type : model.types().values()) {
            Type previous = types.putIfAbsent(type.id(), type);
            if (previous != null && !shape(previous).equals(shape(type)))
                throw error("CONFLICTING_TYPE", model.uri(), type.span());
        }
    }
    private static List<Object> shape(Type type) {
        return List.of(type.kind(), type.supertypes(), type.fields().stream().map(field -> List.of(field.name(), field.type())).toList());
    }
    public String project() { return project; }
    public long version() { return version; }
    public List<Module> modules() { return projectModules; }
    public List<Dependency> dependencies() { return dependencies; }
    /** Full rebuild is the correctness baseline; query caching is a separate layer. */
    public ProjectSymbolIndex withModules(long nextVersion, List<Module> replacement) {
        if (nextVersion <= version) throw error("NON_INCREASING_VERSION", "", new Span(0, 0));
        for (Module next : replacement) {
            for (Module previous : projectModules) {
                if (!previous.model().uri().equals(next.model().uri())) continue;
                if (next.model().version() < previous.model().version()
                        || !next.model().source().equals(previous.model().source()) && next.model().version() == previous.model().version())
                    throw error("STALE_DOCUMENT", next.model().uri(), new Span(0, 0));
            }
        }
        return new ProjectSymbolIndex(project, nextVersion, replacement, dependencies);
    }
    private Module queryModule(String module, long projectVersion, long documentVersion, int cursor) {
        Module found = modules.get(new Key("", module));
        if (found == null) throw error("UNDEFINED_MODULE", "", new Span(0, 0));
        if (projectVersion != version || documentVersion != found.model().version())
            throw error("STALE_SNAPSHOT", found.model().uri(), new Span(0, 0));
        int length = found.model().source().codePointCount(0, found.model().source().length());
        if (cursor < 0 || cursor > length) throw error("INVALID_CURSOR", found.model().uri(), new Span(Math.max(0, cursor), Math.max(0, cursor)));
        return found;
    }
    private Candidate candidate(Key key, Module module, Symbol symbol, String name) {
        String dependencyVersion = key.dependency().isEmpty() ? "" : libraries.get(key.dependency()).version();
        return new Candidate(name, symbol.type(), new Definition(new Identity(project, key.dependency(), dependencyVersion,
                module.id(), symbol.id()), module.model().uri(), module.model().version(), symbol.declaration()));
    }
    private Binding imported(Key owner, Module module, Import imported) {
        String dependency = imported.target().dependency().isEmpty() ? owner.dependency() : imported.target().dependency();
        Key key = new Key(dependency, imported.target().module());
        Module target = modules.get(key);
        String failure = "UNRESOLVED_IMPORT";
        if (target != null) {
            for (Symbol symbol : target.model().symbols().values()) {
                if (!symbol.name().equals(imported.symbol()) || target.model().scopes().get(symbol.scope()).parent() != null) continue;
                if (target.exports().contains(symbol.id())) return new Binding(imported.name(), List.of(candidate(key, target, symbol, imported.name())), List.of());
                failure = "PRIVATE_SYMBOL";
            }
        }
        return new Binding(imported.name(), List.of(), List.of(new Diagnostic(failure, module.model().uri(), imported.span())));
    }
    public List<Diagnostic> diagnostics() {
        List<Diagnostic> result = new ArrayList<>();
        for (var entry : modules.entrySet()) {
            Set<List<String>> aliases = new HashSet<>();
            for (Import imported : entry.getValue().imports()) {
                result.addAll(imported(entry.getKey(), entry.getValue(), imported).diagnostics());
                if (!aliases.add(List.of(imported.scope(), imported.name())))
                    result.add(new Diagnostic("AMBIGUOUS_IMPORT", entry.getValue().model().uri(), imported.span()));
            }
        }
        result.sort(Comparator.comparing(Diagnostic::uri, ProjectSymbolIndex::compareText)
                .thenComparingInt(d -> d.span().start()).thenComparingInt(d -> d.span().end()).thenComparing(Diagnostic::code));
        return List.copyOf(result);
    }
    public List<Binding> visible(String moduleId, long projectVersion, long documentVersion, int cursor) {
        Module module = queryModule(moduleId, projectVersion, documentVersion, cursor);
        SemanticModel model = module.model();
        int length = model.source().codePointCount(0, model.source().length());
        Scope scope = model.scopes().values().stream().filter(s -> s.parent() == null).findFirst().orElseThrow();
        while (true) {
            String parent = scope.id();
            Optional<Scope> child = model.scopes().values().stream().filter(s -> parent.equals(s.parent()) && s.span().start() <= cursor
                    && (cursor < s.span().end() || cursor == length && s.span().end() == length)).findFirst();
            if (child.isEmpty()) break;
            scope = child.get();
        }
        Map<String, Binding> visible = new TreeMap<>(ProjectSymbolIndex::compareText);
        while (scope != null) {
            Map<String, Binding> local = new TreeMap<>(ProjectSymbolIndex::compareText);
            for (Import imported : module.imports()) {
                if (!imported.scope().equals(scope.id()) || imported.visibleFrom() > cursor) continue;
                Binding binding = imported(new Key("", moduleId), module, imported);
                Binding previous = local.get(imported.name());
                if (previous != null) {
                    List<Candidate> candidates = new ArrayList<>(previous.candidates()); candidates.addAll(binding.candidates());
                    List<Diagnostic> diagnostics = new ArrayList<>(previous.diagnostics()); diagnostics.addAll(binding.diagnostics());
                    diagnostics.add(new Diagnostic("AMBIGUOUS_IMPORT", model.uri(), imported.span()));
                    binding = new Binding(imported.name(), candidates, diagnostics);
                }
                local.put(imported.name(), binding);
            }
            for (Symbol symbol : model.symbols().values()) {
                if (symbol.scope().equals(scope.id()) && symbol.visibleFrom() <= cursor)
                    local.put(symbol.name(), new Binding(symbol.name(), List.of(candidate(new Key("", moduleId), module, symbol, symbol.name())), List.of()));
            }
            local.forEach(visible::putIfAbsent);
            scope = scope.parent() == null ? null : model.scopes().get(scope.parent());
        }
        return List.copyOf(visible.values());
    }
    public Binding resolve(String module, long projectVersion, long documentVersion, int cursor, String name) {
        return visible(module, projectVersion, documentVersion, cursor).stream().filter(binding -> binding.name().equals(name)).findFirst()
                .orElse(new Binding(name, List.of(), List.of()));
    }
    public Compatibility isAssignable(String actual, String expected) {
        if ((!actual.equals(SemanticModel.UNKNOWN) && !types.containsKey(actual)) || (!expected.equals(SemanticModel.UNKNOWN) && !types.containsKey(expected)))
            throw error("UNDEFINED_TYPE", "", new Span(0, 0));
        if (actual.equals(SemanticModel.UNKNOWN) || expected.equals(SemanticModel.UNKNOWN)) return Compatibility.UNKNOWN;
        Set<String> seen = new HashSet<>(); ArrayDeque<String> queue = new ArrayDeque<>(); queue.add(actual);
        while (!queue.isEmpty()) {
            String type = queue.removeFirst();
            if (type.equals(expected)) return Compatibility.YES;
            if (seen.add(type)) queue.addAll(types.get(type).supertypes());
        }
        return Compatibility.NO;
    }
    public List<Completion> complete(String moduleId, long projectVersion, long documentVersion, int cursor, String prefix, String expected) {
        Module module = queryModule(moduleId, projectVersion, documentVersion, cursor);
        isAssignable(SemanticModel.UNKNOWN, expected);
        int count = prefix.codePointCount(0, prefix.length());
        String source = module.model().source();
        if (count > cursor || !source.substring(source.offsetByCodePoints(0, cursor - count), source.offsetByCodePoints(0, cursor)).equals(prefix))
            throw error("PREFIX_MISMATCH", module.model().uri(), new Span(cursor, cursor));
        List<Completion> result = new ArrayList<>();
        for (Binding binding : visible(moduleId, projectVersion, documentVersion, cursor)) {
            if (!binding.status().equals("RESOLVED") || !binding.name().startsWith(prefix)) continue;
            Candidate candidate = binding.candidates().get(0);
            Compatibility compatibility = isAssignable(candidate.type(), expected);
            if (compatibility == Compatibility.NO) continue;
            result.add(new Completion(candidate, compatibility, candidate.type() + " -> " + expected + ": " + compatibility,
                    new Edit(module.model().uri(), documentVersion, new Span(cursor - count, cursor), candidate.name())));
        }
        result.sort(Comparator.comparing(Completion::compatibility).thenComparing(c -> c.candidate().name(), ProjectSymbolIndex::compareText));
        return List.copyOf(result);
    }
}
