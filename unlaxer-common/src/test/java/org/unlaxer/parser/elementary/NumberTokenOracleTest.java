package org.unlaxer.parser.elementary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.context.SafeSuccessMemoizable;
import org.unlaxer.parser.GrammarRule;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.Optional;
import org.unlaxer.parser.combinator.PegLookahead;
import java.util.stream.Collectors;

/**
 * The Java side of the shared Number token oracle (#717). Rust reads the same rows in
 * {@code rust/unlaxer-runtime/tests/number_token.rs}. An incomplete exponent ({@code 2e},
 * {@code 2e+}) leaves the token at the mantissa, and the failure the diagnostic reports is
 * after the exponent marker and sign, where a digit was expected.
 *
 * <p>The rows are observed from the real {@link NumberParser}. To regenerate the file after
 * changing {@link #INPUTS}, run this test with {@code -Dnumber.oracle.write=true}; the normal
 * run fails when the file differs from what Java observes.
 */
public class NumberTokenOracleTest {
    private static final Path CASES = Path.of("../conformance/number-token/cases.tsv");
    private static final String HEADER = "# Java NumberParser oracle (#717): Chain(NumberParser, WordParser(\"a\")) and NumberParser"
            + " alone from CP0, the same for every memo/diagnostics/reset. failureCP is the farthest failure under AUTO/DETAILED"
            + " (0 under DETAILED_ON_FAILURE). Regenerate with NumberTokenOracleTest and -Dnumber.oracle.write=true.\n"
            + "# id\tinputHex\tkind\tinvert\tsuccess\tconsumedCP\tmatchedCP\tfailureCP\tnumberSuccess\tnumberConsumedCP\tnumberMatchedCP";

    /** id → input. Each input yields four rows: consumed/matchOnly × not inverted/inverted. */
    private static final Map<String, String> INPUTS = new LinkedHashMap<>();
    static {
        String[] pairs = {
            "e", "2e", "E", "2E", "e-plus", "2e+", "E-minus", "2E-", "decimal-e", "2.5e", "point-digits-e", ".5e",
            "digits-point-e", "2.e", "signed-e", "-2e", "signed-E-plus", "+2E+", "e-plus-a", "2e+a", "e-a", "2ea",
            "e-minus-x", "2e-x", "exponent-a", "2e1a", "signed-exponent-a", "2e+1a", "full-a", "2.5E-3a", "sign", "-",
            "sign-a", "-a", "sign-point-a", "+.a", "digits-point", "2.", "digits-point-a", "2.a", "point", ".",
            "point-a", ".a", "eof", "", "digits-a", "2a", "digits-acute", "42é", "e-acute", "2eé", "e-emoji", "2e😀",
            "e-crlf", "2e\r\n", "e-space-a", "2e a", "space-a", "2 a",
            // Added after the #751 review.
            "exponent-e", "1e1e", "e-plus-x", "1e+x", "fullwidth-digit-a", "２a", "e-fullwidth-digit", "2e２",
            "digit-combining-a", "2́a", "e-combining", "2é",
        };
        for (int i = 0; i < pairs.length; i += 2) INPUTS.put(pairs[i], pairs[i + 1]);
    }

    private static final List<String> KINDS = List.of("consumed", "matchOnly");

    private static String observe(String input, TokenKind kind, boolean invert, ParseOptions options, boolean reset) {
        String row;
        try (var context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
            context.getCurrent().setResetMatchedWithConsumed(reset);
            Parser parser = new Chain(new NumberParser(), new WordParser("a"));
            var parsed = parser.parse(context, kind, invert);
            int farthest = context.getParseFailureDiagnostics().getFarthestOffset();
            assertEquals(input, 1, context.getTokenStack().size());
            row = parsed.isSucceeded() + "\t" + context.getConsumedPosition().value() + "\t"
                    + context.getMatchedPosition().value() + "\t" + (parsed.isSucceeded() ? "~" : String.valueOf(farthest));
        }
        try (var context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
            context.getCurrent().setResetMatchedWithConsumed(reset);
            var parsed = new NumberParser().parse(context, kind, invert);
            row += "\t" + parsed.isSucceeded() + "\t" + context.getConsumedPosition().value() + "\t"
                    + context.getMatchedPosition().value();
        }
        return row;
    }

