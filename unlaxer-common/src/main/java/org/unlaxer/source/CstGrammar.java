package org.unlaxer.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.RecoveryDiagnostic;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageRegions.Language;
import org.unlaxer.source.LanguageRegions.State;

/** Generated Java grammar adapter; capture bindings are immutable grammar-site metadata. */
public final class CstGrammar implements EmbeddedLanguages.Grammar {
    public record Binding(Class<? extends Parser> rule, Set<String> captures, Language language) {
        public Binding { captures = Set.copyOf(captures); }
    }
    private final String name;
    private final Map<String, Parser> entries;
    private final List<Binding> bindings;
    private final Set<Class<? extends Parser>> boundaries;
    private final Function<Token, List<String>> captures;
    private final boolean whitespace;
    public CstGrammar(String name, Map<String, Parser> entries, List<Binding> bindings,
            Set<Class<? extends Parser>> boundaries, Function<Token, List<String>> captures, boolean whitespace) {
        this.name = name; this.entries = Map.copyOf(entries); this.bindings = List.copyOf(bindings);
        this.boundaries = Set.copyOf(boundaries); this.captures = captures; this.whitespace = whitespace;
    }
    @Override public String name() { return name; }
    @Override public EmbeddedLanguages.Parsed parse(String entry, DocumentSnapshot snapshot) {
        Parser parser = entries.get(entry);
        if (parser == null) { return new EmbeddedLanguages.Parsed(snapshot, State.UNSUPPORTED, List.of()); }
        try (ParseContext context = new ParseContext(StringSource.createRootSource(snapshot.text()))) {
            Parsed parsed = parser.parse(context);
            int end = context.getPosition(TokenKind.consumed).value();
            String remaining = snapshot.slice(new Span(end, snapshot.length()));
            boolean complete = remaining.isEmpty() || whitespace && remaining.codePoints().allMatch(c -> c <= 0x20);
            if (false == parsed.isSucceeded() || false == complete) {
                return new EmbeddedLanguages.Parsed(snapshot, State.FAILED, List.of());
            }
            Token root = parsed.getRootToken(false);
            List<EmbeddedLanguages.Child> children = new ArrayList<>();
            discover(root, children);
            State state = RecoveryDiagnostic.from(root).isEmpty() ? State.COMPLETE : State.PARTIAL;
            return new EmbeddedLanguages.Parsed(snapshot, state, children);
        }
    }
    /** Opt-in EOF repair. Only regions with an original opening and original body escape. */
    public EmbeddedLanguages.Grammar editor(List<String> completions, Function<Parser, String> ruleName,
            Function<Parser, List<String>> captureBindings, org.unlaxer.editor.EditorCst.Options options) {
        List<String> suffixes = List.copyOf(completions);
        return new EmbeddedLanguages.Grammar() {
            @Override public String name() { return name; }
            @Override public EmbeddedLanguages.Parsed parse(String entry, DocumentSnapshot snapshot) {
                EmbeddedLanguages.Parsed strict = CstGrammar.this.parse(entry, snapshot);
                if (strict.state() != State.FAILED) { return strict; }
                var cst = org.unlaxer.editor.EditorCst.parse(snapshot.text(), entries.get(entry), suffixes, ruleName, captureBindings, options);
                if (cst.status() == org.unlaxer.editor.EditorCst.Status.FAILED) { return strict; }
                List<EmbeddedLanguages.Child> children = new ArrayList<>();
                for (var node : cst.nodes()) {
                    for (Binding binding : bindings) {
                        if (false == node.rule().equals(ruleName.apply(Parser.get(binding.rule)))) { continue; }
                        var bodies = node.captures().stream().filter(capture -> binding.captures.contains(capture.name())).toList();
                        if (bodies.size() != 1) { throw new IllegalArgumentException("embedding needs exactly one body capture"); }
                        var body = bodies.get(0);
                        // No synthetic-only opening, no inserted body text, no guessed inverse edit.
                        if (node.span().start() >= body.span().start() || body.synthetic()) { continue; }
                        children.add(new EmbeddedLanguages.Child(binding.language, new Span(node.span().start(), node.span().end()), new Span(body.span().start(), body.span().end()), node.synthetic() && node.span().end() == body.span().end() && body.span().end() == snapshot.length()));
                    }
                }
                children.sort(java.util.Comparator.comparingInt((EmbeddedLanguages.Child child) -> child.full().start())
                    .thenComparing(java.util.Comparator.comparingInt((EmbeddedLanguages.Child child) -> child.full().end()).reversed()));
                List<EmbeddedLanguages.Child> owned = new ArrayList<>();
                for (var child : children) {
                    if (owned.stream().anyMatch(parent -> parent.body().contains(child.full()))) { continue; }
                    owned.add(child);
                }
                return new EmbeddedLanguages.Parsed(snapshot, State.PARTIAL, owned);
            }
        };
    }
    private void discover(Token token, List<EmbeddedLanguages.Child> children) {
        for (Binding binding : bindings) {
            if (token.parser.getClass() == binding.rule) {
                List<Token> bodies = new ArrayList<>();
                collect(token, binding.captures, bodies, true);
                if (bodies.size() != 1) { throw new IllegalArgumentException("embedding needs exactly one body capture"); }
                children.add(new EmbeddedLanguages.Child(binding.language, span(token), span(bodies.get(0))));
                return; // The child grammar owns this body's nested regions.
            }
        }
        for (Token child : token.getChildren(child -> true, Token.ChildrenKind.original).toList()) { discover(child, children); }
    }
    private void collect(Token token, Set<String> wanted, List<Token> found, boolean root) {
        if (false == root && boundaries.contains(token.parser.getClass())) { return; }
        if (captures.apply(token).stream().anyMatch(wanted::contains)) { found.add(token); return; }
        for (Token child : token.getChildren(child -> true, Token.ChildrenKind.original).toList()) { collect(child, wanted, found, false); }
    }
    private static Span span(Token token) {
        int start = token.source.offsetFromRoot().value();
        String text = token.source.sourceAsString();
        return new Span(start, start + text.codePointCount(0, text.length()));
    }
}
