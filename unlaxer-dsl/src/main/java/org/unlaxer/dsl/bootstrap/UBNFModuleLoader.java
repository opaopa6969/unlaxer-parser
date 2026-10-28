package org.unlaxer.dsl.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.runtime.LexicalExpression;
import org.unlaxer.dsl.runtime.LexicalExpression.Op;

/**
 * File-based lexical modules. Imported token programs are closed over their defining module;
 * no host classes, settings, rules, or caller capture bindings are inherited.
 * The old parseWithImports API remains separate.
 */
public final class UBNFModuleLoader {
    @FunctionalInterface public interface SourceReader {
        String read(Path path) throws IOException;
        /** Trusted injection stays compatible; disk implementations override with a bounded reader. */
        default String readPackage(Path path) throws IOException { return read(path); }
    }
    private final SourceReader reader;
    private final UBNFPackageResolver packages;
    private final Map<Path, Map<String, LexicalExpression>> modules = new LinkedHashMap<>();
    private final Set<Path> stack = new java.util.LinkedHashSet<>();
    private final UBNFSourceSnapshot.Builder origins;

    private UBNFModuleLoader(UBNFPackageResolver packages, UBNFSourceSnapshot.Builder origins) {
        this.packages = packages;
        this.reader = packages::read;
        this.origins = origins;
    }

    private <T> T origin(T node, Object from) {
        return origins == null ? node : origins.copy(node, from);
    }

    public static UBNFFile load(Path path) throws IOException {
        return resolve(UBNFMapper.parse(Files.readString(path)), path, UBNFPackageResolver.diskReader());
    }

    public static UBNFFile resolve(UBNFFile file, Path path, SourceReader reader) throws IOException {
        return resolve(file, path, new UBNFPackageResolver(path, reader));
    }

    /** CLI diagnostics retain the root source location even when linking replaces declarations. */
    public static UBNFFile resolve(UBNFSourceSnapshot snapshot, Path path, SourceReader reader) throws IOException {
        return resolve(snapshot.ast(), path, new UBNFPackageResolver(path, reader), snapshot);
    }

    /** Retain root node identities through linking, without assigning origins to imported tokens. */
    public static UBNFSourceSnapshot resolveWithSource(UBNFSourceSnapshot snapshot, Path path,
            SourceReader reader) throws IOException {
        var origins = new UBNFSourceSnapshot.Builder(snapshot);
        var file = resolve(snapshot.ast(), path, new UBNFPackageResolver(path, reader), snapshot, origins);
        origins.copy(file, snapshot.ast());
        return new UBNFSourceSnapshot(file, origins);
    }

    /** Explicit root selection; imported modules still export declarative tokens only. */
    public static UBNFFile resolve(UBNFFile file, Path path, UBNFPackageResolver packages) throws IOException {
        return resolve(file, path, packages, null);
    }

    private static UBNFFile resolve(UBNFFile file, Path path, UBNFPackageResolver packages,
            UBNFSourceSnapshot snapshot) throws IOException {
        return resolve(file, path, packages, snapshot, null);
    }

    private static UBNFFile resolve(UBNFFile file, Path path, UBNFPackageResolver packages,
            UBNFSourceSnapshot snapshot, UBNFSourceSnapshot.Builder origins) throws IOException {
        var loader = new UBNFModuleLoader(packages, origins);
        path = path.toAbsolutePath().normalize();
        loader.stack.add(path);
        var grammars = new ArrayList<GrammarDecl>();
        for (var grammar : file.grammars()) {
            String location = snapshot == null ? "" : snapshot.spanOf(grammar)
                .map(span -> " [" + span.start() + "," + span.end() + ")").orElse("");
            try {
                grammars.add(loader.link(grammar, path));
            } catch (IllegalArgumentException error) {
                if (file.grammars().size() == 1 || snapshot == null) throw error;
                throw new IllegalArgumentException("grammar " + grammar.name() + location + ": " + error.getMessage(), error);
            } catch (IOException error) {
                if (file.grammars().size() == 1 || snapshot == null) throw error;
                throw new IOException("grammar " + grammar.name() + location + ": " + error.getMessage(), error);
            }
        }
        return new UBNFFile(grammars);
    }

