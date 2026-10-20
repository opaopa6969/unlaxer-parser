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
 * Forms whose written value used to be ignored are rejected instead of being silently replaced.
 */
public final class GlobalComment {
    private GlobalComment() {}

    public record Problem(String code, String subject, Object node) {}

    public static List<Problem> problems(GrammarDecl grammar) {
        // tokenStream owns @comment there (E-TOKEN-STREAM-TRIVIA); report it once.
        if (TokenStreamGrammar.enabled(grammar)) return List.of();
        List<GlobalSetting> settings = settings(grammar);
        var issues = new ArrayList<Problem>();
        for (int i = 0; i < settings.size(); i++) {
            if (i > 0 || !supported(settings.get(i).value()))
                issues.add(new Problem("E-COMMENT-SETTING", "comment", settings.get(i)));
        }
        if (issues.isEmpty() && !settings.isEmpty()) {
            String style = globalWhitespace(grammar);
            boolean named;
            try { named = style != null && WhitespaceDefinitions.resolve(style, LexicalCompiler.compile(grammar)) != null; }
            catch (IllegalArgumentException invalid) { named = false; } // reported by the whitespace checks
            if (named) issues.add(new Problem("E-COMMENT-WHITESPACE", style.trim(), settings.get(0)));
        }
        return List.copyOf(issues);
    }

    public static void requireValid(GrammarDecl grammar) {
        var problems = problems(grammar);
        if (!problems.isEmpty())
            throw new IllegalArgumentException(problems.get(0).code() + ": " + problems.get(0).subject());
    }

    /** True when rules that inherit the grammar default skip only line comments. */
    public static boolean lineComments(GrammarDecl grammar) {
        if (settings(grammar).isEmpty() || !problems(grammar).isEmpty()) return false;
        String style = globalWhitespace(grammar);
        // javaStyle already includes line comments; named global whitespace is rejected above.
        return style == null || !WhitespaceDefinitions.normalize(style).equals("javaStyle");
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
