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

    /** #754: compile both targets and compare success/failure, CST and AST in all 12 modes. */
    @Test(timeout = 300_000)
    public void deeplyGroupedRecursiveHelpersAgreeAcrossTargets() throws Exception {
        assumeTrue("enable with -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        var cases = new ArrayList<ParityHarness.Case>();
        for (int depth : List.of(84, 85, 121, 122)) {
            cases.add(new ParityHarness.Case("recursive-depth-" + depth,
                org.unlaxer.dsl.NestingLimitGrammars.recursivePlus(depth),
                List.of("aa", "naaa", "aynaa", "a", "b", ""), Map.of("corpus", 1), null));
        }
        var stats = new ParityHarness.Stats();
        var findings = new ParityHarness(repo, temporary.getRoot().toPath()).evaluate(cases, stats);
        Files.write(Path.of("target/recursive-generation-depth.jsonl"), stats.evidence);
        assertTrue(findings.toString(), findings.isEmpty());
        assertEquals("all grammars accepted", 0, stats.rejected);
        assertEquals("all inputs observed", 24, stats.inputs);
        assertEquals("success cases exercise the recursive branch", 12, stats.accepted);
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
    public void legacyCiGenerationKeepsItsFingerprint() throws Exception {
        // Captured from origin/master ead93369 before #770, including reserve inputs and
        // rejected variants. Calling the new mode must not consume the legacy random stream.
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        for (long seed = CI_FIRST_SEED; seed < CI_FIRST_SEED + CI_GRAMMARS; seed++) {
            new GrammarGenerator(seed, GrammarGenerator.Limits.CI).featureCombinations();
            var generator = new GrammarGenerator(seed, GrammarGenerator.Limits.CI);
            var model = generator.grammar();
            String value = model.render("Pbt", "pbt.generated") + "\n" + generator.inputs(model, DERIVATIONS, MUTATIONS)
                    + "\n" + generator.reserve(model, RESERVE_DERIVATIONS, 2) + "\n";
            if (seed % LEFT_RECURSION_EVERY == 0)
                value += GrammarGenerator.leftRecursive(model).render("Pbt", "pbt.generated");
            digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals("255fc856fa481e558ad2635bfe3c8abf7c0831fce2f90a54a46cbc11f0a5eadc",
                java.util.HexFormat.of().formatHex(digest.digest()));
    }

    @Test
    public void featureCombinationsAreDeterministicBoundedAndCovered() {
        var counts = new LinkedHashMap<String, Integer>();
        int rejected = 0, inserted = 0, nullablePrefixes = 0;
        var comments = new java.util.HashSet<String>();
        for (long seed = CI_FIRST_SEED; seed < CI_FIRST_SEED + CI_GRAMMARS; seed++) {
            var cases = new GrammarGenerator(seed, GrammarGenerator.Limits.CI).featureCombinations();
            assertEquals("seed " + seed, cases, new GrammarGenerator(seed, GrammarGenerator.Limits.CI).featureCombinations());
            for (var c : cases) {
                String text = c.grammar();
                var grammar = org.unlaxer.dsl.bootstrap.UBNFMapper.parse(text).grammars().get(0);
                assertTrue(c.id(), grammar.rules().size() <= GrammarGenerator.Limits.CI.maxRules());
                assertTrue(c.id(), c.inputs().size() <= 22);
                assertTrue(c.id(), c.inputs().stream().allMatch(s -> s.codePointCount(0, s.length()) <= 64));
                var raising = org.unlaxer.dsl.codegen.RaisingOperators.problems(grammar);
                if (c.expectedRejection() != null) {
                    assertTrue(c.id(), raising.stream().anyMatch(p -> p.code().equals(c.expectedRejection())));
                    rejected++;
                    continue;
                }
                assertTrue(c.id() + raising, raising.isEmpty());
                c.features().forEach((key, n) -> counts.merge(key, n, Integer::sum));
                // Check the rendered combinations as well as aggregate feature counts.
                if (text.contains("Bind(=arg)")) {
                    assertTrue(text, text.contains("Bind(R2)") && text.contains("CAPTURE(w, Bind(R2))")
                            && text.contains("LOOKAHEAD(") && text.contains("SAME_AS(w)"));
                }
                if (text.contains("@comment:")) comments.add(text.contains("javaStyle") ? "javaStyle"
                        : text.contains("@whitespace: none") ? "none" : "comment");
                if (text.contains("@autoCommit")) {
                    int commits = org.unlaxer.dsl.bootstrap.AutoCommit.lint(text).stream()
                            .filter(org.unlaxer.dsl.bootstrap.AutoCommit.Finding::inserted).mapToInt(f -> f.commits().size()).sum();
                    boolean nullablePrefix = text.contains("['k'] R2");
                    assertEquals(c.id(), nullablePrefix ? 1 : 2, commits);
                    inserted += commits;
                    if (nullablePrefix) nullablePrefixes++;
                }
            }
        }
        for (String family : List.of("value-owned-lookahead-memo", "comment-frontier-stack",
                "autoCommit-nullable-raising", "commit-label-catch"))
            assertEquals(family, Integer.valueOf(6), counts.get("combination:" + family));
        for (String syntax : List.of("(=arg)", "CAPTURE(", "SAME_AS(", "LOOKAHEAD(", "@comment:",
                "@autoCommit", "COMMIT(", "LABEL(", "CATCH("))
            assertTrue("missing rendered syntax: " + syntax, counts.getOrDefault("syntax:" + syntax, 0) >= 6);
        assertEquals(java.util.Set.of("javaStyle", "none", "comment"), comments);
        assertEquals(6, rejected);
        assertEquals(9, inserted);
        assertEquals(3, nullablePrefixes);
        System.out.println("[pbt] combination coverage " + counts + " inserted=" + inserted + " raisingRejections=" + rejected);
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
     * #739: inputs above {@link ParityHarness#STEP_BUDGET} finish in bounded time and exactly the
     * declared ones are left unobserved, both as corpus inputs and on the reserve path where seed 81
     * hung. Without the budget the Java acceptance check of a reserve derivation does not return, and
     * an observed over-budget input stops the Rust probe, so this test times out or fails.
     */
    @Test(timeout = 300_000)
    public void overBudgetInputsFinishInBoundedTime() throws Exception {
        assumeTrue(Boolean.getBoolean("rustConformance"));
        var cases = new ArrayList<>(corpusCases().stream().filter(c -> !c.overBudget().isEmpty()).toList());
        assertFalse("the corpus must keep an over-budget regression", cases.isEmpty());
        var regression = cases.stream().filter(c -> c.id().equals("step-budget-capture-mutual-recursion")).findFirst().orElseThrow();
        String over = regression.overBudget().get(0);
        // Reserve groups as built for generated seeds: an over-budget derivation, an accepted
        // derivation with an over-budget mutation (both skipped), then a group that fits.
        cases.add(new ParityHarness.Case(regression.id() + "-reserve", regression.grammar(), List.of(), Map.of("corpus", 1), null,
                List.of(List.of(over, ","), List.of(",", over), List.of("x,x,,", "x,x,")), List.of(over)));
        var stats = new ParityHarness.Stats();
        var findings = new ParityHarness(repo, temporary.getRoot().toPath()).evaluate(cases, stats);
        assertTrue(findings.toString(), findings.isEmpty());
        assertEquals("both frontends accept the grammars", 0, stats.rejected);
        assertEquals(cases.stream().mapToInt(c -> c.overBudget().size()).sum(), stats.overBudget);
        int expected = 2; // the fitting reserve group
        for (var c : cases) if (c.reserve().isEmpty()) expected += c.inputs().size() - c.overBudget().size();
        assertEquals("within-budget inputs are still observed", expected, stats.inputs);
    }

    /**
     * #739 review: the deadline of a child process covers its standard input. The child reads
     * 8,192 bytes, reports progress and stops reading; the rest of the input must not keep
     * {@code execute} past its timeout, and the progress so far stays in the result.
     */
    @Test(timeout = 60_000)
    public void executeTimeoutCoversAChildThatStopsReading() throws Exception {
        String child = "dd bs=8192 count=1 of=/dev/null 2>/dev/null; printf '#\\t7\\t78\\n'; exec sleep 30";
        long started = System.nanoTime();
        var result = ParityHarness.execute(temporary.getRoot().toPath(), List.of("sh", "-c", child), "x".repeat(1 << 20), 1);
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertEquals(result.output(), -1, result.code());
        assertTrue(result.output(), result.output().startsWith("#\t7\t78\n"));
        assertTrue(result.output(), result.output().endsWith("timeout after 1s: [sh, -c, " + child + "]"));
        assertTrue("returned after " + millis + " ms", millis < 10_000);
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
        // Separate mode and Random instances: preserve every legacy grammar/input/reserve draw.
        var combinations = new ArrayList<ParityHarness.Case>();
        for (long seed = first; seed < first + grammars; seed++)
            combinations.addAll(new GrammarGenerator(seed, GrammarGenerator.Limits.CI).featureCombinations());
        cases.addAll(combinations);
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
            var source = (ParityHarness.Case) p[1];
            if ((model != null || finding.caseId().startsWith("combination-"))
                    && finding.input() != null && !Boolean.getBoolean("pbt.noShrink")
                    && shrunk < shrinkLimit && System.nanoTime() < shrinkDeadline) {
                var shrinker = new Shrinker(harness, known, shrinkDeadline);
                entry = model == null ? shrinker.shrink(finding, source, entry) : shrinker.shrink(finding, model, entry);
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
        assertCombinationPaths(combinations, stats);
        if (grammars >= MIN_COVERAGE_GRAMMARS) {
            assertTrue("most legacy grammars must be accepted", stats.rejected * 4 <= stats.grammars - combinations.size());
            assertEquals("all left-recursive and raising variants must be rejected by both frontends",
                    cases.stream().filter(c -> c.expectedRejection() != null).count(), (long) stats.rejectedAsExpected);
            // Corpus inputs do not count: generated inputs alone must reach accepted parses (AST, CST).
            long legacyAccepting = stats.evidence.stream().map(s -> JsonParser.parseString(s).getAsJsonObject())
                    .filter(r -> r.get("case").getAsString().matches("seed-\\d+") && r.has("java")
                            && r.getAsJsonObject("java").get("diag").isJsonNull())
                    .map(r -> r.get("case").getAsString()).distinct().count();
            assertTrue("generated inputs must be accepted in at least " + MIN_ACCEPTING_SEEDS_PERCENT + "% of the legacy seeds: "
                            + legacyAccepting + "/" + grammars,
                    legacyAccepting * 100 >= (long) grammars * MIN_ACCEPTING_SEEDS_PERCENT);
        }
    }

    private static void assertCombinationPaths(List<ParityHarness.Case> combinations, ParityHarness.Stats stats) {
        var rows = stats.evidence.stream().map(s -> JsonParser.parseString(s).getAsJsonObject()).toList();
        for (var c : combinations) {
            if (c.expectedRejection() != null) continue;
            if (c.id().endsWith("value-owned-lookahead-memo")) {
                long[] hits = stats.memoHits.getOrDefault(c.id(), new long[4]);
                assertTrue(c.id() + " must replay success/failure memo on both hosts: " + java.util.Arrays.toString(hits),
                        hits[0] > 0 && hits[1] > 0 && hits[2] > 0 && hits[3] > 0);
            }
            var observed = rows.stream().filter(r -> r.get("case").getAsString().equals(c.id()) && r.has("java")).toList();
            for (String host : List.of("java", "rust")) {
                assertTrue(c.id() + " must accept on " + host, observed.stream()
                        .anyMatch(r -> r.getAsJsonObject(host).get("diag").isJsonNull()));
                assertTrue(c.id() + " must fail on " + host, observed.stream()
                        .anyMatch(r -> !r.getAsJsonObject(host).get("diag").isJsonNull()));
                if (c.id().endsWith("value-owned-lookahead-memo"))
                    assertTrue(c.id() + " must publish the owned value on " + host, observed.stream()
                            .anyMatch(r -> r.getAsJsonObject(host).get("diag").isJsonNull()
                                    && !r.getAsJsonObject(host).getAsJsonObject("owned").get("w").isJsonNull()));
                if (c.id().endsWith("autoCommit-nullable-raising") || c.id().endsWith("commit-label-catch"))
                    assertTrue(c.id() + " must reach a committed failure on " + host,
                            observed.stream().anyMatch(r -> diagnosticKind(r, host).equals("commit")));
                if (c.id().endsWith("commit-label-catch")) {
                    assertTrue(c.id() + " must reach an uncaught label on " + host,
                            observed.stream().anyMatch(r -> diagnosticKind(r, host).equals("label")));
                    assertTrue(c.id() + " must accept the caught-label alternative on " + host,
                            observed.stream().anyMatch(r -> r.get("input").getAsString().startsWith("q")
                                    && r.getAsJsonObject(host).get("diag").isJsonNull()));
                }
                if (c.id().endsWith("comment-frontier-stack"))
                    assertTrue(c.id() + " must fail after a comment with a nested rule stack on " + host,
                            observed.stream().anyMatch(r -> r.get("input").getAsString().contains("//")
                                    && r.getAsJsonObject(host).get("stack").getAsString().contains(">R2@")));
            }
        }
    }

    private static String diagnosticKind(JsonObject row, String host) {
        var diag = row.getAsJsonObject(host).get("diag");
        return diag.isJsonNull() ? "" : diag.getAsJsonObject().get("kind").getAsString();
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
