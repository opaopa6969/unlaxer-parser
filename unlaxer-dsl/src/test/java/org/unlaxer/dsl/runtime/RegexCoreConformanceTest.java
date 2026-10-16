package org.unlaxer.dsl.runtime;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Java21 public Pattern behavior and handwritten expectations, never Rust-produced snapshots. */
public class RegexCoreConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path CORPUS = ROOT.resolve("spec-corpus/regex-core");

    private static final class RegexParser extends RegexTokenParser {
        RegexParser(String pattern) {
            super(pattern);
        }
    }

    @Before
    public void java21Baseline() {
        assumeTrue("Regex core oracle requires Java21+", Runtime.version().feature() >= 21);
    }

    private List<String[]> rows(String file) throws Exception {
        return Files.readAllLines(CORPUS.resolve(file)).stream()
                .filter(s -> !s.startsWith("#"))
                .map(s -> s.split("\t", -1))
                .toList();
    }

    private String decode(String text) {
        return text.equals("~")
                ? ""
                : new String(HexFormat.of().parseHex(text), StandardCharsets.UTF_8);
    }

    private String hex(String text) {
        return text.isEmpty()
                ? "~"
                : HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8));
    }

    private String observation(Pattern pattern, String input) {
        var matcher = pattern.matcher(input);
        boolean found = matcher.lookingAt();
        String prefix = found ? input.substring(0, matcher.end()) : null;
        int cp = found ? prefix.codePointCount(0, prefix.length()) : 0;
        int token = !input.isEmpty() && cp > 0 ? cp : 0,
                inverted = !input.isEmpty() && !found ? 1 : 0;
        return (found ? "ok" : "none")
                + "\t"
                + cp
                + "\t"
                + (found ? prefix.length() : 0)
                + "\t"
                + (found ? prefix.getBytes(StandardCharsets.UTF_8).length : 0)
                + "\t"
                + (found ? hex(prefix) : "-")
                + "\t"
                + token
                + "\t"
                + inverted;
    }

    private String expected(String[] row) {
        return row[3].equals("-")
                ? "none\t0\t0\t0\t-\t" + row[7] + "\t" + row[8]
                : "ok\t" + row[4] + "\t" + row[5] + "\t" + row[6] + "\t" + row[3] + "\t" + row[7]
                        + "\t" + row[8];
    }

    @Test
    public void javaPatternAndExistingTokenParserAgreeWithIndependentCoreCases() throws Exception {
        assertTrue("Java21 baseline", Runtime.version().feature() >= 21);
        var report = new ArrayList<>(List.of("case\tpattern_utf8\tinput_utf8\tjava_observation"));
        for (var row : rows("cases.tsv")) {
            String pattern = decode(row[1]), input = decode(row[2]);
            assertEquals(row[0], expected(row), observation(Pattern.compile(pattern), input));
            for (TokenKind mode : new TokenKind[] {TokenKind.consumed, TokenKind.matchOnly})
                for (boolean invert : new boolean[] {false, true}) {
                    int progress = Integer.parseInt(row[invert ? 8 : 7]);
                    try (var context = new ParseContext(StringSource.createRootSource(input))) {
                        var parsed = new RegexParser(pattern).parse(context, mode, invert);
                        assertEquals(
                                row[0] + "/" + mode + "/" + invert,
                                progress > 0,
                                parsed.isSucceeded());
                        assertEquals(
                                row[0],
                                mode.isConsumed() ? progress : 0,
                                context.getConsumedPosition().value());
                        assertEquals(row[0], progress, context.getMatchedPosition().value());
                        if (progress > 0) {
                            var token = parsed.getRootToken();
                            assertEquals(0, token.source.offsetFromRoot().value());
                            assertEquals(
                                    input.substring(0, input.offsetByCodePoints(0, progress)),
                                    token.source.sourceAsString());
                        } else {
                            assertTrue(context.getCurrent().getTokens().isEmpty());
                        }
                    }
                }
            report.add(row[0] + "\t" + row[1] + "\t" + row[2] + "\t" + expected(row));
        }
        Files.write(Path.of("target/regex-core-java.tsv"), report);
    }

    @Test
    public void javaDistinguishesUnsupportedValidConstructsFromInvalidPatterns() throws Exception {
        for (var row : rows("invalid.tsv")) {
            String pattern = decode(row[1]);
            if (Boolean.parseBoolean(row[3])) assertNotNull(row[0], Pattern.compile(pattern));
            else assertThrows(row[0], PatternSyntaxException.class, () -> Pattern.compile(pattern));
        }
    }

    @Test
    public void standaloneRustRuntimeMatchesJavaAndTheSharedOracle() throws Exception {
        assumeTrue(
                "RegexCoreConformanceTest requires -DrustConformance=true",
                Boolean.getBoolean("rustConformance"));
        assertTrue("Java21 baseline", Runtime.version().feature() >= 21);
        Path work = temporary.newFolder().toPath();
        Path library = work.resolve("libunlaxer_runtime.rlib"),
                source = work.resolve("probe.rs"),
                binary = work.resolve("probe");
        run(
                List.of(
                        "rustc",
                        "--edition=2021",
                        "--crate-type=rlib",
                        "--crate-name",
                        "unlaxer_runtime",
                        ROOT.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),
                        "-o",
                        library.toString()),
                "");
        Files.writeString(source, Files.readString(CORPUS.resolve("probe.rs.txt")));
        run(
                List.of(
                        "rustc",
                        "--edition=2021",
                        source.toString(),
                        "--extern",
                        "unlaxer_runtime=" + library,
                        "-o",
                        binary.toString()),
                "");
        var queries = new ArrayList<String>();
        var expected = new ArrayList<String>();
        var labels = new ArrayList<String>();
        for (var row : rows("cases.tsv")) {
            queries.add(row[1] + "\t" + row[2]);
            expected.add(expected(row));
            labels.add(row[0]);
        }
        for (var row : rows("invalid.tsv")) {
            queries.add(row[1] + "\t~");
            expected.add("compile\t" + row[2]);
            labels.add(row[0]);
        }
        // Systematic short inputs exercise branching order and nullable repetition beyond
        // the handwritten corpus. Java computes this second oracle independently at runtime.
        var inputs =
                new ArrayList<>(
                        List.of(
                                "", "a", "b", "c", "aa", "ab", "ba", "bb", "aaa", "aab", "aba",
                                "abb", "baa", "bab", "bba", "bbb", "aaaa", "aaab", "aaba", "abab",
                                "baaa", "bbbb", "a!", "ab!", "\n", "\r\n", "a\n", "a\r\n", "😀",
                                "😀a", "é", "é"));
        var patterns =
                new LinkedHashSet<String>(
                        List.of(
                                "",
                                "^",
                                "$",
                                "\\A",
                                "\\z",
                                "\\Z",
                                "(?m)^",
                                "(?m)$",
                                "(?s).",
                                "(?d).",
                                "(?x)[a & & [ab]]",
                                "(?x)\\Q # \\E",
                                "(?x)#\\Q\na"));
        patterns.addAll(
                List.of(
                        "[^a&&b]",
                        "[^ab&&bc]",
                        "[^a-z&&b-d]",
                        "[a-z&&[^b]c]",
                        "[[a-c]&&[^b]]",
                        "[[a-c][d-f]]",
                        "[a-c&&b-dx]",
                        "[a&&\\&]",
                        "[&a]",
                        "[a-\\Qb\\E]",
                        "(?i)[^A&&a]",
                        "(?)",
                        "(?-)",
                        "(?i-)",
                        "(?-:a)",
                        "(?x)a{1, }",
                        "(?x)a{1, 2}",
                        "(?x)a{1 ,2}",
                        "(?x)a{1#x\n2}"));
        for (String atom :
                List.of(
                        "a",
                        "ab",
                        ".",
                        "[ab]",
                        "[^b]",
                        "(?:a|ab)",
                        "(?:ab|a)",
                        "(?:a|)",
                        "(?:|a)",
                        "(?:a?)",
                        "(?:a*)",
                        "(?:a|b)",
                        "(?:😀|a)")) {
            for (String quantity :
                    List.of(
                            "", "?", "*", "+", "??", "*?", "+?", "?+", "*+", "++", "{0}", "{1,2}",
                            "{1,2}?", "{1,2}+", "{2,}")) {
                String core = "(?:" + atom + ")" + quantity;
                patterns.add(core);
                patterns.add(core + "a");
                patterns.add(core + "b");
                patterns.add("(?>" + core + ")b");
                patterns.add("(?i:" + core + ")a");
            }
        }
        for (String pattern : patterns) {
            var java = Pattern.compile(pattern);
            for (String input : inputs) {
                queries.add(hex(pattern) + "\t" + hex(input));
                expected.add(observation(java, input));
                labels.add("differential:" + hex(pattern) + ":" + hex(input));
            }
        }
        List<String> actual =
                run(List.of(binary.toString()), String.join("\n", queries) + "\n").lines().toList();
        assertEquals(expected.size(), actual.size());
        var report = new ArrayList<>(List.of("case\tpattern_utf8\tinput_utf8\tobservation"));
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(labels.get(i), expected.get(i), actual.get(i));
            report.add(labels.get(i) + "\t" + queries.get(i) + "\t" + actual.get(i));
        }
        Files.write(Path.of("target/regex-core-conformance.tsv"), report);
    }

    private String run(List<String> command, String input) throws Exception {
        Path output = temporary.newFile().toPath();
        var process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        try {
            process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue(command.toString(), process.waitFor(180, TimeUnit.SECONDS));
            String text = Files.readString(output);
            assertEquals(command + "\n" + text, 0, process.exitValue());
            return text;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
