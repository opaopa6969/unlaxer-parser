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
     * {@code syntax} failures and the Java offset is greater than the Rust offset (#706);
     * {@code java-stack-starts-after-rust} — see {@link #javaStackStartsAfterRust(String)} (#706);
     * {@code number-incomplete-exponent} — see {@link #numberIncompleteExponent(String, String)} (#717).
     */
    private record Entry(String id, Pattern kind, Pattern grammar, Pattern detail, String condition) {}

    private static final Pattern DIAG = Pattern.compile("^java=(\\{[^}]*\\}) rust=(\\{[^}]*\\})$");
    private static final Pattern STACK = Pattern.compile("^java=\"([^\"]*)\" rust=\"([^\"]*)\"(?: |$)");
    private static final java.util.Set<String> CONDITIONS =
            java.util.Set.of("java-syntax-offset-after-rust", "java-stack-starts-after-rust", "number-incomplete-exponent");

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
            if (condition != null && !CONDITIONS.contains(condition))
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
                    && (e.condition() == null || condition(e.condition(), finding.detail(), finding.input()))) {
                used.merge(e.id(), 1, Integer::sum);
                return e.id();
            }
        return null;
    }

    private static boolean condition(String name, String detail, String input) {
        if (name.equals("number-incomplete-exponent")) return numberIncompleteExponent(detail, input);
        return name.equals("java-stack-starts-after-rust")
                ? javaStackStartsAfterRust(detail) : javaSyntaxOffsetAfterRust(detail);
    }

    /**
     * The #717 shape: Rust's Number token stops before an incomplete exponent and the next element
     * fails there, while Java's NumberParser reads the exponent marker (and a sign) and fails after
     * it. Matches a {@code diag} finding with both sides {@code syntax}, or a {@code stack} finding
     * with the same omitted count and frames, whose Rust offset {@code r} is at an {@code e}/{@code E}
     * right after a digit and whose Java offset is {@code r + 1}, or {@code r + 2} over a sign.
     */
    static boolean numberIncompleteExponent(String detail, String input) {
        int java, rust;
        var diag = DIAG.matcher(String.valueOf(detail));
        var stack = STACK.matcher(String.valueOf(detail));
        if (diag.matches()) {
            if (!javaSyntaxOffsetAfterRust(detail)) return false;
            java = JsonParser.parseString(diag.group(1)).getAsJsonObject().get("offset").getAsInt();
            rust = JsonParser.parseString(diag.group(2)).getAsJsonObject().get("offset").getAsInt();
        } else if (stack.find()) {
            String[] j = stack.group(1).split("\\|", -1), r = stack.group(2).split("\\|", -1);
            if (j.length != 3 || r.length != 3 || !j[1].equals(r[1]) || !j[2].equals(r[2])) return false;
            java = Integer.parseInt(j[0]);
            rust = Integer.parseInt(r[0]);
        } else {
            return false;
        }
        if (input == null) return false;
        int[] cp = input.codePoints().toArray();
        if (rust < 1 || rust >= cp.length || (cp[rust] != 'e' && cp[rust] != 'E')) return false;
        if (cp[rust - 1] < '0' || cp[rust - 1] > '9') return false;
        if (java == rust + 1) return true;
        return java == rust + 2 && rust + 1 < cp.length && (cp[rust + 1] == '+' || cp[rust + 1] == '-');
    }

    /**
     * A {@code java-rust:stack} finding (#703) of the #706 shape: both stacks explain the same
     * failure offset with the same omitted count, agree on a common outer prefix, and after it
     * every Java frame starts later than the Rust frame of the same rule at the same depth, or,
     * where Rust has no frame, later than Rust's innermost frame. Rust has no frame that Java
     * lacks. A positive LOOKAHEAD that advanced the matched cursor shifts Java's starts (and the
     * reach measured from them) forward; any other difference, such as a different offset, a
     * different rule or an earlier Java start, is a new finding.
     */
    static boolean javaStackStartsAfterRust(String detail) {
        var m = STACK.matcher(String.valueOf(detail));
        if (!m.find()) return false;
        String[] java = m.group(1).split("\\|", -1), rust = m.group(2).split("\\|", -1);
        if (java.length != 3 || rust.length != 3 || !java[0].equals(rust[0]) || !java[1].equals(rust[1])) return false;
        String[] jf = java[2].split(">"), rf = rust[2].isEmpty() ? new String[0] : rust[2].split(">");
        if (rf.length > jf.length) return false;
        int common = 0;
        while (common < rf.length && rf[common].equals(jf[common])) common++;
        if (common == jf.length) return false; // identical stacks are no finding
        int previous = rf.length == 0 ? -1 : start(rf[rf.length - 1]);
        for (int i = common; i < jf.length; i++) {
            if (i < rf.length) {
                if (!rule(jf[i]).equals(rule(rf[i])) || start(jf[i]) <= start(rf[i])) return false;
            } else if (start(jf[i]) <= previous) {
                return false;
            }
        }
        return true;
    }

    private static String rule(String frame) {
        return frame.substring(0, frame.lastIndexOf('@'));
    }

    private static int start(String frame) {
        return Integer.parseInt(frame.substring(frame.lastIndexOf('@') + 1));
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
