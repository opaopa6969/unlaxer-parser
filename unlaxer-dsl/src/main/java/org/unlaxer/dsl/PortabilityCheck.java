package org.unlaxer.dsl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot.Span;
import org.unlaxer.dsl.bootstrap.TokenAdapterRegistry;
import org.unlaxer.dsl.bootstrap.TokenContractRegistry;
import org.unlaxer.dsl.codegen.GrammarValidator;
import org.unlaxer.dsl.codegen.rust.RustGrammarLowering;

/** Read-only Rust portability analysis of one UBNF source. */
public final class PortabilityCheck {
    private PortabilityCheck() {}

    public record Diagnostic(String code, Span span, String subject) {
        public String severity() { return "error"; }
    }
    public record Result(boolean portable, String structure, List<Diagnostic> diagnostics) {}

    public static Result check(String source) {
        UBNFSourceSnapshot snapshot;
        try {
            snapshot = UBNFMapper.parseWithSource(source);
        } catch (IllegalArgumentException error) {
            return new Result(false, "unavailable", List.of(new Diagnostic("P-SYNTAX", null, "UBNF syntax")));
        }
        return check(snapshot, snapshot.ast());
    }

    /** File API opts into local module loading; the String API remains I/O-free. */
    public static Result checkFile(java.nio.file.Path path) throws java.io.IOException {
        UBNFSourceSnapshot snapshot;
        try {
            snapshot = UBNFMapper.parseWithSource(java.nio.file.Files.readString(path));
        } catch (IllegalArgumentException error) {
            return new Result(false, "unavailable", List.of(new Diagnostic("P-SYNTAX", null, "UBNF syntax")));
        }
        if (snapshot.ast().grammars().stream().noneMatch(g -> !g.imports().isEmpty()
                && g.settings().stream().anyMatch(s -> s.key().equals("ubnf")
                    && s.value() instanceof StringSettingValue v && v.value().equals("v2"))))
            return check(snapshot, snapshot.ast());
        try {
            var result = check(snapshot, org.unlaxer.dsl.bootstrap.UBNFModuleLoader.resolve(
                snapshot.ast(), path, java.nio.file.Files::readString));
            // A single-file offset must not pretend to identify an imported source.
            return result(result.portable(), result.structure(), result.diagnostics().stream()
                .map(d -> new Diagnostic(d.code(), null, d.subject())).toList());
        } catch (IllegalArgumentException | java.io.IOException error) {
            return new Result(false, "blocked", List.of(new Diagnostic("P-MODULE", null, "UBNF module resolution")));
        }
    }

    private static Result check(UBNFSourceSnapshot snapshot, org.unlaxer.dsl.bootstrap.UBNFAST.UBNFFile file) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<GrammarDecl> grammars = file.grammars();
        if (grammars.size() != 1) add(diagnostics, "P-GRAMMAR-COUNT", snapshot, snapshot.ast(), "expected one grammar");
        for (GrammarDecl grammar : grammars) scanGrammar(grammar, snapshot, diagnostics);
        if (!diagnostics.isEmpty()) return result(false, "blocked", diagnostics);

