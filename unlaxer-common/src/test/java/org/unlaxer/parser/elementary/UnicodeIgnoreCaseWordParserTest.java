package org.unlaxer.parser.elementary;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.unlaxer.CodePointIndex;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.parser.FirstSets;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.MatchOnly;

public class UnicodeIgnoreCaseWordParserTest {
    private static Path fixture(String name) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("spec-corpus/case-insensitive"))) { root = root.getParent(); }
        if (root == null) { throw new AssertionError("fixture missing"); }
        return root.resolve("spec-corpus/case-insensitive/" + name);
    }
    private static String decode(String hex) {
        if (hex.equals("~")) { return ""; }
        StringBuilder result = new StringBuilder();
        for (String cp : hex.split(" ")) { result.appendCodePoint(Integer.parseInt(cp, 16)); }
        return result.toString();
    }
    @Test public void everyScalarMatchesOfficialUnicode17SimpleMappings() throws Exception {
        int[] upper = new int[0x110000], lower = new int[0x110000];
        Arrays.setAll(upper, index -> index);
        Arrays.setAll(lower, index -> index);
        for (String row : Files.readAllLines(fixture("unicode-17.0.0/UnicodeData.txt"))) {
            String[] fields = row.split(";", -1);
            int cp = Integer.parseInt(fields[0], 16);
            if (!fields[12].isEmpty()) { upper[cp] = Integer.parseInt(fields[12], 16); }
            if (!fields[13].isEmpty()) { lower[cp] = Integer.parseInt(fields[13], 16); }
        }
        for (int cp = 0; cp <= 0x10ffff; cp++) {
            if (cp >= 0xd800 && cp <= 0xdfff) { continue; }
            int actual = UnicodeCaseData.key(cp);
            assertEquals(lower[upper[cp]], actual);
            assertEquals(actual, UnicodeCaseData.key(actual));
        }
        assertEquals("17.0.0", UnicodeIgnoreCaseWordParser.UNICODE_VERSION);
    }
    @Test public void sharedOriginalTextSpansRollbackLookaheadAndFirstSets() throws Exception {
        for (var memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES)) {
            for (var diagnostics : ParseOptions.Diagnostics.values()) {
                for (String row : Files.readAllLines(fixture("runtime.tsv"))) {
                    if (row.startsWith("#")) { continue; }
                    String[] fields = row.split("\t");
                    String input = decode(fields[2]);
                    var ci = new UnicodeIgnoreCaseWordParser(decode(fields[1]));
                    Parser parser = switch (fields[3]) {
                        case "ATOM" -> ci;
                        case "CHAIN" -> new Chain(ci);
                        case "FAIL" -> new Chain(ci, new WordParser("!"));
                        case "RETRY" -> new Choice(new Chain(ci, new WordParser("!")), ci);
                        case "AHEAD_CI" -> new Chain(new MatchOnly(ci), ci);
                        case "PREFIX" -> new Chain(new WildCardCharacterParser(), ci);
                        default -> throw new AssertionError(fields[3]);
                    };
                    try (var context = ParseContext.withOptions(StringSource.createRootSource(input), ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                        var result = parser.parse(context);
                        int expected = Integer.parseInt(fields[5]);
                        assertEquals(fields[0], Boolean.parseBoolean(fields[4]), result.isSucceeded());
                        assertEquals(fields[0], expected, context.getConsumedPosition().value());
                        assertEquals(fields[0], expected, context.getMatchedPosition().value());
                        if (result.isSucceeded()) {
                            var token = result.getRootToken();
                            assertEquals(fields[0], 0, token.source.offsetFromRoot().value());
                            assertEquals(fields[0], expected, token.source.codePointLength().value());
                            assertEquals(fields[0], input.substring(0, input.offsetByCodePoints(0, expected)), token.source.toString());
                        } else {
                            assertTrue(context.getCurrent().getTokens().isEmpty());
                            if (!input.isEmpty()) { assertTrue(new WildCardCharacterParser().parse(context).isSucceeded()); }
                        }
                    }
                }
            }
        }
    }
    @Test public void legacyFirstSetsCannotExcludeInverseCaseEquivalents() {
        for (String[] pair : new String[][] {{"k", "K"}, {"i", "İ"}, {"i", "ı"}, {"s", "ſ"}, {"σ", "ς"}}) {
            for (WordParser word : List.of(new IgnoreCaseWordParser(pair[0]), new WordParser(pair[0], true), new UnicodeIgnoreCaseWordParser(pair[0]))) {
                assertTrue(FirstSets.of(word).mayStartWith(pair[1].codePointAt(0)));
                for (var diagnostics : ParseOptions.Diagnostics.values()) {
                    for (var memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES)) {
                        try (var context = ParseContext.withOptions(StringSource.createRootSource(pair[1]), ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                            assertTrue(new Chain(word).parse(context).isSucceeded());
                            assertEquals(1, context.getConsumedPosition().value());
                        }
                    }
                }
            }
        }
    }
    @Test public void invertedAndMatchOnlyKeepWordParserCursorContract() {
        for (String input : List.of("K", "x")) {
            for (boolean inverted : List.of(false, true)) {
                for (TokenKind kind : List.of(TokenKind.consumed, TokenKind.matchOnly)) {
                    try (var context = new ParseContext(StringSource.createRootSource(input))) {
                        boolean accepted = input.equals("K") != inverted;
                        var parsed = new UnicodeIgnoreCaseWordParser("k").parse(context, kind, inverted);
                        assertEquals(accepted, parsed.isSucceeded());
                        assertEquals(accepted && kind.isConsumed() ? 1 : 0, context.getConsumedPosition().value());
                        assertEquals(accepted ? 1 : 0, context.getMatchedPosition().value());
                        if (accepted) { assertEquals(input, parsed.getRootToken().source.toString()); }
                    }
                }
            }
        }
    }
    @Test public void derivedWordsKeepPinnedSemanticsAndMalformedUtf16DoesNotMatch() {
        var parser = new UnicodeIgnoreCaseWordParser("Ꟑ");
        for (WordParser derived : List.of(parser.effect(source -> source), parser.slice(slicer -> {}),
                parser.slice(source -> new CodePointIndex(0), source -> new CodePointIndex(1)),
                parser.slice(source -> new CodePointIndex(0), source -> new CodePointIndex(1), false))) {
            assertTrue(derived instanceof UnicodeIgnoreCaseWordParser);
            try (var context = new ParseContext(StringSource.createRootSource("ꟑ"))) { assertTrue(derived.parse(context).isSucceeded()); }
        }
        for (String malformed : List.of("\ud800", "\udc00", "x\ud800")) {
            try (var context = new ParseContext(StringSource.createRootSource(malformed))) {
                assertFalse(new UnicodeIgnoreCaseWordParser(malformed).parse(context).isSucceeded());
                assertEquals(0, context.getConsumedPosition().value());
            }
        }
    }
    @Test public void longWordsScanOnce() {
        String input = "K𐐨".repeat(10_000);
        try (var context = new ParseContext(StringSource.createRootSource(input))) {
            assertTrue(new UnicodeIgnoreCaseWordParser("k𐐀".repeat(10_000)).parse(context).isSucceeded());
            assertEquals(20_000, context.getConsumedPosition().value());
        }
    }
}