    private static IllegalArgumentException error(String message) {
        return new IllegalArgumentException("E-MODULE: " + message);
    }

    private Map<String, LexicalExpression> module(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        if (stack.contains(path)) throw error("cyclic import: " + path);
        if (modules.containsKey(path)) return modules.get(path);
        if (stack.size() >= 64) throw error("import depth exceeds 64");
        stack.add(path);
        try {
            var file = UBNFMapper.parse(reader.read(path));
            if (file.grammars().size() != 1) throw error("import requires exactly one grammar: " + path);
            var grammar = file.grammars().get(0);
            if (!grammar.rules().isEmpty() || grammar.tokens().stream().anyMatch(t -> !(t instanceof TokenDecl.Declarative)))
                throw error("only declarative token modules can be imported: " + path);
            if (!UBNFFeatures.validate(grammar).isEmpty()) throw error("invalid module features: " + path);
            var exports = LexicalCompiler.compile(link(grammar, path));
            modules.put(path, exports);
            return exports;
        } finally { stack.remove(path); }
    }

    private GrammarDecl link(GrammarDecl grammar, Path path) throws IOException {
        if (grammar.imports().isEmpty()) return grammar;
        var imported = new LinkedHashMap<String, LexicalExpression>();
        var aliases = new HashSet<String>();
        for (var declaration : grammar.imports()) {
            if (!aliases.add(declaration.alias())) throw error("duplicate import alias: " + declaration.alias());
            if (declaration.path().contains("://")) throw error("network imports are unsupported: " + declaration.path());
            var exports = module(packages.importPath(path, declaration.path()));
            for (var entry : exports.entrySet())
                imported.put(declaration.alias() + "." + entry.getKey(), entry.getValue());
        }
        var tokens = new ArrayList<TokenDecl>();
        for (var token : grammar.tokens()) {
            tokens.add(token instanceof TokenDecl.Declarative lexical
                ? origin(new TokenDecl.Declarative(token.name(), externalRefs(lexical.expression(), imported)), token) : token);
        }
        var used = new HashSet<String>();
        grammar.tokens().forEach(t -> used.add(t.name()));
        grammar.rules().forEach(r -> used.add(r.name()));
        var synthetic = new LinkedHashMap<String, String>();
        var settings = new ArrayList<GlobalSetting>();
        for (var setting : grammar.settings()) {
            settings.add(setting.key().equals("whitespace") && setting.value() instanceof StringSettingValue value
                ? origin(new GlobalSetting(setting.key(), origin(new StringSettingValue(
                    policy(value.value(), imported, tokens, used, synthetic)), value)), setting) : setting);
        }
        var rules = new ArrayList<RuleDecl>();
        for (var rule : grammar.rules()) {
            var annotations = new ArrayList<Annotation>();
            for (var annotation : rule.annotations()) {
                if (annotation instanceof LexicalContextAnnotation value) {
                    annotations.add(origin(new LexicalContextAnnotation(value.tokens().stream().map(name -> policy(name, imported, tokens, used, synthetic)).toList(), value.literals()), annotation));
                } else annotations.add(annotation instanceof WhitespaceAnnotation value
                    && value.style().isPresent() ? origin(new WhitespaceAnnotation(java.util.Optional.of(policy(value.style().get(), imported, tokens, used, synthetic))), annotation) : annotation);
            }
            rules.add(origin(new RuleDecl(annotations, rule.name(), body(rule.body(), imported, tokens, used, synthetic)), rule));
        }
        return origin(new GrammarDecl(grammar.name(), List.of(), settings, tokens, rules), grammar);
    }

    private String policy(String style, Map<String, LexicalExpression> imported, List<TokenDecl> tokens,
            Set<String> used, Map<String, String> synthetic) {
        if (!style.contains(".")) return style;
        int dot = style.indexOf('.');
        RuleRefElement resolved = (RuleRefElement) atom(new RuleRefElement(java.util.Optional.of(style.substring(0, dot)),
            style.substring(dot + 1)), imported, tokens, used, synthetic);
        return resolved.name();
    }

