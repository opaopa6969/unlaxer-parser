package org.unlaxer.source;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Versioned, declarative coverage metadata. Reading a profile never installs a provider. */
public final class LanguageProfile {
    public enum Support { SUPPORTED, PARTIAL, EXTERNAL, UNSUPPORTED }
    private static final Set<String> CAPABILITIES = Set.of("PARSE", "AST", "CST", "VALIDATE", "COMPLETION", "HOVER", "DEFINITION", "RENAME", "FORMAT", "CODE_ACTION", "EXECUTE");
    private final List<List<String>> rows;
    private final Map<String, List<String>> singles;
    private final Map<String, Support> entries;
    private final Map<String, Support> capabilities;
    private LanguageProfile(List<List<String>> rows, Map<String, List<String>> singles,
                            Map<String, Support> entries, Map<String, Support> capabilities) {
        this.rows = List.copyOf(rows); this.singles = Map.copyOf(singles);
        this.entries = Map.copyOf(entries); this.capabilities = Map.copyOf(capabilities);
    }
    public static LanguageProfile parse(String text) {
        if (text.getBytes(StandardCharsets.UTF_8).length > 65536 || !text.endsWith("\n") || text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0) throw invalid();
        List<List<String>> rows = new ArrayList<>();
        Map<String, List<String>> singles = new TreeMap<>();
        Map<String, Support> entries = new TreeMap<>(), capabilities = new TreeMap<>();
        Set<String> keys = new HashSet<>();
        for (String line : text.substring(0, text.length() - 1).split("\n", -1)) {
            List<String> row = List.of(line.split("\t", -1));
            if (row.stream().anyMatch(String::isEmpty) || rows.size() >= 512) throw invalid();
            String tag = row.get(0);
            int width = switch (tag) {
                case "profile", "language", "fixture" -> 2;
                case "package", "target", "tool", "grammar", "entry", "capability", "syntax", "difference" -> 3;
                case "source" -> 4;
                default -> throw invalid();
            };
            if (row.size() != width) throw invalid();
            boolean repeated = Set.of("entry", "capability", "syntax", "difference").contains(tag);
            String key = repeated ? tag + "\t" + row.get(1) : tag;
            if (!keys.add(key)) throw invalid();
            if (repeated && !row.get(1).matches("[A-Za-z][A-Za-z0-9_-]*")) throw invalid();
            if (Set.of("entry", "capability", "syntax").contains(tag)) {
                Support support = Support.valueOf(row.get(2));
                if (tag.equals("entry")) entries.put(row.get(1), support);
                if (tag.equals("capability")) capabilities.put(row.get(1), support);
            }
            if (!repeated) singles.put(tag, row);
            rows.add(row);
        }
        if (!singles.keySet().equals(Set.of("profile", "language", "package", "target", "tool", "grammar", "source", "fixture")) || entries.isEmpty() || !capabilities.keySet().equals(CAPABILITIES)) throw invalid();
        if (!singles.get("profile").get(1).equals("1") || !singles.get("language").get(1).matches("[a-z][a-z0-9-]*")
            || !singles.get("package").get(1).matches("[a-z][a-z0-9-]*/[a-z][a-z0-9-]*")
            || !singles.get("package").get(2).matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
            || !singles.get("grammar").get(1).matches("[A-Za-z][A-Za-z0-9_]*")) throw invalid();
        for (String file : List.of(singles.get("grammar").get(2), singles.get("fixture").get(1))) {
            if (!file.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9]+")) throw invalid();
        }
        if (!singles.get("grammar").get(2).endsWith(".ubnf") || !singles.get("source").get(1).startsWith("https://") || capabilities.get("EXECUTE") != Support.UNSUPPORTED) throw invalid();
        return new LanguageProfile(rows, singles, entries, capabilities);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid language profile"); }
    public String language() { return singles.get("language").get(1); }
    public String grammarFile() { return singles.get("grammar").get(2); }
    public String fixtureFile() { return singles.get("fixture").get(1); }
    public List<List<String>> rows() { return rows; }
    public Map<String, Support> entries() { return entries; }
    public Map<String, Support> capabilities() { return capabilities; }
    public LanguageRegions.Language identity(String entry) {
        if (!entries.containsKey(entry) || entries.get(entry) == Support.UNSUPPORTED) throw invalid();
        return new LanguageRegions.Language(language(), singles.get("package").get(1), singles.get("package").get(2), singles.get("grammar").get(1), entry);
    }
    /** Select a locally parsed entry; EXTERNAL is descriptive, never an installed provider. */
    public Selection select(String grammar, String entry) {
        var language = identity(entry);
        if (!language.grammar().equals(grammar) || !local(entries.get(entry)) || !local(capabilities.get("PARSE")))
            throw new IllegalArgumentException("profile grammar/entry cannot be parsed locally");
        return new Selection(this, language);
    }
    private static boolean local(Support support) { return support == Support.SUPPORTED || support == Support.PARTIAL; }
    public record Selection(LanguageProfile profile, LanguageRegions.Language language) {
        public boolean allowsLocal(String capability) { return allows(capability, false); }
        public boolean allows(String capability, boolean providerRegistered) {
            Support support = profile.capabilities().get(capability);
            return local(support) || providerRegistered && support == Support.EXTERNAL;
        }
    }
    public String canonicalTsv() {
        List<String> result = rows.stream().map(row -> String.join("\t", row)).sorted().toList();
        return String.join("\n", result) + "\n";
    }
}