        GrammarDecl grammar = grammars.get(0);
        try {
            // Lowering is a pure IR conversion. The common validator's token
            // suggestion path is excluded to avoid probing Java classes.
            RustGrammarLowering.lower(grammar);
            boolean invalid = GrammarValidator.validateWithoutClassLoading(grammar).stream()
                .anyMatch(issue -> "ERROR".equals(issue.severity()));
            invalid |= grammar.rules().stream().anyMatch(rule ->
                rule.annotations().stream().anyMatch(annotation -> annotation instanceof LeftAssocAnnotation
                    || annotation instanceof RightAssocAnnotation)
                    && rule.annotations().stream().noneMatch(annotation -> annotation instanceof PrecedenceAnnotation));
            if (invalid) add(diagnostics, "P-STRUCTURE", snapshot, grammar, "Rust structural constraints");
        } catch (RuntimeException error) {
            add(diagnostics, "P-STRUCTURE", snapshot, grammar, "Rust structural constraints");
        }
        return diagnostics.isEmpty() ? result(true, "passed", diagnostics) : result(false, "failed", diagnostics);
    }

    private static Result result(boolean portable, String structure, List<Diagnostic> diagnostics) {
        Set<Diagnostic> unique = new LinkedHashSet<>(diagnostics);
        List<Diagnostic> ordered = new ArrayList<>(unique);
        ordered.sort(Comparator.comparingInt((Diagnostic d) -> d.span() == null ? Integer.MAX_VALUE : d.span().start())
            .thenComparingInt(d -> d.span() == null ? Integer.MAX_VALUE : d.span().end())
            .thenComparing(Diagnostic::code).thenComparing(Diagnostic::subject));
        return new Result(portable, structure, List.copyOf(ordered));
    }

    private static void add(List<Diagnostic> out, String code, UBNFSourceSnapshot snapshot, Object node, String subject) {
        out.add(new Diagnostic(code, snapshot.spanOf(node).orElse(null), subject));
    }

    private static void scanGrammar(GrammarDecl grammar, UBNFSourceSnapshot snapshot, List<Diagnostic> out) {
        for (TokenAdapterRegistry.Diagnostic issue : TokenAdapterRegistry.build(grammar, snapshot).diagnostics()) {
            out.add(new Diagnostic(issue.code(), issue.span(), issue.subject()));
        }
        for (TokenContractRegistry.Diagnostic issue : TokenContractRegistry.build(grammar, snapshot).diagnostics()) {
            out.add(new Diagnostic(issue.code(), issue.span(), issue.subject()));
        }
        for (var issue : org.unlaxer.dsl.codegen.TokenStreamGrammar.problems(grammar))
            add(out, issue.code(), snapshot, issue.node(), issue.subject());
        for (ImportDecl decl : grammar.imports()) add(out, "P-IMPORT", snapshot, decl, decl.path());
        for (GlobalSetting setting : grammar.settings()) {
            if (!Set.of("whitespace", "package", "memoSafeToken", "tokenAdapter", "tokenContract", "ubnf", "feature", "tokenStream", "embedded", "embedding").contains(setting.key())
                || (setting.value() instanceof BlockSettingValue && !Set.of("tokenAdapter", "tokenContract", "embedded").contains(setting.key()))) {
                add(out, "P-SETTING", snapshot, setting, setting.key());
            }
            if (setting.key().equals("whitespace") && setting.value() instanceof StringSettingValue value
                && !whitespaceStyle(grammar, value.value())) {
                add(out, "P-WHITESPACE", snapshot, value, value.value());
            }
        }
        for (TokenDecl token : grammar.tokens()) {
            if (token instanceof TokenDecl.Simple simple && !RustGrammarLowering.supportsSimpleToken(simple.parserClass())) {
                add(out, "P-EXTERNAL-TOKEN", snapshot, token, simple.parserClass());
            } else if (token instanceof TokenDecl.Regex) {
                add(out, "P-TOKEN-KIND", snapshot, token, "REGEX");
            } else if (token instanceof TokenDecl.CaseInsensitive) {
                add(out, "P-TOKEN-KIND", snapshot, token, "CI");
            }
        }
        for (RuleDecl rule : grammar.rules()) {
            boolean skip = rule.annotations().stream().anyMatch(SkipAnnotation.class::isInstance);
            for (Annotation annotation : rule.annotations()) {
                if (!(skip && annotation instanceof MappingAnnotation)) scanAnnotation(grammar, annotation, snapshot, out);
            }
            scanBody(rule.body(), snapshot, out);
        }
    }

    private static boolean whitespaceStyle(GrammarDecl grammar, String style) {
        try { org.unlaxer.dsl.bootstrap.WhitespaceDefinitions.resolve(style,
            org.unlaxer.dsl.bootstrap.LexicalCompiler.compile(grammar)); return true; }
        catch (IllegalArgumentException error) { return false; }
    }

    private static boolean identifier(String value) {
        return value.matches("[A-Za-z][A-Za-z0-9_]*") && !Set.of("Self", "self", "super", "crate").contains(value);
    }

    private static void scanAnnotation(GrammarDecl grammar, Annotation annotation, UBNFSourceSnapshot snapshot, List<Diagnostic> out) {
        String unsupported = null;
        if (annotation instanceof EvalAnnotation) unsupported = "eval";
        else if (annotation instanceof SimpleAnnotation simple) unsupported = simple.name();
        else if (annotation instanceof CommonFieldAnnotation) unsupported = "commonField";
        else if (annotation instanceof EnumAnnotation) unsupported = "enum";
        if (unsupported != null) add(out, "P-ANNOTATION", snapshot, annotation, unsupported);

        if (annotation instanceof MappingAnnotation mapping) {
            if (!identifier(mapping.className())) add(out, "P-MAPPING-TYPE", snapshot, annotation, mapping.className());
            for (String param : mapping.paramNames()) {
                if (!identifier(param) || Set.of("span", "semantics").contains(param)) {
                    add(out, "P-FIELD-NAME", snapshot, annotation, param);
                }
            }
        } else if (annotation instanceof WhitespaceAnnotation whitespace) {
            String style = whitespace.style().orElse("javaStyle");
            if (!whitespaceStyle(grammar, style)) add(out, "P-WHITESPACE", snapshot, annotation, style);
        } else if (annotation instanceof InterleaveAnnotation interleave) {
            String profile = interleave.profile().trim();
            if (!profile.equals("javaStyle") && !profile.equals("commentsAndSpaces")) {
                add(out, "P-INTERLEAVE", snapshot, annotation, interleave.profile());
            }
        } else if (annotation instanceof ScopeTreeAnnotation scope) {
            String mode = scope.mode().trim();
            if (!mode.equals("lexical") && !mode.equals("dynamic")) {
                add(out, "P-SCOPE-MODE", snapshot, annotation, scope.mode());
            }
        }
    }

    private static void scanBody(RuleBody body, UBNFSourceSnapshot snapshot, List<Diagnostic> out) {
        if (body instanceof ChoiceBody choice) {
            for (SequenceBody alternative : choice.alternatives()) scanBody(alternative, snapshot, out);
        } else if (body instanceof SequenceBody sequence) {
            for (AnnotatedElement element : sequence.elements()) {
                element.typeofConstraint().ifPresent(typeof ->
                    add(out, "P-TYPEOF", snapshot, typeof, typeof.captureName()));
                scanAtomic(element.element(), snapshot, out);
            }
        }
    }

    private static void scanAtomic(AtomicElement atomic, UBNFSourceSnapshot snapshot, List<Diagnostic> out) {
        if (atomic instanceof RuleRefElement ref && ref.namespace().isPresent()) {
            add(out, "P-QUALIFIED-REFERENCE", snapshot, atomic, ref.namespace().get() + "." + ref.name());
        } else if (atomic instanceof TerminalElement terminal && terminal.value().isEmpty()) {
            add(out, "P-EMPTY-LITERAL", snapshot, atomic, "empty literal");
        } else if (atomic instanceof GroupElement group) {
            scanBody(group.body(), snapshot, out);
        } else if (atomic instanceof OptionalElement optional) {
            scanBody(optional.body(), snapshot, out);
        } else if (atomic instanceof RepeatElement repeat) {
            scanBody(repeat.body(), snapshot, out);
        } else if (atomic instanceof OneOrMoreElement repeat) {
            scanAtomic(repeat.body(), snapshot, out);
        } else if (atomic instanceof BoundedRepeatElement repeat) {
            scanAtomic(repeat.body(), snapshot, out);
        } else if (atomic instanceof SeparatedElement separated) {
            scanAtomic(separated.element(), snapshot, out);
            scanAtomic(separated.separator(), snapshot, out);
        }
    }
}
