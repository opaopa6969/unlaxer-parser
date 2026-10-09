package org.unlaxer.dsl.lsp.ubnf;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFPackageResolver;
import org.unlaxer.dsl.bootstrap.UBNFModuleLoader;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot;
import org.unlaxer.dsl.runtime.LexicalExpression;

/** Read-only editor bindings. AST origins authorize edits; recovery never does. */
final class UbnfEditorIndex {
    record Symbol(String name, String kind, String uri, String source, int start, int end, String detail,
                  boolean exported, String origin) {
        Symbol(String name, String kind, String uri, String source, int start, int end, String detail, boolean exported) {
            this(name, kind, uri, source, start, end, detail, exported, uri);
        }
        Range range() { return rangeOf(source, start, end); }
        Location location() { return new Location(uri, range()); }
    }
    record Use(String name, int start, int end, boolean declaration, Scope scope) {}
    record Problem(String message, Range range) {}
    static final class Scope {
        final int start, end;
        final GrammarDecl grammar;
        final Map<String, List<Symbol>> symbols = new LinkedHashMap<>();
        Scope(int start, int end, GrammarDecl grammar) { this.start = start; this.end = end; this.grammar = grammar; }
        void add(String name, Symbol symbol) { symbols.computeIfAbsent(name, key -> new ArrayList<>()).add(symbol); }
        Symbol unique(String name) { var found = symbols.get(name); return found != null && found.size() == 1 ? found.get(0) : null; }
    }
    final String uri, source, code;
    final List<Scope> scopes = new ArrayList<>();
    final List<Symbol> declarations = new ArrayList<>();
    final List<Use> uses = new ArrayList<>();
    final List<Problem> problems = new ArrayList<>();
    final Map<String, String> virtualSources = new LinkedHashMap<>();
    final UBNFSourceSnapshot snapshot;
    List<GrammarDecl> linked;
    boolean safe = true;
    private static final Pattern WORD = Pattern.compile("[A-Za-z_]\\w*(?:\\s*\\.\\s*[A-Za-z_]\\w*)*");

    UbnfEditorIndex(String uri, String source, UBNFModuleLoader.SourceReader reader) {
        this.uri = uri; this.source = source; code = mask(source);
        UBNFSourceSnapshot parsed = null;
        try { parsed = UBNFMapper.parseWithSource(source); } catch (RuntimeException ignored) { safe = false; }
        snapshot = parsed;
        if (parsed != null) {
            for (var grammar : parsed.ast().grammars()) {
                int[] bounds = bounds(parsed, grammar);
                var scope = new Scope(bounds[0], bounds[1], grammar); scopes.add(scope);
                declare(scope, grammar, grammar.name(), "grammar", false);
                for (var token : grammar.tokens()) declare(scope, token, token.name(), "token", grammar.rules().isEmpty());
                for (var rule : grammar.rules()) declare(scope, rule, rule.name(), "rule", false);
                for (var token : grammar.tokens()) if (token instanceof TokenDecl.Declarative lexical) lexicalUses(scope, lexical);
                for (var setting : grammar.settings()) if (setting.key().equals("whitespace") && setting.value() instanceof StringSettingValue value)
                    policyUse(scope, setting, value.value());
                for (var rule : grammar.rules()) {
                    bodyUses(scope, rule.body());
                    for (var annotation : rule.annotations()) if (annotation instanceof WhitespaceAnnotation value && value.style().isPresent())
                        policyUse(scope, annotation, value.style().get());
                }
            }
            linked = parsed.ast().grammars();
        } else {
            // Only completion/outline recovery. Never synthesize rename bindings from malformed input.
            var grammar = new GrammarDecl("", List.of(), List.of(), List.of());
            var scope = new Scope(0, source.length(), grammar); scopes.add(scope);
            var matcher = Pattern.compile("\\b(grammar|token)\\s+([A-Za-z_]\\w*)|\\b([A-Za-z_]\\w*)\\s*::=").matcher(code);
            while (matcher.find()) {
                String name = matcher.group(2) == null ? matcher.group(3) : matcher.group(2);
                int start = matcher.group(2) == null ? matcher.start(3) : matcher.start(2);
                String kind = matcher.group(1) == null ? "rule" : matcher.group(1);
                var symbol = new Symbol(name, kind, uri, source, start, start + name.length(), "", false);
                scope.add(name, symbol); declarations.add(symbol);
            }
        }
        if (!code.contains("@import")) return;
        try {
            Path file = Path.of(URI.create(uri)).toAbsolutePath().normalize();
            var sources = new LinkedHashMap<Path, String>();
            sources.put(file, source);
            UBNFModuleLoader.SourceReader stable = path -> {
                path = path.toAbsolutePath().normalize();
                if (!sources.containsKey(path)) sources.put(path, reader.read(path));
                return sources.get(path);
            };
            var packages = new UBNFPackageResolver(file, stable);
            for (Scope scope : scopes) {
                List<ImportDecl> imports = snapshot != null ? scope.grammar.imports() : recoveredImports();
                for (var declaration : imports) {
                    try {
                        // Use the real module loader to enforce cycles, token-only exports and features.
                        var probe = new UBNFFile(List.of(new GrammarDecl("Editor", List.of(declaration), List.of(), List.of(), List.of())));
                        UBNFModuleLoader.resolve(probe, file, stable);
                        Path module = packages.importPath(file, declaration.path());
                        String text = packages.read(module);
                        var identity = packages.identity(module);
                        String moduleUri = identity == null ? module.toUri().toString()
                            : packageUri(identity.get("sha256").getAsString(), identity.get("file").getAsString());
                        String origin = identity == null ? moduleUri : identity.get("id").getAsString() + "@" + identity.get("version").getAsString()
                            + " · sha256 " + identity.get("sha256").getAsString() + " · " + identity.get("file").getAsString();
                        if (identity != null) virtualSources.put(moduleUri, text);
                        var moduleSnapshot = UBNFMapper.parseWithSource(text);
                        for (var token : moduleSnapshot.ast().grammars().get(0).tokens()) {
                            var symbol = symbol(moduleSnapshot, token, token.name(), "token", moduleUri, true);
                            if (symbol != null) scope.add(declaration.alias() + "." + token.name(), new Symbol(symbol.name(), symbol.kind(), symbol.uri(),
                                symbol.source(), symbol.start(), symbol.end(), symbol.detail(), true, origin));
                        }
                    } catch (IOException | RuntimeException error) {
                        problems.add(new Problem("import " + declaration.alias() + ": " + error.getMessage(), importRange(declaration)));
                    }
                }
            }
            if (snapshot != null && problems.isEmpty()) linked = UBNFModuleLoader.resolve(snapshot.ast(), file, stable).grammars();
        } catch (IOException | RuntimeException error) {
            problems.add(new Problem("import を解決できません。保存場所・alias・token 名を確認してください: " + error.getMessage(), rangeOf(source, 0, Math.min(1, source.length()))));
        }
    }

