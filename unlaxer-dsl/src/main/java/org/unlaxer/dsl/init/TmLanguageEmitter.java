package org.unlaxer.dsl.init;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.unlaxer.dsl.bootstrap.UBNFAST.AnnotatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.BoundedRepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.GroupElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.OneOrMoreElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.OptionalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.SeparatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.TerminalElement;

/**
 * UBNF AST から VS Code TextMate grammar (tmLanguage.json) を生成する。
 *
 * 文法本体に出現する {@code 'literal'} を以下の2グループに分類してハイライトする:
 * <ul>
 *   <li>アルファベット始まり (例: {@code 'var'}, {@code 'if'}) → keyword.control (\b 区切り)</li>
 *   <li>記号 (例: {@code '+'}, {@code ';'}) → keyword.operator (リテラル)</li>
 * </ul>
 *
 * 加えて固定パターン (コメント, 数値, 識別子) を含める。
 */
public final class TmLanguageEmitter {

    private TmLanguageEmitter() {}

    /** 文法から tmLanguage.json 文字列を生成する。 */
    public static String emit(GrammarDecl grammar, String languageId) {
        Literals lits = collectLiterals(grammar);
        StringBuilder s = new StringBuilder();
        s.append("{\n");
        s.append("  \"$schema\": \"https://raw.githubusercontent.com/martinring/tmlanguage/master/tmlanguage.json\",\n");
        s.append("  \"name\": \"").append(escape(grammar.name())).append("\",\n");
        s.append("  \"scopeName\": \"source.").append(escape(languageId)).append("\",\n");
        s.append("  \"patterns\": [\n");
        s.append("    { \"include\": \"#comments\" },\n");
        s.append("    { \"include\": \"#strings\" },\n");
        if (!lits.words().isEmpty()) {
            s.append("    { \"include\": \"#keywords\" },\n");
        }
        s.append("    { \"include\": \"#numbers\" },\n");
        if (!lits.symbols().isEmpty()) {
            s.append("    { \"include\": \"#operators\" },\n");
        }
        s.append("    { \"include\": \"#identifiers\" }\n");
        s.append("  ],\n");
        s.append("  \"repository\": {\n");
        s.append("    \"comments\": {\n");
        s.append("      \"patterns\": [\n");
        s.append("        { \"name\": \"comment.line.double-slash.").append(languageId)
            .append("\", \"match\": \"//.*$\" },\n");
        s.append("        { \"name\": \"comment.block.").append(languageId)
            .append("\", \"begin\": \"/\\\\*\", \"end\": \"\\\\*/\" }\n");
        s.append("      ]\n");
        s.append("    },\n");
        s.append("    \"strings\": {\n");
        s.append("      \"patterns\": [\n");
        s.append("        { \"name\": \"string.quoted.double.").append(languageId)
            .append("\", \"begin\": \"\\\"\", \"end\": \"\\\"\" },\n");
        s.append("        { \"name\": \"string.quoted.single.").append(languageId)
            .append("\", \"begin\": \"'\", \"end\": \"'\" }\n");
        s.append("      ]\n");
        s.append("    },\n");
        if (!lits.words().isEmpty()) {
            s.append("    \"keywords\": {\n");
            s.append("      \"patterns\": [\n");
            s.append("        { \"name\": \"keyword.control.").append(languageId)
                .append("\", \"match\": \"\\\\b(").append(joinForRegex(lits.words())).append(")\\\\b\" }\n");
            s.append("      ]\n");
            s.append("    },\n");
        }
        s.append("    \"numbers\": {\n");
        s.append("      \"patterns\": [\n");
        s.append("        { \"name\": \"constant.numeric.").append(languageId)
            .append("\", \"match\": \"\\\\b\\\\d+(?:\\\\.\\\\d+)?\\\\b\" }\n");
        s.append("      ]\n");
        s.append("    },\n");
        if (!lits.symbols().isEmpty()) {
            s.append("    \"operators\": {\n");
            s.append("      \"patterns\": [\n");
            s.append("        { \"name\": \"keyword.operator.").append(languageId)
                .append("\", \"match\": \"").append(symbolsCharClass(lits.symbols())).append("\" }\n");
            s.append("      ]\n");
            s.append("    },\n");
        }
        s.append("    \"identifiers\": {\n");
        s.append("      \"patterns\": [\n");
        s.append("        { \"name\": \"variable.other.").append(languageId)
            .append("\", \"match\": \"\\\\b[a-zA-Z_][a-zA-Z0-9_]*\\\\b\" }\n");
        s.append("      ]\n");
        s.append("    }\n");
        s.append("  }\n");
        s.append("}\n");
        return s.toString();
    }

