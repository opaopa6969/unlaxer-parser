package org.unlaxer.dsl.runtime;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.PortabilityCheck;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.ParserGenerator;
import org.unlaxer.dsl.codegen.rust.*;
import org.unlaxer.dsl.codegen.rust.RegexCoreValidator;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Independent common corpus at both generator validation boundaries. */
public class RegexIntegrationConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static String decode(String s) {
        return s.equals("~") ? "" : new String(HexFormat.of().parseHex(s), StandardCharsets.UTF_8);
    }

    @Test
    public void javaHostValidatesTheCoreCorpusBeforeRustEmission() throws Exception {
        for (String row : Files.readAllLines(Path.of("../spec-corpus/regex-core/cases.tsv"))) {
            if (!row.startsWith("#")) RegexCoreValidator.validate(decode(row.split("\t")[1]));
        }
        for (String row : Files.readAllLines(Path.of("../spec-corpus/regex-core/invalid.tsv"))) {
            if (row.startsWith("#")) continue;
            String[] fields = row.split("\t");
            var error =
                    assertThrows(
                            fields[0],
                            RegexCoreValidator.Rejection.class,
                            () -> RegexCoreValidator.validate(decode(fields[1])));
            assertEquals(fields[0], fields[2], error.kind().toString());
        }
        assertEquals(
                RegexCoreValidator.Kind.ResourceLimit,
                assertThrows(
                                RegexCoreValidator.Rejection.class,
                                () ->
                                        RegexCoreValidator.validate(
                                                "(".repeat(5000) + "a" + ")".repeat(5000)))
                        .kind());
        assertEquals(
                RegexCoreValidator.Kind.ResourceLimit,
                assertThrows(
                                RegexCoreValidator.Rejection.class,
                                () -> RegexCoreValidator.validate("a".repeat(16385)))
                        .kind());
    }

    @Test
    public void unsupportedRustPatternDoesNotRestrictTheJavaTarget() {
        String source =
                "grammar Advanced { @package: regex.probe token T=REGEX('(?=a)a') @root"
                    + " @mapping(Value,params=[text]) Root ::= T @text; }";
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        assertNotNull(new ParserGenerator().generate(grammar));
        var error =
                assertThrows(
                        RegexCoreValidator.Rejection.class,
                        () -> new RustBackend().generate(grammar));
        assertEquals(RegexCoreValidator.Kind.Unsupported, error.kind());
        var report = PortabilityCheck.check(source);
        assertFalse(report.portable());
        assertEquals("P-REGEX-UNSUPPORTED", report.diagnostics().get(0).code());
        assertEquals("REGEX:Unsupported@2", report.diagnostics().get(0).subject());
        class Probe extends RegexTokenParser {
            Probe() {
                super("(?=a)a");
            }
        }
        try (var context =
                new org.unlaxer.context.ParseContext(
                        org.unlaxer.StringSource.createRootSource("ab"))) {
            assertTrue(new Probe().parse(context).isSucceeded());
            assertEquals(1, context.getConsumedPosition().value());
        }
        // Public normalized IR cannot bypass support validation either.
        assertThrows(RegexCoreValidator.Rejection.class, () -> new GrammarIR.RegexToken("(?=a)a"));
        assertThrows(RegexCoreValidator.Rejection.class, () -> new GrammarIR.RegexToken("["));
        String unused = source.replace("T @text", "'ok' @text");
        assertThrows(
                RegexCoreValidator.Rejection.class,
                () -> new RustBackend().generate(UBNFMapper.parse(unused).grammars().get(0)));
    }

    @Test
    public void bothValidatorsAgreeOnCompileOutcomeAndScalarRejectionSite() throws Exception {
        org.junit.Assume.assumeTrue(
                "requires -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        Path root = Path.of("..").toAbsolutePath().normalize(),
                work = temporary.newFolder().toPath();
        Path library = work.resolve("libunlaxer_runtime.rlib"),
                probe = work.resolve("probe.rs"),
                binary = work.resolve("probe");
        run(
                List.of(
                        "rustc",
                        "--edition=2021",
                        "--crate-type=rlib",
                        "--crate-name=unlaxer_runtime",
                        root.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),
                        "-o",
                        library.toString()),
                "");
        Files.copy(root.resolve("spec-corpus/regex-integration/validation-probe.rs.txt"), probe);
        run(
                List.of(
                        "rustc",
                        "--edition=2021",
                        probe.toString(),
                        "--extern",
                        "unlaxer_runtime=" + library,
                        "-o",
                        binary.toString()),
                "");
        var patterns = new ArrayList<String>();
        for (String file : List.of("cases.tsv", "invalid.tsv")) {
            for (String row : Files.readAllLines(root.resolve("spec-corpus/regex-core/" + file)))
                if (!row.startsWith("#")) patterns.add(decode(row.split("\t")[1]));
        }
        patterns.addAll(
                List.of(
                        "(".repeat(5000) + "a" + ")".repeat(5000),
                        "[".repeat(5000) + "a" + "]".repeat(5000),
                        "a".repeat(16385),
                        "\\p{}",
                        "(?i)*",
                        "(?x)a{ 1}"));
        var classText = new StringBuilder("[");
        for (int i = 0; i < 4000; i++) classText.appendCodePoint(0x1000 + i * 2);
        patterns.add(classText.append(']').toString());
        // Exhaustive short syntax checks exercise acceptance boundaries independently
        // of the curated supported patterns, including escaped/group/class punctuation.
        String alphabet = "a|()[]*+?{}\\.&-^$";
        for (int a = 0; a < alphabet.length(); a++) {
            patterns.add("" + alphabet.charAt(a));
            for (int b = 0; b < alphabet.length(); b++) {
                patterns.add("" + alphabet.charAt(a) + alphabet.charAt(b));
                for (int c = 0; c < alphabet.length(); c++)
                    patterns.add("" + alphabet.charAt(a) + alphabet.charAt(b) + alphabet.charAt(c));
            }
        }
        String input =
                String.join(
                                "\n",
                                patterns.stream()
                                        .map(
                                                p ->
                                                        p.isEmpty()
                                                                ? "~"
                                                                : HexFormat.of()
                                                                        .formatHex(
                                                                                p.getBytes(
                                                                                        StandardCharsets
                                                                                                .UTF_8)))
                                        .toList())
                        + "\n";
        var results = run(List.of(binary.toString()), input).lines().toList();
        assertEquals(patterns.size(), results.size());
        var report = new ArrayList<>(List.of("pattern_utf8\tcompile_result"));
        for (int i = 0; i < patterns.size(); i++) {
            String expected;
            try {
                RegexCoreValidator.validate(patterns.get(i));
                java.util.regex.Pattern.compile(patterns.get(i));
                expected = "supported";
            } catch (RegexCoreValidator.Rejection error) {
                expected = error.kind() + "@" + error.offset();
            }
            assertEquals(patterns.get(i), expected, results.get(i));
            report.add(
                    HexFormat.of().formatHex(patterns.get(i).getBytes(StandardCharsets.UTF_8))
                            + "\t"
                            + expected);
        }
        Files.write(Path.of("target/regex-validation-conformance.tsv"), report);
    }

    private String run(List<String> command, String input) throws Exception {
        Path output = temporary.newFile().toPath();
        var process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(command.toString(), process.waitFor(180, TimeUnit.SECONDS));
            String text = Files.readString(output);
            assertEquals(text, 0, process.exitValue());
            return text;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
