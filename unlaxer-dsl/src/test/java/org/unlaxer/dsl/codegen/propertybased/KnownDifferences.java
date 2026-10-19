package org.unlaxer.dsl.codegen.propertybased;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Explicitly excluded, already-tracked differences (#705). Every entry names an issue and a
 * reason; nothing is excluded silently. The test prints how many findings each entry excluded
 * and fails when an entry excluded nothing, so a fixed or drifted difference cannot linger.
 */
final class KnownDifferences {
    /**
     * {@code condition} is a named, numeric check on the finding that a regular expression cannot
     * express. Supported: {@code java-syntax-offset-after-rust} — both {@code diag} values are
     * {@code syntax} failures and the Java offset is greater than the Rust offset (#706).
     */
    private record Entry(String id, Pattern kind, Pattern grammar, Pattern detail, String condition) {}

    private static final Pattern DIAG = Pattern.compile("^java=(\\{[^}]*\\}) rust=(\\{[^}]*\\})$");

    private final List<Entry> entries;
    private final Map<String, Integer> used = new LinkedHashMap<>();

    private KnownDifferences(List<Entry> entries) {
        this.entries = entries;
        for (Entry e : entries) used.put(e.id(), 0);
    }

    static KnownDifferences load(Path repo) throws Exception {
        var list = new ArrayList<Entry>();
        if (Boolean.getBoolean("pbt.ignoreKnown")) return new KnownDifferences(list); // report everything
        var file = repo.resolve("spec-corpus/property-based/known-differences.json");
        for (var element : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
            var o = element.getAsJsonObject();
            if (!o.has("issue") || !o.has("reason")) throw new IllegalStateException("known difference needs issue and reason: " + o);
            String condition = o.has("condition") ? o.get("condition").getAsString() : null;
            if (condition != null && !condition.equals("java-syntax-offset-after-rust"))
                throw new IllegalStateException("unknown condition " + condition);
            list.add(new Entry(o.get("id").getAsString() + " (" + o.get("issue").getAsString() + ")",
                    Pattern.compile(o.get("kind").getAsString()),
                    o.has("grammar") ? Pattern.compile(o.get("grammar").getAsString()) : null,
                    o.has("detail") ? Pattern.compile(o.get("detail").getAsString()) : null,
                    condition));
        }
        return new KnownDifferences(list);
    }

    /** The excluding entry's label, or null when the finding is new. Counts every match. */
    String match(ParityHarness.Finding finding, ParityHarness.Case c) {
        for (Entry e : entries)
            if (e.kind().matcher(finding.kind()).matches()
                    && (e.grammar() == null || e.grammar().matcher(c.grammar()).find())
                    && (e.detail() == null || e.detail().matcher(String.valueOf(finding.detail())).find())
                    && (e.condition() == null || javaSyntaxOffsetAfterRust(finding.detail()))) {
                used.merge(e.id(), 1, Integer::sum);
                return e.id();
            }
        return null;
    }

    static boolean javaSyntaxOffsetAfterRust(String detail) {
        var m = DIAG.matcher(String.valueOf(detail));
        if (!m.matches()) return false;
        JsonObject java = JsonParser.parseString(m.group(1)).getAsJsonObject();
        JsonObject rust = JsonParser.parseString(m.group(2)).getAsJsonObject();
        return "syntax".equals(java.get("kind").getAsString())
                && "syntax".equals(rust.get("kind").getAsString())
                && java.get("offset").getAsInt() > rust.get("offset").getAsInt();
    }

    /** Entries that excluded nothing in this run (stale or too narrow). */
    List<String> unused() {
        return used.entrySet().stream().filter(e -> e.getValue() == 0).map(Map.Entry::getKey).toList();
    }
}
