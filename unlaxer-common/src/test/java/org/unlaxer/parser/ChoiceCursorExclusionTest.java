package org.unlaxer.parser;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.ChoiceInterface;
import org.unlaxer.parser.combinator.ChoicePredictor;
import org.unlaxer.parser.combinator.PredictiveChoice;
import org.unlaxer.parser.combinator.NamedCapture;
import org.unlaxer.parser.combinator.Optional;
import org.unlaxer.parser.elementary.WordParser;

/** FIRST exclusion must inspect the cursor that an alternative's begin will publish (#861). */
public class ChoiceCursorExclusionTest {
    private record Observation(boolean succeeded, int consumed, int matched, boolean reset,
            Parser chosen, String selected, List<String> originalCst) {}

    private static void append(Token token, List<String> output) {
        String label = token.parser instanceof ChoiceInterface ? "Choice" : token.parser.getClass().getSimpleName();
        output.add(label + "/" + token.tokenKind + "@"
                + token.source.offsetFromRoot().value() + ":" + token.source.codePointLength().value()
                + "=" + token.source.sourceAsString());
        token.getOriginalChildren().forEach(child -> append(child, output));
    }

    private static Observation observe(Parser choice, String input, String prefix, String peek,
            TokenKind kind, boolean invert, boolean reset, Memoization memo,
            ParseOptions.Diagnostics diagnostics) {
        try (var context = ParseContext.withOptions(StringSource.createRootSource(input),
                ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
            context.getCurrent().setResetMatchedWithConsumed(false);
            assertTrue(new WordParser(prefix).parse(context).isSucceeded());
            assertTrue(new WordParser(peek).parse(context, TokenKind.matchOnly, false).isSucceeded());
            context.getCurrent().setResetMatchedWithConsumed(reset);
            if (diagnostics == ParseOptions.Diagnostics.DETAILED_ON_FAILURE) {
                assertEquals(!Boolean.getBoolean("unlaxer.parser.firstSetExclusion.disabled"),
                        context.isCandidateExclusionEnabled());
            }
            var parsed = choice.parse(context, kind, invert);
            var cst = new ArrayList<String>();
            context.getCurrent().getTokens().forEach(token -> append(token, cst));
            return new Observation(parsed.isSucceeded(), context.position(),
                    context.getMatchedPosition().value(), context.getCurrent().isResetMatchedWithConsumed(),
                    context.chosenParserByChoice.get(choice),
                    context.captured("selected").map(value -> value.text()).orElse(null), List.copyOf(cst));
        }
    }

    @Test public void resetBeforeAlternativeCannotChangeRecognitionAcrossDiagnosticPolicies() {
        for (String[] example : List.of(
                new String[]{"x\n", "x", "\n", "y"},
                new String[]{"x\ny", "x", "\n", "y"},
                new String[]{"😀\r\n界", "😀", "\r\n", "界"})) {
            var choice = new Choice(new WordParser(example[2]), new WordParser(example[3]));
            for (var memo : Memoization.values()) for (var kind : TokenKind.values()) {
                for (boolean invert : List.of(false, true)) for (boolean reset : List.of(false, true)) {
                    String label = example[0] + "/" + memo + "/" + kind + "/" + invert + "/" + reset;
                    var detailed = observe(choice, example[0], example[1], example[2], kind,
                            invert, reset, memo, ParseOptions.Diagnostics.DETAILED);
                    assertEquals(label, detailed, observe(choice, example[0], example[1], example[2], kind,
                            invert, reset, memo, ParseOptions.Diagnostics.AUTO));
                    assertEquals(label, detailed, observe(choice, example[0], example[1], example[2], kind,
                            invert, reset, memo, ParseOptions.Diagnostics.DETAILED_ON_FAILURE));
                    if (kind == TokenKind.matchOnly && !invert && reset) {
                        assertTrue(label, detailed.succeeded());
                        assertEquals(label, 1, detailed.consumed());
                        assertEquals(label, 1 + example[2].codePointCount(0, example[2].length()), detailed.matched());
                        assertSame(label, choice.getChildren().get(0), detailed.chosen());
                    }
                }
            }
        }
    }

    @Test public void predictiveChoiceCannotPreferANullableFallbackBecauseOfTheOldCursor() throws Exception {
        var rows = Files.readAllLines(Path.of("../conformance/choice-cursor/predictive.tsv"));
        assertEquals(4, rows.stream().filter(row -> !row.startsWith("#")).count());
        for (String row : rows) {
            if (row.startsWith("#")) continue;
            String[] c = row.split("\t");
            String input = new String(HexFormat.of().parseHex(c[0]), StandardCharsets.UTF_8);
            String prefix = new String(HexFormat.of().parseHex(c[1]), StandardCharsets.UTF_8);
            String peek = new String(HexFormat.of().parseHex(c[2]), StandardCharsets.UTF_8);
            String literalText = new String(HexFormat.of().parseHex(c[3]), StandardCharsets.UTF_8);
            boolean reset = Boolean.parseBoolean(c[4]);
            Parser literal = new NamedCapture("selected", new WordParser(literalText));
            Parser fallback = new Optional(new WordParser("Z"));
            Parser ordered = new Choice(literal, fallback);
            Parser predicted = new PredictiveChoice(List.of(ChoicePredictor.literal(literalText), ChoicePredictor.any()),
                    literal, fallback);
            for (var memo : Memoization.values()) for (var kind : List.of(TokenKind.matchOnly, TokenKind.virtualTokenMatchOnly)) {
                var expected = observe(ordered, input, prefix, peek, kind, false, reset, memo,
                        ParseOptions.Diagnostics.DETAILED);
                assertTrue(expected.succeeded());
                assertEquals(Integer.parseInt(c[5]), expected.consumed());
                assertEquals(Integer.parseInt(c[6]), expected.matched());
                assertSame(literal, expected.chosen());
                assertEquals(literalText, expected.selected());
                for (var diagnostics : ParseOptions.Diagnostics.values()) {
                    assertEquals(row + "/" + memo + "/" + kind + "/" + diagnostics, expected,
                            observe(predicted, input, prefix, peek, kind, false, reset, memo, diagnostics));
                }
            }
        }
    }
}
