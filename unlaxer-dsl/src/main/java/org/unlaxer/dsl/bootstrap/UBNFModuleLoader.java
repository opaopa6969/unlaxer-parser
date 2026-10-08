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
    @FunctionalInterface public interface SourceReader { String read(Path path) throws IOException; }
    private final SourceReader reader;
    private final Map<Path, Map<String, LexicalExpression>> modules = new LinkedHashMap<>();
    private final Set<Path> stack = new java.util.LinkedHashSet<>();

    private UBNFModuleLoader(SourceReader reader) { this.reader = reader; }

    public static UBNFFile load(Path path) throws IOException {
        return resolve(UBNFMapper.parse(Files.readString(path)), path, Files::readString);
    }

    public static UBNFFile resolve(UBNFFile file, Path path, SourceReader reader) throws IOException {
        var loader = new UBNFModuleLoader(reader);
        path = path.toAbsolutePath().normalize();
        loader.stack.add(path);
        var grammars = new ArrayList<GrammarDecl>();
        for (var grammar : file.grammars()) grammars.add(loader.link(grammar, path));
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
            var exports = module(path.getParent().resolve(declaration.path()));
            for (var entry : exports.entrySet())
                imported.put(declaration.alias() + "." + entry.getKey(), entry.getValue());
        }
        var tokens = new ArrayList<TokenDecl>();
        for (var token : grammar.tokens()) {
            tokens.add(token instanceof TokenDecl.Declarative lexical
                ? new TokenDecl.Declarative(token.name(), externalRefs(lexical.expression(), imported)) : token);
        }
        var used = new HashSet<String>();
        grammar.tokens().forEach(t -> used.add(t.name()));
        grammar.rules().forEach(r -> used.add(r.name()));
        var synthetic = new LinkedHashMap<String, String>();
        var rules = new ArrayList<RuleDecl>();
        for (var rule : grammar.rules()) {
            rules.add(new RuleDecl(rule.annotations(), rule.name(), body(rule.body(), imported, tokens, used, synthetic)));
        }
        return new GrammarDecl(grammar.name(), List.of(), grammar.settings(), tokens, rules);
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
        if (value instanceof ChoiceBody choice) return new ChoiceBody(choice.alternatives().stream()
            .map(s -> (SequenceBody) body(s, imported, tokens, used, synthetic)).toList());
        return new SequenceBody(((SequenceBody) value).elements().stream().map(item ->
            new AnnotatedElement(atom(item.element(), imported, tokens, used, synthetic),
                item.captureName(), item.typeofConstraint())).toList());
    }

    private AtomicElement atom(AtomicElement value, Map<String, LexicalExpression> imported, List<TokenDecl> tokens,
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
        if (value instanceof GroupElement group)
            return new GroupElement(body(group.body(), imported, tokens, used, synthetic));
        if (value instanceof OptionalElement optional)
            return new OptionalElement(body(optional.body(), imported, tokens, used, synthetic));
        if (value instanceof RepeatElement repeat)
            return new RepeatElement(body(repeat.body(), imported, tokens, used, synthetic));
        if (value instanceof OneOrMoreElement repeat)
            return new OneOrMoreElement(atom(repeat.body(), imported, tokens, used, synthetic));
        if (value instanceof BoundedRepeatElement repeat)
            return new BoundedRepeatElement(atom(repeat.body(), imported, tokens, used, synthetic), repeat.min(), repeat.max());
        if (value instanceof SeparatedElement separated)
            return new SeparatedElement(atom(separated.element(), imported, tokens, used, synthetic),
                atom(separated.separator(), imported, tokens, used, synthetic));
        return value;
    }
}