    private LexicalExpression externalRefs(LexicalExpression expression, Map<String, LexicalExpression> imported) {
        if (expression.op() == Op.REF && expression.text().contains(".")) {
            var target = imported.get(expression.text());
            if (target == null) throw error("undefined imported token: " + expression.text());
            return target; // already compiled inside its own module and wrapped in SCOPE
        }
        return new LexicalExpression(expression.op(), expression.text(), expression.min(), expression.max(),
            expression.children().stream().map(child -> externalRefs(child, imported)).toList());
    }

    private RuleBody body(RuleBody value, Map<String, LexicalExpression> imported, List<TokenDecl> tokens,
            Set<String> used, Map<String, String> synthetic) {
        if (value instanceof ChoiceBody choice) return origin(new ChoiceBody(choice.alternatives().stream()
            .map(s -> (SequenceBody) body(s, imported, tokens, used, synthetic)).toList()), value);
        return origin(new SequenceBody(((SequenceBody) value).elements().stream().map(item ->
            origin(new AnnotatedElement(atom(item.element(), imported, tokens, used, synthetic),
                item.captureName(), item.typeofConstraint()), item)).toList()), value);
    }

    private AtomicElement atom(AtomicElement value, Map<String, LexicalExpression> imported, List<TokenDecl> tokens,
            Set<String> used, Map<String, String> synthetic) {
        return origin(linkAtom(value, imported, tokens, used, synthetic), value);
    }

    private AtomicElement linkAtom(AtomicElement value, Map<String, LexicalExpression> imported, List<TokenDecl> tokens,
            Set<String> used, Map<String, String> synthetic) {
        if (value instanceof RuleRefElement ref && ref.namespace().isPresent()) {
            String qualified = ref.namespace().get() + "." + ref.name();
            var target = imported.get(qualified);
            if (target == null) throw error("undefined imported token: " + qualified);
            String name = synthetic.get(qualified);
            if (name == null) {
                int index = synthetic.size();
                do { name = "ImportedLexical" + index++; } while (used.contains(name));
                used.add(name);
                synthetic.put(qualified, name);
                tokens.add(new TokenDecl.Declarative(name, target));
            }
            return new RuleRefElement(name);
        }
        if (value instanceof UBNFAST.OwnedCaptureElement capture)
            return new UBNFAST.OwnedCaptureElement(capture.name(), body(capture.body(), imported, tokens, used, synthetic));
        if (value instanceof UBNFAST.CaptureScopeElement scope)
            return new UBNFAST.CaptureScopeElement(body(scope.body(), imported, tokens, used, synthetic));
        if (value instanceof UBNFAST.CommitElement commit)
            return new UBNFAST.CommitElement(body(commit.body(), imported, tokens, used, synthetic), commit.message());
        if (value instanceof UBNFAST.LabelElement label)
            return new UBNFAST.LabelElement(label.label(), body(label.body(), imported, tokens, used, synthetic));
        if (value instanceof UBNFAST.CatchElement caught)
            return new UBNFAST.CatchElement(
                origin(new GroupElement(body(caught.child().body(), imported, tokens, used, synthetic)), caught.child()),
                origin(new GroupElement(body(caught.alternative().body(), imported, tokens, used, synthetic)), caught.alternative()),
                caught.labels());
        if (value instanceof GroupElement group)
            return new GroupElement(body(group.body(), imported, tokens, used, synthetic));
        if (value instanceof OptionalElement optional)
            return new OptionalElement(body(optional.body(), imported, tokens, used, synthetic));
        if (value instanceof RepeatElement repeat)
            return new RepeatElement(body(repeat.body(), imported, tokens, used, synthetic));
        if (value instanceof OneOrMoreElement repeat)
            return new OneOrMoreElement(atom(repeat.body(), imported, tokens, used, synthetic));
        if (value instanceof BoundedRepeatElement repeat)
            return BoundedQuantifiers.copy(new BoundedRepeatElement(atom(repeat.body(), imported, tokens, used, synthetic), repeat.min(), repeat.max()), repeat);
        if (value instanceof SeparatedElement separated)
            return new SeparatedElement(atom(separated.element(), imported, tokens, used, synthetic),
                atom(separated.separator(), imported, tokens, used, synthetic));
        return value;
    }
}