    /** The oracle rows as Java observes them under OFF / DETAILED without the cursor reset. */
    private static List<String> expectedRows() {
        var rows = new ArrayList<String>();
        var options = ParseOptions.withMemoization(Memoization.OFF).withDiagnostics(ParseOptions.Diagnostics.DETAILED);
        for (var entry : INPUTS.entrySet()) for (String kind : KINDS) for (boolean invert : List.of(false, true)) {
            String input = entry.getValue();
            rows.add(entry.getKey() + "-" + kind + "-" + invert + "\t"
                    + (input.isEmpty() ? "~" : HexFormat.of().formatHex(input.getBytes(StandardCharsets.UTF_8)))
                    + "\t" + kind + "\t" + invert + "\t" + observe(input, TokenKind.valueOf(kind), invert, options, false));
        }
        return rows;
    }

    @Test public void oracleFileIsWhatJavaObserves() throws Exception {
        var expected = expectedRows();
        if (Boolean.getBoolean("number.oracle.write")) {
            Files.writeString(CASES, HEADER + "\n" + String.join("\n", expected) + "\n");
        }
        var rows = Files.readAllLines(CASES).stream().filter(row -> !row.isBlank() && !row.startsWith("#")).toList();
        assertEquals("regenerate with -Dnumber.oracle.write=true", String.join("\n", expected), String.join("\n", rows));
    }