    private List<ImportDecl> recoveredImports() {
        var result = new ArrayList<ImportDecl>();
        var matcher = Pattern.compile("@import\\s+([A-Za-z_]\\w*)\\s+from\\s+('(?:\\\\.|[^'\\\\])*')").matcher(source);
        while (matcher.find()) if (code.startsWith("@import", matcher.start())) {
            try { result.addAll(UBNFMapper.parse("grammar Editor { " + matcher.group() + " }").grammars().get(0).imports()); }
            catch (RuntimeException ignored) { /* Invalid/incomplete import: no guessed path. */ }
        }
        return result;
    }

    private Range importRange(ImportDecl declaration) {
        if (snapshot != null && snapshot.spanOf(declaration).isPresent()) {
            var b = bounds(snapshot, declaration); return rangeOf(source, b[0], b[1]);
        }
        return rangeOf(source, 0, Math.min(1, source.length()));
    }

    private void declare(Scope scope, Object node, String name, String kind, boolean exported) {
        Symbol symbol = symbol(snapshot, node, name, kind, uri, exported);
        if (symbol == null) { safe = false; return; }
        declarations.add(symbol);
        if (!kind.equals("grammar")) {
            scope.add(name, symbol);
            uses.add(new Use(name, symbol.start(), symbol.end(), true, scope));
        }
    }

    private static Symbol symbol(UBNFSourceSnapshot snapshot, Object node, String name, String kind, String uri, boolean exported) {
        var bounds = bounds(snapshot, node);
        String text = snapshot.source(), code = mask(text);
        String prefix = kind.equals("rule") ? "" : "\\b" + kind + "\\s+";
        var matcher = Pattern.compile(prefix + "\\b(" + Pattern.quote(name) + ")\\b" + (kind.equals("rule") ? "\\s*::=" : ""))
            .matcher(code).region(bounds[0], bounds[1]);
        if (!matcher.find()) return null;
        return new Symbol(name, kind, uri, text, matcher.start(1), matcher.end(1), text.substring(bounds[0], bounds[1]), exported);
    }

    private void lexicalUses(Scope scope, TokenDecl.Declarative token) {
        int[] b = bounds(snapshot, token);
        int begin = code.indexOf("::=", b[0]) + 3;
        var expected = new ArrayList<String>(); lexicalRefs(token.expression(), expected);
        var actual = new ArrayList<Use>();
        var matcher = WORD.matcher(code).region(begin, b[1]);
        while (matcher.find()) {
            String name = matcher.group().replaceAll("\\s", "");
            if (List.of("ANY", "EOF", "BOF", "BOL", "EOL").contains(name)) continue;
            String before = code.substring(begin, matcher.start());
            if (before.matches("(?s).*(?:CAPTURE|SAME_AS)\\s*\\(\\s*")) continue;
            int next = matcher.end(); while (next < b[1] && Character.isWhitespace(code.charAt(next))) next++;
            if (next < b[1] && code.charAt(next) == '(' && List.of("CAPTURE", "SAME_AS", "LOOKAHEAD", "NEGATIVE_LOOKAHEAD", "CHAR_RANGE", "NEGATION").contains(name)) continue;
            actual.add(new Use(name, matcher.start(), matcher.end(), false, scope));
        }
        if (!expected.equals(actual.stream().map(Use::name).toList())) { safe = false; return; }
        uses.addAll(actual);
    }

