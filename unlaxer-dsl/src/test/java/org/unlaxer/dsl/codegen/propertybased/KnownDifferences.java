package org.unlaxer.dsl.codegen.propertybased;

import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Explicitly excluded, already-tracked differences (#705). Every entry names an issue and a
 * reason; nothing is excluded silently. The test prints how many findings each entry excluded.
 */
final class KnownDifferences {
    private record Entry(String id, Pattern kind, Pattern grammar, Pattern detail) {}

    private final List<Entry> entries;

    private KnownDifferences(List<Entry> entries) {
        this.entries = entries;
    }

    static KnownDifferences load(Path repo) throws Exception {
        var list = new ArrayList<Entry>();
        if (Boolean.getBoolean("pbt.ignoreKnown")) return new KnownDifferences(list); // report everything
        var file = repo.resolve("spec-corpus/property-based/known-differences.json");
        for (var element : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
            var o = element.getAsJsonObject();
            if (!o.has("issue") || !o.has("reason")) throw new IllegalStateException("known difference needs issue and reason: " + o);
            list.add(new Entry(o.get("id").getAsString() + " (" + o.get("issue").getAsString() + ")",
                    Pattern.compile(o.get("kind").getAsString()),
                    o.has("grammar") ? Pattern.compile(o.get("grammar").getAsString()) : null,
                    o.has("detail") ? Pattern.compile(o.get("detail").getAsString()) : null));
        }
        return new KnownDifferences(list);
    }

    /** The excluding entry's label, or null when the finding is new. */
    String match(ParityHarness.Finding finding, ParityHarness.Case c) {
        for (Entry e : entries)
            if (e.kind().matcher(finding.kind()).matches()
                    && (e.grammar() == null || e.grammar().matcher(c.grammar()).find())
                    && (e.detail() == null || e.detail().matcher(String.valueOf(finding.detail())).find()))
                return e.id();
        return null;
    }
}
