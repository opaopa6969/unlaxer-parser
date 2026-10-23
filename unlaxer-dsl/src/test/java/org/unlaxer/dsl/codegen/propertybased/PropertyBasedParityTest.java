package org.unlaxer.dsl.codegen.propertybased;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Property-based Java / native Rust parity over generated grammars and inputs (#705).
 *
 * <p>CI runs a fixed seed set within a small budget. Larger runs are manual:
 * {@code -Dpbt.seed=1000 -Dpbt.grammars=200 -Dpbt.batch=25}; see
 * {@code docs/property-based-testing.md}.
 */
public class PropertyBasedParityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    /** CI seed set: seeds {@code 1..24}, one batch, so one Java and one Rust compilation. */
    static final long CI_FIRST_SEED = 1;
    static final int CI_GRAMMARS = 24;
    static final int CI_BATCH = 24;
    static final int DERIVATIONS = 4, MUTATIONS = 7, LEFT_RECURSION_EVERY = 3, RESERVE_DERIVATIONS = 12;
    /** Lower bound on seeds whose own generated inputs include an accepted one. */
    static final int MIN_ACCEPTING_SEEDS_PERCENT = 60;
    // Coverage ratios are only meaningful over many seeds; a hand-run reproduction of one seed skips them.
    static final int MIN_COVERAGE_GRAMMARS = 12;

    private static long longProperty(String name, long fallback) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : Long.parseLong(value.strip());
    }

    @Test
    public void generatorIsDeterministicBoundedAndValid() {
        for (long seed = 1; seed <= 1_000; seed++) {
            var a = new GrammarGenerator(seed, GrammarGenerator.Limits.CI);
            var b = new GrammarGenerator(seed, GrammarGenerator.Limits.CI);
            GrammarModel ga = a.grammar(), gb = b.grammar();
            assertEquals("seed " + seed, ga.render("G", "p"), gb.render("G", "p"));
            assertEquals("seed " + seed, a.inputs(ga, DERIVATIONS, MUTATIONS), b.inputs(gb, DERIVATIONS, MUTATIONS));
            assertTrue("seed " + seed, ga.valid());
            String text = ga.render("G", "p");
            try {
                org.unlaxer.dsl.bootstrap.UBNFMapper.parse(text);
            } catch (RuntimeException error) {
                throw new AssertionError("seed " + seed + " renders unparsable UBNF:\n" + text, error);
            }
            assertTrue("seed " + seed + " rule bound", ga.rules.size() <= GrammarGenerator.Limits.CI.maxRules());
            if (seed <= 200) for (GrammarModel candidate : GrammarGenerator.shrinkGrammar(ga)) {
                assertTrue("shrink keeps validity", candidate.valid());
                assertTrue("shrink never grows", candidate.size() <= ga.size());
            }
        }
    }

    @Test
    public void knownDifferenceConditionOnlyMatchesTheTrackedShape() {
        // #706: syntax failures on both sides, Java offset after the Rust offset.
        assertTrue(KnownDifferences.javaSyntaxOffsetAfterRust(
                "java={\"kind\":\"syntax\",\"offset\":2} rust={\"kind\":\"syntax\",\"offset\":1}"));
        assertFalse(KnownDifferences.javaSyntaxOffsetAfterRust(
                "java={\"kind\":\"syntax\",\"offset\":1} rust={\"kind\":\"syntax\",\"offset\":2}"));
        assertFalse(KnownDifferences.javaSyntaxOffsetAfterRust(
                "java={\"kind\":\"trailing_input\",\"offset\":2} rust={\"kind\":\"trailing_input\",\"offset\":1}"));
        assertFalse(KnownDifferences.javaSyntaxOffsetAfterRust(
                "java={\"kind\":\"syntax\",\"offset\":2} rust={\"kind\":\"trailing_input\",\"offset\":1}"));
        assertFalse(KnownDifferences.javaSyntaxOffsetAfterRust("java=null rust={\"kind\":\"syntax\",\"offset\":1}"));
        // #706 for the rule stack (#703): same offset, Java frames start later than Rust's.
        String tail = " far(java/rust)=2/2 diag(java/rust)={}/{}";
        assertTrue(KnownDifferences.javaStackStartsAfterRust("java=\"2|0|R0@0>R1@2\" rust=\"2|0|R0@0>R1@1\"" + tail));
        assertTrue(KnownDifferences.javaStackStartsAfterRust("java=\"1|0|R0@0>R1@1\" rust=\"1|0|R0@0\"" + tail));
        assertFalse("earlier Java start", KnownDifferences.javaStackStartsAfterRust("java=\"2|0|R0@0>R1@1\" rust=\"2|0|R0@0>R1@2\"" + tail));
        assertFalse("different offset", KnownDifferences.javaStackStartsAfterRust("java=\"3|0|R0@0>R1@2\" rust=\"2|0|R0@0>R1@1\"" + tail));
        assertFalse("different rule", KnownDifferences.javaStackStartsAfterRust("java=\"2|0|R0@0>R2@2\" rust=\"2|0|R0@0>R1@1\"" + tail));
        assertFalse("Rust-only frame", KnownDifferences.javaStackStartsAfterRust("java=\"1|0|R0@0\" rust=\"1|0|R0@0>R1@1\"" + tail));
        assertFalse("Java-only frame not later", KnownDifferences.javaStackStartsAfterRust("java=\"1|0|R0@1>R1@1\" rust=\"1|0|R0@1\"" + tail));
        assertFalse("stack on one side only", KnownDifferences.javaStackStartsAfterRust("java=\"-\" rust=\"1|0|R0@0\"" + tail));
        assertFalse("different omitted", KnownDifferences.javaStackStartsAfterRust("java=\"2|1|R0@0>R1@2\" rust=\"2|0|R0@0>R1@1\"" + tail));
    }

    /**
     * #739: corpus entries whose inputs exceed {@link ParityHarness#STEP_BUDGET} finish in bounded
     * time, and exactly the declared inputs are left unobserved. Without the budget the Java
     * acceptance check of such an input does not return (seed 81), so this test times out.
     */
    @Test(timeout = 300_000)
    public void overBudgetCorpusFinishesInBoundedTime() throws Exception {
        assumeTrue(Boolean.getBoolean("rustConformance"));
        var cases = corpusCases().stream().filter(c -> !c.overBudget().isEmpty()).toList();
        assertFalse("the corpus must keep an over-budget regression", cases.isEmpty());
        var stats = new ParityHarness.Stats();
        var findings = new ParityHarness(repo, temporary.getRoot().toPath()).evaluate(cases, stats);
        assertTrue(findings.toString(), findings.isEmpty());
        assertEquals("both frontends accept the grammars", 0, stats.rejected);
        assertEquals(cases.stream().mapToInt(c -> c.overBudget().size()).sum(), stats.overBudget);
        assertTrue("within-budget inputs of the same grammars are still observed", stats.inputs > 0);
    }

    @Test
    public void generatedGrammarsAgreeAcrossHostsConfigurationsAndInvariants() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] PropertyBasedParityTest requires -DrustConformance=true and rustc/cargo");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        long started = System.nanoTime();
        long first = longProperty("pbt.seed", CI_FIRST_SEED);
        int grammars = (int) longProperty("pbt.grammars", CI_GRAMMARS);
        int batch = (int) longProperty("pbt.batch", CI_BATCH);
        var harness = new ParityHarness(repo, temporary.getRoot().toPath());
        var known = KnownDifferences.load(repo);
        var stats = new ParityHarness.Stats();
        var models = new LinkedHashMap<String, GrammarModel>();
        var reported = new ArrayList<JsonObject>();
        var excluded = new LinkedHashMap<String, Integer>();

        // 1. Fixed regressions found by earlier runs (minimized, corpus form).
        var corpus = corpusCases();
        var cases = new ArrayList<>(corpus);
        // 2. Seeded random grammars.
        for (long seed = first; seed < first + grammars; seed++) {
            var generator = new GrammarGenerator(seed, GrammarGenerator.Limits.CI);
            GrammarModel model = generator.grammar();
            String id = "seed-" + seed;
            models.put(id, model);
            var inputs = generator.inputs(model, DERIVATIONS, MUTATIONS);
            cases.add(ParityHarness.Case.of(id, model, inputs, generator.reserve(model, RESERVE_DERIVATIONS, 2)));
            // 3. Every LEFT_RECURSION_EVERY-th seed also yields a left-recursive variant that both
            //    frontends must reject (generated grammars avoid the form by construction).
            if (seed % LEFT_RECURSION_EVERY == 0)
                cases.add(ParityHarness.Case.rejected(id + "-left-recursive", GrammarGenerator.leftRecursive(model), "E-LEFT-RECURSION"));
        }
        var gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.createDirectories(Path.of("target"));
        var raw = new ArrayList<JsonObject>();
        var pending = new ArrayList<Object[]>(); // {finding, case, entry}
        for (int from = 0; from < cases.size(); from += batch) {
            var slice = cases.subList(from, Math.min(cases.size(), from + batch));
            var findings = harness.evaluate(slice, stats);
            var seen = new java.util.HashSet<String>();
            for (var finding : findings) {
                String reason = known.match(finding, caseOf(slice, finding.caseId()));
                if (reason != null) {
                    excluded.merge(reason, 1, Integer::sum);
                    continue;
                }
                if (!seen.add(finding.caseId() + " " + finding.signature())) continue;
                var c = caseOf(slice, finding.caseId());
                var entry = corpusEntry(finding, c);
                raw.add(entry);
                pending.add(new Object[] {finding, c, entry});
            }
            // Written before any shrinking, so a CI timeout during shrinking still leaves evidence.
            Files.writeString(Path.of("target/property-based-parity-raw.json"), gson.toJson(raw));
        }
        // Shrink a bounded number of representatives (distinct kinds first) within a time budget;
        // the rest are reported unshrunk. Defaults keep a real discrepancy inside the CI job timeout.
        int shrinkLimit = (int) longProperty("pbt.shrinkLimit", 3);
        long shrinkDeadline = System.nanoTime() + longProperty("pbt.shrinkSeconds", 300) * 1_000_000_000L;
        var order = new ArrayList<Object[]>();
        var kinds = new java.util.HashSet<String>();
        for (Object[] p : pending) if (kinds.add(((ParityHarness.Finding) p[0]).kind())) order.add(p);
        for (Object[] p : pending) if (!order.contains(p)) order.add(p);
        int shrunk = 0;
        for (Object[] p : order) {
            var finding = (ParityHarness.Finding) p[0];
            var entry = (JsonObject) p[2];
            GrammarModel model = models.get(finding.caseId());
            if (model != null && finding.input() != null && !Boolean.getBoolean("pbt.noShrink")
                    && shrunk < shrinkLimit && System.nanoTime() < shrinkDeadline) {
                entry = new Shrinker(harness, known, shrinkDeadline).shrink(finding, model, entry);
                shrunk++;
            } else entry.addProperty("shrunk", false);
            reported.add(entry);
            Files.writeString(Path.of("target/property-based-parity.json"), gson.toJson(reported));
        }
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        System.out.println("[pbt] seeds " + first + ".." + (first + grammars - 1) + " corpus=" + corpus.size()
                + " " + stats + " excludedKnown=" + excluded + " findings=" + reported.size() + " shrunk=" + shrunk
                + " seconds=" + seconds);
        Files.writeString(Path.of("target/property-based-parity.json"), gson.toJson(reported));
        Files.write(Path.of("target/property-based-parity-observations.jsonl"), stats.evidence);
        Files.writeString(Path.of("target/property-based-parity-stats.txt"), stats + "\nexcluded=" + excluded + "\nseconds=" + seconds + "\n");
        assertTrue("findings (minimized, corpus form) in target/property-based-parity.json:\n" + gson.toJson(reported), reported.isEmpty());
        assertTrue("both hosts must exercise success memo replay", stats.javaSuccessHits > 0 && stats.rustSuccessHits > 0);
        assertTrue("both hosts must exercise failure memo replay", stats.javaFailureHits > 0 && stats.rustFailureHits > 0);
        assertTrue("known differences that excluded nothing are stale or too narrow: " + known.unused(), known.unused().isEmpty());
        if (grammars >= MIN_COVERAGE_GRAMMARS) {
            assertTrue("most generated grammars must be accepted", stats.rejected * 4 <= stats.grammars);
            assertTrue("left-recursive variants must be rejected by both frontends", stats.rejectedAsExpected >= grammars / LEFT_RECURSION_EVERY);
            // Corpus inputs do not count: generated inputs alone must reach accepted parses (AST, CST).
            assertTrue("generated inputs must be accepted in at least " + MIN_ACCEPTING_SEEDS_PERCENT + "% of the seeds: "
                            + stats.generatedAcceptingCases + "/" + grammars,
                    stats.generatedAcceptingCases * 100 >= (long) grammars * MIN_ACCEPTING_SEEDS_PERCENT);
        }
    }

    private static ParityHarness.Case caseOf(List<ParityHarness.Case> cases, String id) {
        return cases.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    /** Corpus entries; {@code overBudget} lists exactly the inputs expected above the step budget (#739). */
    private List<ParityHarness.Case> corpusCases() throws Exception {
        var corpus = new ArrayList<ParityHarness.Case>();
        for (var element : corpusEntries()) {
            var entry = element.getAsJsonObject();
            var inputs = new ArrayList<String>();
            entry.getAsJsonArray("inputs").forEach(i -> inputs.add(i.getAsString()));
            var over = new ArrayList<String>();
            if (entry.has("overBudget")) entry.getAsJsonArray("overBudget").forEach(i -> over.add(i.getAsString()));
            corpus.add(new ParityHarness.Case(entry.get("id").getAsString(), entry.get("grammar").getAsString(), inputs, Map.of("corpus", 1),
                    entry.has("expectedRejection") ? entry.get("expectedRejection").getAsString() : null, List.of(), over));
        }
        return corpus;
    }

    private JsonArray corpusEntries() throws Exception {
        Path file = repo.resolve("spec-corpus/property-based/regressions.json");
        return JsonParser.parseString(Files.readString(file)).getAsJsonArray();
    }

    static JsonObject corpusEntry(ParityHarness.Finding finding, ParityHarness.Case c) {
        var entry = new JsonObject();
        entry.addProperty("id", finding.caseId());
        entry.addProperty("kind", finding.kind());
        entry.addProperty("configuration", finding.configuration());
        entry.addProperty("detail", finding.detail());
        entry.addProperty("grammar", c.grammar().replaceAll("\n\\s*", " ").trim());
        var inputs = new JsonArray();
        if (finding.input() != null) inputs.add(finding.input());
        entry.add("inputs", inputs);
        return entry;
    }
}