    private static void lexicalRefs(LexicalExpression expression, List<String> result) {
        if (expression.op() == LexicalExpression.Op.REF) result.add(expression.text());
        for (var child : expression.children()) lexicalRefs(child, result);
    }

    private static String packageUri(String hash, String file) {
        try { return new URI("ubnf-package", null, "/" + hash + "/" + file, null).toASCIIString(); }
        catch (java.net.URISyntaxException invalid) { throw new IllegalArgumentException("invalid package source URI"); }
    }

    private void policyUse(Scope scope, Object node, String policy) {
        if (policy.equalsIgnoreCase("none") || policy.equalsIgnoreCase("javaStyle")) return;
        var bounds = bounds(snapshot, node);
        var matcher = WORD.matcher(code).region(bounds[0], bounds[1]);
        while (matcher.find()) if (matcher.group().replaceAll("\\s", "").equals(policy)) {
            uses.add(new Use(policy, matcher.start(), matcher.end(), false, scope)); return;
        }
    }

    private void bodyUses(Scope scope, RuleBody body) {
        if (body instanceof ChoiceBody choice) choice.alternatives().forEach(item -> bodyUses(scope, item));
        else for (var element : ((SequenceBody) body).elements()) atomUses(scope, element.element());
    }

    private void atomUses(Scope scope, AtomicElement atom) {
        if (atom instanceof RuleRefElement ref) {
            var b = bounds(snapshot, ref);
            var matcher = WORD.matcher(code).region(b[0], b[1]);
            String name = ref.namespace().map(value -> value + ".").orElse("") + ref.name();
            if (matcher.find() && matcher.group().replaceAll("\\s", "").equals(name)) uses.add(new Use(name, matcher.start(), matcher.end(), false, scope));
            else safe = false;
        } else if (atom instanceof GroupElement group) bodyUses(scope, group.body());
        else if (atom instanceof OptionalElement optional) bodyUses(scope, optional.body());
        else if (atom instanceof RepeatElement repeat) bodyUses(scope, repeat.body());
        else if (atom instanceof OneOrMoreElement repeat) atomUses(scope, repeat.body());
        else if (atom instanceof BoundedRepeatElement repeat) atomUses(scope, repeat.body());
        else if (atom instanceof SeparatedElement separated) { atomUses(scope, separated.element()); atomUses(scope, separated.separator()); }
    }

    Scope scopeAt(int offset) { return scopes.stream().filter(scope -> offset >= scope.start && offset <= scope.end).findFirst().orElse(null); }
    Use useAt(int offset) { return uses.stream().filter(use -> offset >= use.start && offset < use.end).findFirst().orElse(null); }
    Symbol target(Use use) { return use == null ? null : use.scope.unique(use.name); }
    List<Use> references(Use use) {
        Symbol target = target(use);
        return target == null ? List.of() : uses.stream().filter(item -> target.equals(target(item))).toList();
    }
    boolean editable(Use use) {
        Symbol target = target(use);
        return safe && problems.isEmpty() && target != null && target.uri.equals(uri) && !target.exported;
    }
    static Range rangeOf(String text, int start, int end) {
        return new Range(UBNFLanguageServerExt.positionAt(text, start), UBNFLanguageServerExt.positionAt(text, end));
    }
    static int offset(String text, Position position) {
        int start = 0;
        for (int line = 0; line < position.getLine(); line++) { int next = text.indexOf('\n', start); if (next < 0) return text.length(); start = next + 1; }
        int end = text.indexOf('\n', start); if (end < 0) end = text.length();
        return Math.min(end, start + Math.max(0, position.getCharacter()));
    }
    private static int[] bounds(UBNFSourceSnapshot snapshot, Object node) {
        var span = snapshot.spanOf(node).orElseThrow(() -> new IllegalStateException("missing source origin"));
        return new int[] {snapshot.source().offsetByCodePoints(0, span.start()), snapshot.source().offsetByCodePoints(0, span.end())};
    }

    /** Preserve UTF-16 offsets/newlines while removing trivia and quoted text. */
    static String mask(String source) {
        char[] chars = source.toCharArray();
        for (int i = 0; i < chars.length;) {
            int start = i;
            char c = chars[i];
            if (c == '\'' || c == '"') {
                i++;
                while (i < chars.length) { char next = chars[i++]; if (next == '\\' && i < chars.length) i++; else if (next == c) break; }
            } else if (source.startsWith("//", i)) {
                i += 2; while (i < chars.length && chars[i] != '\n' && chars[i] != '\r') i++;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2); i = end < 0 ? chars.length : end + 2;
            } else { i++; continue; }
            for (int j = start; j < i; j++) if (chars[j] != '\n' && chars[j] != '\r') chars[j] = ' ';
        }
        return new String(chars);
    }
}