    @Test public void numberParserMatchesTheSharedOracle() throws Exception {
        var rows = Files.readAllLines(CASES).stream().filter(row -> !row.isBlank() && !row.startsWith("#")).toList();
        assertEquals(INPUTS.size() * 4, rows.size());
        var failures = new ArrayList<String>();
        for (var memo : Memoization.values()) for (var diagnostics : ParseOptions.Diagnostics.values()) {
            for (boolean reset : List.of(false, true)) for (String row : rows) {
                String[] c = row.split("\t", -1);
                assertEquals(row, 11, c.length);
                String label = c[0] + "/" + memo + "/" + diagnostics + "/reset=" + reset;
                String input = c[1].equals("~") ? "" : new String(HexFormat.of().parseHex(c[1]), StandardCharsets.UTF_8);
                var options = ParseOptions.withMemoization(memo).withDiagnostics(diagnostics);
                String[] actual = observe(input, TokenKind.valueOf(c[2]), Boolean.parseBoolean(c[3]), options, reset).split("\t", -1);
                // The low-level entry collects no failure under DETAILED_ON_FAILURE.
                String failure = diagnostics == ParseOptions.Diagnostics.DETAILED_ON_FAILURE && !c[7].equals("~") ? "0" : c[7];
                String[] expected = {c[4], c[5], c[6], failure, c[8], c[9], c[10]};
                if (!java.util.Arrays.equals(expected, actual))
                    failures.add(label + ": expected " + String.join(" ", expected) + " but was " + String.join(" ", actual));
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    private static final Path DIAGNOSTICS = Path.of("../conformance/number-token/diagnostics.tsv");
    private static final List<String> SHAPES = List.of("number", "not", "optional", "choice", "after-optional", "tail", "memo", "bare", "failure-memo");
    private static final List<String> DIAGNOSTIC_INPUTS = List.of(
            "2a", "2", "1,2e,a", "1e1e", "2e", "2e+", "2E-", "2.", "2.a", "2.5", ".5", ".5e",
            "-2", "+2.5E-3", "2e1", "2e+1", "2e+1a", "2e😀", "2́", "42é", "2e\r\n");

    private static final class NumberRule extends Chain implements GrammarRule,
            SafeSuccessMemoizable {
        private static final long serialVersionUID = 1L;
        private final String rule;
        NumberRule(String rule, Parser... children) { super(children); this.rule = rule; }
        @Override public String grammarRuleName() { return rule; }
    }

    private static Parser diagnosticParser(String shape) {
        if (shape.equals("bare")) return new NumberParser();
        Parser number = new NumberRule("Num", new NumberParser());
        Parser body = switch (shape) {
            case "number" -> number;
            case "not" -> new Chain(new PegLookahead(number, false), new WordParser("x"));
            case "failure-memo" -> new Choice(
                    new Chain(new PegLookahead(number, false), new WordParser("x")), number);
            case "optional" -> new Optional(number);
            case "choice" -> new Choice(new WordParser("x"), number);
            case "after-optional" -> new Chain(new Optional(new WordParser("x")), number);
            case "tail" -> new Chain(number, new Optional(new WordParser("e")));
            case "memo" -> new Chain(new PegLookahead(number, true),
                    new PegLookahead(number, true),
                    new PegLookahead(number, false));
            default -> throw new IllegalArgumentException(shape);
        };
        return new NumberRule("Root", body);
    }

    private static String diagnosticObservation(String shape, String input, ParseOptions options) {
        try (var context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
            boolean success = diagnosticParser(shape).parse(context).isSucceeded();
            if (shape.equals("memo") && options.memoization() == Memoization.SAFE_FAILURES)
                assertTrue("exercise successful memo replay", context.getPackratMemoTable().successHits() > 0);
            if (shape.equals("failure-memo") && options.memoization() == Memoization.SAFE_FAILURES)
                assertEquals("failure replay", 1,
                        context.getPackratMemoTable().failureHits());
            var diagnostic = context.getParseFailureDiagnostics();
            String expected = String.join(",", diagnostic.getExpectedParsers());
            String stack = context.getRuleStack().map(value -> value.frames().stream()
                    .map(frame -> frame.rule() + "@" + frame.start())
                    .collect(Collectors.joining(">")) + "@" + value.offset() + "/" + value.omitted()).orElse("~");
            return success + "\t" + context.getConsumedPosition().value() + "\t" + context.getMatchedPosition().value()
                    + "\t" + diagnostic.getFarthestOffset() + "\t" + (expected.isEmpty() ? "~" : expected) + "\t" + stack;
        }
    }

    private static List<String> diagnosticInputs(String shape) {
        if (shape.equals("failure-memo")) return List.of("-", "+");
        var inputs = new ArrayList<>(DIAGNOSTIC_INPUTS);
        if (shape.equals("bare")) inputs.addAll(List.of("", "-", "-a", "+.a", ".", ".a", "a", "e", "２"));
        return inputs;
    }

    @Test public void internalFailureDiagnosticsMatchTheSharedOracle() throws Exception {
        var rows = new ArrayList<String>();
        for (String shape : SHAPES) for (String input : diagnosticInputs(shape)) for (var memo : Memoization.values()) {
            var detailed = ParseOptions.withMemoization(memo).withDiagnostics(ParseOptions.Diagnostics.DETAILED);
            String observation = diagnosticObservation(shape, input, detailed);
            rows.add(shape + "\t" + (input.isEmpty() ? "~" : HexFormat.of().formatHex(input.getBytes(StandardCharsets.UTF_8)))
                    + "\t" + memo + "\t" + observation);
            for (var mode : ParseOptions.Diagnostics.values()) {
                String expected = observation;
                if (mode == ParseOptions.Diagnostics.DETAILED_ON_FAILURE) {
                    String[] columns = observation.split("\t");
                    expected = String.join("\t", columns[0], columns[1], columns[2], "0", "~", "~");
                }
                assertEquals(shape + "/" + input + "/" + memo + "/" + mode, expected,
                        diagnosticObservation(shape, input, ParseOptions.withMemoization(memo).withDiagnostics(mode)));
            }
        }
        if (Boolean.getBoolean("number.oracle.write")) {
            Files.writeString(DIAGNOSTICS, "# Java NumberParser internal failures (#752). Generated by NumberTokenOracleTest.\n"
                    + "# DETAILED observations; AUTO agrees. Direct DETAILED_ON_FAILURE has 0/~/~ diagnostics.\n"
                    + "# shape\tinputHex\tmemo\tsuccess\tconsumedCP\tmatchedCP\tfailureCP\texpectedParsers\truleStack\n"
                    + String.join("\n", rows) + "\n");
        }
        assertEquals(rows, Files.readAllLines(DIAGNOSTICS).stream().filter(row -> !row.startsWith("#")).toList());
    }
}
