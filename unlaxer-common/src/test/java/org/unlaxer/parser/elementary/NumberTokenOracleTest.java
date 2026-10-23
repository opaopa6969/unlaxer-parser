package org.unlaxer.parser.elementary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.Chain;

/**
 * The Java side of the shared Number token oracle (#717). Rust reads the same rows in
 * {@code rust/unlaxer-runtime/tests/number_token.rs}. An incomplete exponent ({@code 2e},
 * {@code 2e+}) leaves the token at the mantissa, and the failure the diagnostic reports is
 * after the exponent marker and sign, where a digit was expected.
 */
public class NumberTokenOracleTest {
    private static final Path CASES = Path.of("../conformance/number-token/cases.tsv");

    @Test public void numberParserMatchesTheSharedOracle() throws Exception {
        var rows = Files.readAllLines(CASES).stream().filter(row -> !row.isBlank() && !row.startsWith("#")).toList();
        assertEquals(120, rows.size());
        var failures = new ArrayList<String>();
        for (var memo : Memoization.values()) for (var diagnostics : ParseOptions.Diagnostics.values()) {
            for (boolean reset : List.of(false, true)) for (String row : rows) {
                String[] c = row.split("\t", -1);
                assertEquals(row, 11, c.length);
                String label = c[0] + "/" + memo + "/" + diagnostics + "/reset=" + reset;
                String input = c[1].equals("~") ? "" : new String(HexFormat.of().parseHex(c[1]), StandardCharsets.UTF_8);
                var kind = TokenKind.valueOf(c[2]);
                boolean invert = Boolean.parseBoolean(c[3]);
                var options = ParseOptions.withMemoization(memo).withDiagnostics(diagnostics);
                try (var context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
                    context.getCurrent().setResetMatchedWithConsumed(reset);
                    Parser parser = new Chain(new NumberParser(), new WordParser("a"));
                    var parsed = parser.parse(context, kind, invert);
                    assertEquals(label, Boolean.parseBoolean(c[4]), parsed.isSucceeded());
                    assertEquals(label, Integer.parseInt(c[5]), context.getConsumedPosition().value());
                    assertEquals(label, Integer.parseInt(c[6]), context.getMatchedPosition().value());
                    if (!parsed.isSucceeded()) {
                        int expected = diagnostics == ParseOptions.Diagnostics.DETAILED_ON_FAILURE ? 0 : Integer.parseInt(c[7]);
                        assertEquals(label, expected, context.getParseFailureDiagnostics().getFarthestOffset());
                    }
                    assertEquals(label, 1, context.getTokenStack().size());
                } catch (AssertionError failure) {
                    failures.add(failure.getMessage());
                }
                try (var context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
                    context.getCurrent().setResetMatchedWithConsumed(reset);
                    var parsed = new NumberParser().parse(context, kind, invert);
                    assertEquals(label, Boolean.parseBoolean(c[8]), parsed.isSucceeded());
                    assertEquals(label, Integer.parseInt(c[9]), context.getConsumedPosition().value());
                    assertEquals(label, Integer.parseInt(c[10]), context.getMatchedPosition().value());
                } catch (AssertionError failure) {
                    failures.add(failure.getMessage());
                }
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }
}
