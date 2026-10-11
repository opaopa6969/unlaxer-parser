import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/** Experiment-owned projection: every field, original text, CP and UTF-16 spans. */
final class Canonical {
    static String hex(String text) { return HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8)); }
    static String list(List<String> values) { return "[" + String.join(",", values) + "]"; }
    static String span(String source, int start, int end) {
        int a = source.offsetByCodePoints(0, start), b = source.offsetByCodePoints(0, end);
        return "cp=" + start + ":" + end + ",utf16=" + a + ":" + b + ",text=" + hex(source.substring(a, b));
    }
    static String ast(Object node, String source, Function<Object, int[]> spans) throws Exception {
        var components = node.getClass().getRecordComponents();
        if (components == null) throw new IllegalArgumentException("not an AST record");
        int[] p = spans.apply(node);
        var fields = new java.util.ArrayList<String>();
        for (var field : components) {
            Object value = field.getAccessor().invoke(node);
            fields.add(field.getName() + "=" + value(value, source, spans));
        }
        return node.getClass().getSimpleName() + "(" + span(source, p[0], p[1]) + "," + String.join(",", fields) + ")";
    }
    static String value(Object value, String source, Function<Object, int[]> spans) throws Exception {
        if (value instanceof String s) return "s" + hex(s);
        if (value instanceof List<?> list) {
            var items = new java.util.ArrayList<String>();
            for (Object item : list) items.add(value(item, source, spans));
            return list(items);
        }
        return ast(value, source, spans);
    }
}
