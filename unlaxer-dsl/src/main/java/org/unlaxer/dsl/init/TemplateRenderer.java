package org.unlaxer.dsl.init;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 軽量な Mustache 風テンプレートエンジン。
 *
 * 対応構文:
 * <ul>
 *   <li>{@code {{var}}}             — 変数置換</li>
 *   <li>{@code {{#flag}}...{{/flag}}} — flag が true のときのみブロックを残す</li>
 *   <li>{@code {{^flag}}...{{/flag}}} — flag が false のときのみブロックを残す (反転)</li>
 * </ul>
 *
 * Maven の {@code ${...}} とは衝突しない。フラグも変数も同じ Map に格納する
 * ({@link Boolean} なら条件、それ以外は文字列として置換する)。
 */
public final class TemplateRenderer {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{([A-Za-z_][A-Za-z0-9_]*)\\}\\}");
    // {{#flag}}...{{/flag}} or {{^flag}}...{{/flag}} — non-greedy across newlines.
    private static final Pattern SECTION_PATTERN =
        Pattern.compile("\\{\\{([#^])([A-Za-z_][A-Za-z0-9_]*)\\}\\}(.*?)\\{\\{/\\2\\}\\}", Pattern.DOTALL);

    private final Map<String, Object> values;

    public TemplateRenderer(Map<String, Object> values) {
        this.values = new HashMap<>(values);
    }

    public String render(String template) {
        String pass1 = expandSections(template);
        return expandVars(pass1);
    }

    private String expandSections(String template) {
        // Iterate to handle nested or sequential sections; bounded to avoid infinite loops.
        String current = template;
        for (int i = 0; i < 16; i++) {
            Matcher m = SECTION_PATTERN.matcher(current);
            if (!m.find()) return current;
            StringBuilder buf = new StringBuilder();
            int lastEnd = 0;
            do {
                buf.append(current, lastEnd, m.start());
                String marker = m.group(1);   // "#" or "^"
                String key = m.group(2);
                String body = m.group(3);
                boolean truthy = isTruthy(values.get(key));
                boolean keep = "#".equals(marker) ? truthy : !truthy;
                if (keep) buf.append(body);
                lastEnd = m.end();
            } while (m.find());
            buf.append(current, lastEnd, current.length());
            current = buf.toString();
        }
        return current;
    }

    private String expandVars(String template) {
        Matcher m = VAR_PATTERN.matcher(template);
        StringBuilder buf = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object v = values.get(key);
            String replacement = v == null ? "" : v.toString();
            m.appendReplacement(buf, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(buf);
        return buf.toString();
    }

    private static boolean isTruthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return !s.isEmpty();
        return true;
    }
}
