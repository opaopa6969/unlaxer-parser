package org.unlaxer.dsl.codegen;

import java.util.ArrayList;
import java.util.List;
import org.unlaxer.dsl.bootstrap.LexicalCompiler;
import org.unlaxer.dsl.bootstrap.UBNFAST.BlockSettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.GlobalSetting;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.SettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue;
import org.unlaxer.dsl.bootstrap.WhitespaceDefinitions;

/**
 * Global {@code @comment: { line: '//' }} (#712). The only form is the one whose meaning every
 * generator honours: a Java {@code CPPComment} ({@code //} up to, not including, CR / LF / EOF).
 * Forms whose written value used to be ignored are rejected instead of being silently replaced,
 * except next to a global {@code @whitespace: javaStyle}: there every such form generated the
 * same code as javaStyle alone in 3.3.0, so it is kept and reported as {@code W-COMMENT-IGNORED}.
 */
public final class GlobalComment {
    private GlobalComment() {}

    public record Problem(String code, String subject, Object node) {}

    public static final String IGNORED = "W-COMMENT-IGNORED";

    public static List<Problem> problems(GrammarDecl grammar) {
        // tokenStream owns @comment there (E-TOKEN-STREAM-TRIVIA); report it once.
        if (TokenStreamGrammar.enabled(grammar) || javaStyle(grammar)) return List.of();
        List<GlobalSetting> settings = settings(grammar);
        var issues = unsupported(settings, "E-COMMENT-SETTING");
        if (issues.isEmpty() && !settings.isEmpty()) {
            String style = globalWhitespace(grammar);
            boolean named;
            try { named = style != null && WhitespaceDefinitions.resolve(style, LexicalCompiler.compile(grammar)) != null; }
            catch (IllegalArgumentException invalid) { named = false; } // reported by the whitespace checks
            if (named) issues.add(new Problem("E-COMMENT-WHITESPACE", style.trim(), settings.get(0)));
        }
        return List.copyOf(issues);
    }

    /**
     * {@code W-COMMENT-IGNORED} for every {@code @comment} that {@code E-COMMENT-SETTING} would
     * reject, when the grammar default is {@code javaStyle}: javaStyle already skips {@code //},
     * and 3.3.0 generated these grammars as javaStyle alone. Generation is unchanged.
     */
    public static List<Problem> warnings(GrammarDecl grammar) {
        if (TokenStreamGrammar.enabled(grammar) || !javaStyle(grammar)) return List.of();
        return List.copyOf(unsupported(settings(grammar), IGNORED));
    }

    public static void requireValid(GrammarDecl grammar) {
        var problems = problems(grammar);
        if (!problems.isEmpty())
            throw new IllegalArgumentException(problems.get(0).code() + ": " + problems.get(0).subject());
    }

    /** True when rules that inherit the grammar default skip only line comments. */
    public static boolean lineComments(GrammarDecl grammar) {
        if (settings(grammar).isEmpty() || !problems(grammar).isEmpty()) return false;
        // javaStyle already includes line comments; named global whitespace is rejected above.
        return !javaStyle(grammar);
    }

    private static List<Problem> unsupported(List<GlobalSetting> settings, String code) {
        var issues = new ArrayList<Problem>();
        for (int i = 0; i < settings.size(); i++) {
            if (i > 0 || !supported(settings.get(i).value()))
                issues.add(new Problem(code, "comment", settings.get(i)));
        }
        return issues;
    }

    private static boolean javaStyle(GrammarDecl grammar) {
        String style = globalWhitespace(grammar);
        return style != null && WhitespaceDefinitions.normalize(style).equals("javaStyle");
    }

    private static List<GlobalSetting> settings(GrammarDecl grammar) {
        return grammar.settings().stream().filter(setting -> setting.key().equals("comment")).toList();
    }

    private static boolean supported(SettingValue value) {
        return value instanceof BlockSettingValue block && block.entries().size() == 1
            && block.entries().get(0).key().equals("line") && block.entries().get(0).value().equals("//");
    }

    private static String globalWhitespace(GrammarDecl grammar) {
        return grammar.settings().stream()
            .filter(setting -> setting.key().equals("whitespace") && setting.value() instanceof StringSettingValue)
            .map(setting -> ((StringSettingValue) setting.value()).value()).findFirst().orElse(null);
    }
}
