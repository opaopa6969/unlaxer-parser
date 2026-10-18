package org.unlaxer.dsl.codegen.propertybased;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedy minimization of a finding (#705): first the input, then the grammar, then the input
 * again. Every round evaluates all candidates in one batch (one Java and one Rust compilation)
 * and keeps the smallest candidate that still reproduces a finding of the same kind.
 */
final class Shrinker {
    static final int MAX_ROUNDS = 12, MAX_CANDIDATES = 40;

    private final ParityHarness harness;
    private final KnownDifferences known;

    Shrinker(ParityHarness harness, KnownDifferences known) {
        this.harness = harness;
        this.known = known;
    }

    JsonObject shrink(ParityHarness.Finding finding, GrammarModel model, JsonObject original) throws Exception {
        String kind = finding.kind();
        String input = finding.input();
        ParityHarness.Finding last = finding;
        var shrunkInput = shrinkInput(model, input, kind);
        if (shrunkInput != null) {
            input = shrunkInput.input();
            last = shrunkInput;
        }
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<GrammarModel> candidates = GrammarGenerator.shrinkGrammar(model);
            if (candidates.isEmpty()) break;
            candidates = candidates.subList(0, Math.min(MAX_CANDIDATES, candidates.size()));
            var cases = new ArrayList<ParityHarness.Case>();
            for (int i = 0; i < candidates.size(); i++)
                cases.add(ParityHarness.Case.of("shrink-" + i, candidates.get(i), List.of(input)));
            ParityHarness.Finding best = null;
            int bestIndex = -1;
            for (var f : harness.evaluate(cases, null)) {
                if (!f.kind().equals(kind) || known.match(f, cases.get(index(f))) != null) continue;
                int i = index(f);
                if (bestIndex < 0 || i < bestIndex) {
                    bestIndex = i;
                    best = f;
                }
            }
            if (best == null) break;
            model = candidates.get(bestIndex);
            last = best;
        }
        shrunkInput = shrinkInput(model, input, kind);
        if (shrunkInput != null) {
            input = shrunkInput.input();
            last = shrunkInput;
        }
        var entry = new JsonObject();
        entry.addProperty("id", original.get("id").getAsString() + "-min");
        entry.addProperty("kind", kind);
        entry.addProperty("configuration", last.configuration());
        entry.addProperty("detail", last.detail());
        entry.addProperty("grammar", model.renderOneLine("Pbt", "pbt.corpus"));
        var inputs = new JsonArray();
        inputs.add(input);
        entry.add("inputs", inputs);
        entry.add("original", original);
        return entry;
    }

    private static int index(ParityHarness.Finding f) {
        return Integer.parseInt(f.caseId().substring("shrink-".length()));
    }

    /** The finding for the shortest reproducing simplification, or null when none reproduces. */
    private ParityHarness.Finding shrinkInput(GrammarModel model, String input, String kind) throws Exception {
        ParityHarness.Finding best = null;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            var candidates = GrammarGenerator.shrinkInput(input);
            if (candidates.isEmpty()) break;
            candidates = candidates.subList(0, Math.min(MAX_CANDIDATES, candidates.size()));
            var c = ParityHarness.Case.of("shrink-0", model, candidates);
            ParityHarness.Finding found = null;
            for (var f : harness.evaluate(List.of(c), null))
                if (f.kind().equals(kind) && f.input() != null && known.match(f, c) == null
                        && (found == null || f.input().length() < found.input().length()))
                    found = f;
            if (found == null) break;
            best = found;
            input = found.input();
        }
        return best;
    }
}
