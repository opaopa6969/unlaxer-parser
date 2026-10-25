package org.unlaxer.dsl.codegen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.unlaxer.dsl.bootstrap.AutoCommit;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot.Span;

/**
 * Generation warnings of one UBNF source (#715), shared in meaning with the native
 * {@code unlaxer_generator::warnings}: the warnings that leave generation unchanged, collected
 * once so that the library, the Java flag CLI, the Java-host {@code generate --target rust} and
 * the native {@code unlaxer generate} report the same code, span and text, and fail alike under
 * {@code --strict} / {@code --fail-on warning}.
 *
 * <p>Sources: {@code W-ANNOTATION-IGNORED} ({@link SimpleAnnotations}) and
 * {@code W-AUTO-COMMIT-SKIPPED} ({@link AutoCommit}, read before the rewrite removes the
 * annotation), and {@code W-COMMENT-IGNORED} ({@link GlobalComment}).
 */
public final class GenerationWarnings {
    private GenerationWarnings() {}

    /** The exit status of a strict failure, the Java CLI's {@code EXIT_STRICT_VALIDATION_ERROR}. */
    public static final int STRICT_EXIT = 5;

    /** {@code span} is a code-point span in this source; {@code message} starts with the code and the rule. */
    public record Warning(String code, String grammar, String rule, Optional<Span> span, String message) {
        public String severity() { return "warning"; }

        /** The line the Rust CLIs print to stderr. */
        public String line() {
            return span.map(s -> "warning: CP " + s.start() + ".." + s.end() + ": " + message)
                .orElse("warning: " + message);
        }
    }

    /**
     * Every generation warning of one source, by grammar and then by span (start, end, code); a
     * warning without a span comes last in its grammar. A source that does not parse has none:
     * generation reports that error instead.
     */
    public static List<Warning> collect(String source) {
        UBNFSourceSnapshot before;
        try {
            UBNFMapper.parseWithSource(source); // the generators' parse, with the rewrite
            before = AutoCommit.parseBeforeAutoCommit(source);
        } catch (RuntimeException invalid) {
            return List.of();
        }
        return warnings(before);
    }

    /**
     * {@link #collect(String)} of {@code parsed.source()}, given the generators' parse of it
     * ({@link UBNFMapper#parseWithSource}), for a caller that already holds it (the UBNF LSP, #798).
     * The source is parsed again only when it can name {@code @autoCommit}: without that annotation
     * the rewrite leaves the tree as written.
     */
    public static List<Warning> collect(UBNFSourceSnapshot parsed) {
        if (!parsed.source().contains(AutoCommit.ANNOTATION)) return warnings(parsed);
        return collect(parsed.source());
    }

    private static List<Warning> warnings(UBNFSourceSnapshot before) {
        var out = new ArrayList<Warning>();
        for (GrammarDecl grammar : before.ast().grammars()) {
            var found = new ArrayList<Warning>();
            for (var warning : SimpleAnnotations.warnings(grammar)) {
                found.add(new Warning(warning.code(), grammar.name(), warning.rule(),
                    before.spanOf(warning.node()), warning.message()));
            }
            for (var warning : AutoCommit.warnings(grammar, before)) {
                found.add(new Warning(warning.code(), grammar.name(), warning.rule(), warning.span(), warning.message()));
            }
            for (var warning : GlobalComment.warnings(grammar)) {
                found.add(new Warning(warning.code(), grammar.name(), warning.subject(),
                    before.spanOf(warning.node()), warning.code() + ": " + warning.subject()
                        + ": @comment has no effect with @whitespace: javaStyle"));
            }
            found.sort(Comparator.<Warning, Boolean>comparing(w -> w.span().isEmpty())
                .thenComparing(w -> w.span().map(Span::start).orElse(0))
                .thenComparing(w -> w.span().map(Span::end).orElse(0))
                .thenComparing(Warning::code));
            out.addAll(found);
        }
        return List.copyOf(out);
    }

    /**
     * The stderr text for {@code warnings}; under a strict policy with at least one warning the
     * text ends with the failure line and the caller must fail with {@link #STRICT_EXIT} before
     * writing anything.
     */
    public static String report(List<Warning> warnings, boolean strict) {
        String text = String.join("\n", warnings.stream().map(Warning::line).toList());
        if (strict && !warnings.isEmpty()) text += "\nstrict: " + warnings.size() + " warning(s) fail generation";
        return text;
    }
}