    /** Word キーワードと記号リテラルに分類した結果。 */
    record Literals(List<String> words, List<String> symbols) {}

    static Literals collectLiterals(GrammarDecl grammar) {
        Set<String> raw = new LinkedHashSet<>();
        for (RuleDecl rule : grammar.rules()) {
            collectFromBody(rule.body(), raw);
        }
        List<String> words = new ArrayList<>();
        List<String> symbols = new ArrayList<>();
        for (String lit : raw) {
            if (lit.isEmpty()) continue;
            if (isWordKeyword(lit)) {
                words.add(lit);
            } else {
                symbols.add(lit);
            }
        }
        return new Literals(words, symbols);
    }

    private static boolean isWordKeyword(String s) {
        char c = s.charAt(0);
        if (!Character.isLetter(c) && c != '_') return false;
        for (int i = 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!Character.isLetterOrDigit(ch) && ch != '_') return false;
        }
        return true;
    }

    private static void collectFromBody(RuleBody body, Set<String> acc) {
        switch (body) {
            case ChoiceBody cb -> {
                for (var alt : cb.alternatives()) collectFromBody(alt, acc);
            }
            case SequenceBody sb -> {
                for (AnnotatedElement ae : sb.elements()) collectFromElement(ae.element(), acc);
            }
        }
    }

    private static void collectFromElement(AtomicElement element, Set<String> acc) {
        switch (element) {
            case TerminalElement t -> acc.add(stripQuotes(t.value()));
            case GroupElement g -> collectFromBody(g.body(), acc);
            case OptionalElement o -> collectFromBody(o.body(), acc);
            case RepeatElement r -> collectFromBody(r.body(), acc);
            case OneOrMoreElement r -> collectFromElement(r.body(), acc);
            case BoundedRepeatElement r -> collectFromElement(r.body(), acc);
            case SeparatedElement se -> {
                collectFromElement(se.element(), acc);
                collectFromElement(se.separator(), acc);
            }
            default -> {}
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** 単語キーワード群を `kw1|kw2|...` の正規表現に整形する。 */
    private static String joinForRegex(List<String> words) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(jsonRegexEscape(words.get(i)));
        }
        return sb.toString();
    }

    // 記号リテラルから文字クラス (例 "[+\\-*/]") を生成する。
    // 1文字記号のユニーク集合だけを採用 (多文字記号は誤検知を避けるため除外)。
    private static String symbolsCharClass(List<String> symbols) {
        Set<Character> chars = new LinkedHashSet<>();
        for (String s : symbols) {
            if (s.length() != 1) continue;
            char c = s.charAt(0);
            // skip whitespace and anything that overlaps with comment/string scopes
            if (Character.isWhitespace(c)) continue;
            if (c == '\'' || c == '"' || c == '/' ) continue;
            chars.add(c);
        }
        if (chars.isEmpty()) return "(?!.)"; // never-match, but caller already gates on emptiness
        StringBuilder sb = new StringBuilder("[");
        for (Character c : chars) {
            sb.append(charClassEscape(c));
        }
        sb.append("]");
        return sb.toString();
    }

    /** JSON 文字列内に置く正規表現フラグメント用エスケープ。 */
    private static String jsonRegexEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\\\\\");
                case '"' -> sb.append("\\\"");
                case '/', '+', '*', '?', '.', '|', '(', ')', '[', ']', '{', '}', '^', '$' ->
                    sb.append("\\\\").append(c);
                case '-' -> sb.append("\\\\-");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 文字クラス内の1文字エスケープ (JSON文字列中)。 */
    private static String charClassEscape(char c) {
        return switch (c) {
            case '\\' -> "\\\\\\\\";
            case ']' -> "\\\\]";
            case '[' -> "\\\\[";
            case '^' -> "\\\\^";
            case '-' -> "\\\\-";
            case '"' -> "\\\"";
            default -> String.valueOf(c);
        };
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
