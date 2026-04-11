package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * トークン関連のコード生成を担当する。
 */
class ParserTokenEmitter {

    private ParserTokenEmitter() {}

    /**
     * grammar の token 宣言に含まれるパーサークラス名を既知パッケージで検索し、
     * import 文のリストを返す。見つからないクラスは無視する。
     */
    static List<String> resolveTokenImports(GrammarDecl grammar) {
        Set<String> alreadyImported = Set.of("WordParser", "SpaceParser", "CPPComment");
        String[] candidatePackages = {
            "org.unlaxer.parser.clang",
            "org.unlaxer.parser.elementary",
            "org.unlaxer.parser.posix",
        };
        List<String> imports = new ArrayList<>();
        for (TokenDecl token : grammar.tokens()) {
            if (!(token instanceof TokenDecl.Simple s)) continue; // UNTIL tokens have no class to import
            String parserClass = s.parserClass();
            if (alreadyImported.contains(parserClass) || parserClass.contains(".")) {
                continue;
            }
            for (String pkg : candidatePackages) {
                try {
                    Class.forName(pkg + "." + parserClass);
                    imports.add("import " + pkg + "." + parserClass + ";");
                    break;
                } catch (ClassNotFoundException ignored) {
                    // 次のパッケージを試す
                }
            }
        }
        return imports;
    }

    /**
     * NEGATION トークンごとに SingleCharacterParser 内部クラスを生成する。
     * 例: token NOT_QUOTE = NEGATION('"')
     *   → public static class NotQuoteParser extends SingleCharacterParser { ... }
     */
    static String generateNegationClasses(ParserGenerator.GenContext ctx) {
        if (ctx.tokenNegationMap.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : ctx.tokenNegationMap.entrySet()) {
            String tokenName = e.getKey();
            String excluded = e.getValue();
            String className = ParserCodegenUtil.toParserClassName(tokenName);
            sb.append("    // --- NEGATION parser for token ").append(tokenName).append(" ---\n");
            sb.append("    public static class ").append(className)
              .append(" extends org.unlaxer.parser.elementary.SingleCharacterParser {\n");
            sb.append("        private static final long serialVersionUID = 1L;\n");
            sb.append("        private static final String EXCLUDED = \"")
              .append(ParserCodegenUtil.escapeString(excluded)).append("\";\n");
            sb.append("        @Override\n");
            sb.append("        public boolean isMatch(char target) {\n");
            sb.append("            return EXCLUDED.indexOf(target) < 0;\n");
            sb.append("        }\n");
            sb.append("    }\n\n");
        }
        return sb.toString();
    }

    /**
     * CHAR_RANGE トークン用の SingleCharacterParser 内部クラス群を生成する。
     * 例: token LOWER = CHAR_RANGE('a','z')
     *   → public static class LowerParser extends SingleCharacterParser { ... }
     */
    static String generateCharRangeClasses(ParserGenerator.GenContext ctx) {
        if (ctx.tokenCharRangeMap.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[]> e : ctx.tokenCharRangeMap.entrySet()) {
            String tokenName = e.getKey();
            char min = (char) e.getValue()[0];
            char max = (char) e.getValue()[1];
            String className = ParserCodegenUtil.toParserClassName(tokenName); // same camelCase logic
            sb.append("    // --- CHAR_RANGE parser for token ").append(tokenName).append(" ---\n");
            sb.append("    public static class ").append(className)
              .append(" extends org.unlaxer.parser.elementary.SingleCharacterParser {\n");
            sb.append("        private static final long serialVersionUID = 1L;\n");
            sb.append("        @Override\n");
            sb.append("        public boolean isMatch(char target) {\n");
            sb.append("            return target >= '").append(ParserCodegenUtil.escapeChar(min))
              .append("' && target <= '").append(ParserCodegenUtil.escapeChar(max)).append("';\n");
            sb.append("        }\n");
            sb.append("    }\n\n");
        }
        return sb.toString();
    }

    /**
     * REGEX トークン用の RegexTokenParser 内部クラス群を生成する。
     * 例: token ID = REGEX('[a-z]+')
     *   → public static class IdParser extends org.unlaxer.dsl.runtime.RegexTokenParser { ... }
     */
    static String generateRegexClasses(ParserGenerator.GenContext ctx) {
        if (ctx.tokenRegexMap.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : ctx.tokenRegexMap.entrySet()) {
            String tokenName = e.getKey();
            String pattern = e.getValue();
            String className = ParserCodegenUtil.toParserClassName(tokenName);
            sb.append("    // --- REGEX parser for token ").append(tokenName).append(" ---\n");
            sb.append("    public static class ").append(className)
              .append(" extends org.unlaxer.dsl.runtime.RegexTokenParser {\n");
            sb.append("        private static final long serialVersionUID = 1L;\n");
            sb.append("        public ").append(className).append("() {\n");
            sb.append("            super(\"").append(ParserCodegenUtil.escapeString(pattern)).append("\");\n");
            sb.append("        }\n");
            sb.append("    }\n\n");
        }
        return sb.toString();
    }
}
