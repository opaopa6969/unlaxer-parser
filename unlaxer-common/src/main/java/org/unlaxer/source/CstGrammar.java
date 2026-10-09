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
