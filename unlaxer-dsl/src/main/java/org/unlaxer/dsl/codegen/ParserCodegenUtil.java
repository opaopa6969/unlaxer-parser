package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue;

import java.util.List;
import java.util.stream.Collectors;

/**
 * コード生成で共有されるユーティリティメソッド群。
 */
class ParserCodegenUtil {

    private ParserCodegenUtil() {}

    /** Java 文字列リテラル内のエスケープ（バックスラッシュ・ダブルクォート） */
    static String escapeJava(String s) {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
    }

    /** 文字列内の特殊文字をエスケープする */
    static String escapeString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace("\r", "\\r");
    }

    static String escapeChar(char c) {
        return switch (c) {
            case '\'' -> "\\'";
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\t' -> "\\t";
            case '\r' -> "\\r";
            default   -> String.valueOf(c);
        };
    }

    /**
     * トークン名（UPPER_SNAKE_CASE）をパーサークラス名（CamelCaseParser）に変換する。
     * 例: NOT_QUOTE → NotQuoteParser, LOWER → LowerParser, IDENTIFIER → IdentifierParser
     */
    static String toParserClassName(String tokenName) {
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : tokenName.toCharArray()) {
            if (c == '_') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                sb.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        sb.append("Parser");
        return sb.toString();
    }

    static String renderStringListLiteral(List<String> values) {
        if (values.isEmpty()) {
            return "java.util.List.of()";
        }
        return "java.util.List.of(" + values.stream()
            .map(v -> "\"" + escapeJava(v) + "\"")
            .collect(Collectors.joining(", ")) + ")";
    }

    /** @package 設定からパッケージ名を取得する */
    static String getPackageName(GrammarDecl grammar) {
        return grammar.settings().stream()
            .filter(s -> "package".equals(s.key()))
            .map(s -> s.value() instanceof StringSettingValue sv ? sv.value() : "")
            .findFirst()
            .orElse("generated");
    }
}
