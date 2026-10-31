package sample;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.junit.Test;
import org.unlaxer.CodePointLength;
import org.unlaxer.Parsed;
import org.unlaxer.Source;
import org.unlaxer.Source.SourceKind;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.Token.ChildrenKind;
import org.unlaxer.Token.ScanDirection;
import org.unlaxer.TokenKind;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.MatchOnly;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.AsciiParser;
import org.unlaxer.parser.referencer.MatchedTokenParser;
import org.unlaxer.util.Slicer;

/** Run the Usage003 palindrome recipes against the shared Java/Rust fixture (#1017). */
public class PalindromeSampleTest {

    private static final List<BiFunction<MatchOnly, MatchedTokenParser, Chain>> RECIPES = List.of(
        new Usage003_01_Palindrome()::sliceWithWord,
        new Usage003_01_Palindrome()::sliceWithSlicer,
        new Usage003_01_Palindrome()::effectReverse,
        new Usage003_01_Palindrome()::sliceReverse,
        new Usage003_01_Palindrome()::sliceReverseWithPythonian);

    @Test public void palindromeRecipesMatchSharedFixture() throws Exception {
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("..", "conformance", "samples", "palindrome.tsv"))) {
            if (line.startsWith("#") || line.isEmpty()) {
                continue;
            }
            String[] row = line.split("\t", -1);
            assertEquals(line, 7, row.length);
            rows.add(row);
        }
        assertEquals(26, rows.size());
        int observations = 0;
        for (int recipe = 0; recipe < RECIPES.size(); recipe++) {
            // One instance per recipe is reused by every row, mode and grammar position.
            MatchOnly wordLookahead = new MatchOnly(new OneOrMore(AsciiParser.class));
            Chain palindrome = RECIPES.get(recipe).apply(wordLookahead, new MatchedTokenParser(wordLookahead));
            WordParser separator = new WordParser("😀");
            WordParser prefix = new WordParser("a");
            Set<Parser> literals = Set.of(separator, prefix);
            Parser reuse = new Chain(palindrome, separator, palindrome);
            Parser retry = new Choice(new Chain(prefix, palindrome), palindrome);
            for (String[] row : rows) {
                Parser parser = switch (row[0]) {
                    case "single" -> palindrome;
                    case "reuse" -> reuse;
                    case "retry" -> retry;
                    default -> throw new AssertionError(row[0]);
                };
                String input = row[1].replace("\\r", "\r").replace("\\n", "\n");
                String label = recipe + " " + String.join("|", row);
                for (Memoization memo : Memoization.values()) {
                    for (ParseOptions.Diagnostics diagnostic : ParseOptions.Diagnostics.values()) {
                        StringSource root = StringSource.createRootSource(input);
                        try (ParseContext context = ParseContext.withOptions(root,
                                ParseOptions.withMemoization(memo).withDiagnostics(diagnostic))) {
                            Parsed parsed = parser.parse(context);
                            assertEquals(label, Boolean.parseBoolean(row[2]), parsed.isSucceeded());
                            assertEquals(label, Integer.parseInt(row[3]), context.getConsumedPosition().value());
                            assertEquals(label, Integer.parseInt(row[4]), context.getMatchedPosition().value());
                            String expectedSegments = recipe < 2 ? row[5] : row[6];
                            if (false == parsed.isSucceeded()) {
                                assertEquals(label, "-", expectedSegments);
                                continue;
                            }
                            String consumed = codePoints(input, 0, Integer.parseInt(row[3]));
                            assertEquals(label, consumed, parsed.getConsumed().source.sourceAsString());
                            assertEquals(label, expectedSegments, segments(parsed.getConsumed(), literals, root, input));
                            observations++;
                        }
                    }
                }
            }
        }
        // 5 recipes x 13 succeeding rows x 2 memo x 3 diagnostics
        assertEquals(390, observations);
    }

    /** Matched steps in source order; each is an input-backed slice of the root, even when empty. */
    private static String segments(Token root, Set<Parser> literals, Source rootSource, String input) {
        List<String> spans = new ArrayList<>();
        for (Token token : root.flatten(ScanDirection.Depth, ChildrenKind.original)) {
            if (false == token.parser instanceof WordParser || literals.contains(token.parser)) {
                continue;
            }
            assertEquals(TokenKind.consumed, token.tokenKind);
            assertEquals(SourceKind.subSource, token.source.sourceKind());
            assertSame(rootSource, token.source.root());
            int start = token.source.offsetFromRoot().value();
            int end = start + token.source.codePointLength().value();
            assertEquals(codePoints(input, start, end), token.source.sourceAsString());
            spans.add(start + "-" + end);
        }
        return spans.stream().collect(Collectors.joining(","));
    }

    private static String codePoints(String input, int start, int end) {
        return new String(input.codePoints().skip(start).limit(end - start).toArray(), 0, end - start);
    }

    @Test public void emptyProjectionMatchesZeroWidthForEachKindAndFailsInverted() {
        MatchOnly wordLookahead = new MatchOnly(new OneOrMore(AsciiParser.class));
        MatchedTokenParser emptyPivot = new MatchedTokenParser(wordLookahead).slice(Slicer::invalidate);
        for (TokenKind kind : List.of(TokenKind.consumed, TokenKind.matchOnly)) {
            for (boolean invert : new boolean[] {false, true}) {
                StringSource root = StringSource.createRootSource("aa");
                try (ParseContext context = new ParseContext(root)) {
                    assertTrue(wordLookahead.parse(context).isSucceeded());
                    assertEquals(0, context.getConsumedPosition().value());
                    assertEquals(2, context.getMatchedPosition().value());
                    int before = context.getCurrent().getTokens().size();
                    Parsed parsed = emptyPivot.parse(context, kind, invert);
                    assertEquals(kind + " " + invert, false == invert, parsed.isSucceeded());
                    assertEquals(0, context.getConsumedPosition().value());
                    assertEquals(2, context.getMatchedPosition().value());
                    assertEquals(before + (invert ? 0 : 1), context.getCurrent().getTokens().size());
                    if (false == invert) {
                        Token token = parsed.getRootToken();
                        assertEquals(kind, token.tokenKind);
                        assertTrue(token.source.isEmpty());
                        assertSame(root, token.source.root());
                        // consumed tokens sit at the consumed cursor, match-only tokens at the matched one
                        assertEquals(kind.isConsumed() ? 0 : 2, token.source.offsetFromRoot().value());
                    }
                }
            }
        }
    }

    @Test public void emptyWordParserStillNeverMatches() {
        // UBNF '' is WordParser(""): Rust JavaEmptyWord keeps the same never-matching contract.
        for (boolean invert : new boolean[] {false, true}) {
            try (ParseContext context = new ParseContext(StringSource.createRootSource("a"))) {
                assertFalse(new WordParser("").parse(context, TokenKind.consumed, invert).isSucceeded());
                assertEquals(0, context.getConsumedPosition().value());
            }
        }
    }

    @Test public void candidatesFollowTheCurrentTokenStackAcrossReuse() {
        MatchOnly wordLookahead = new MatchOnly(new OneOrMore(AsciiParser.class));
        MatchedTokenParser reversed = new MatchedTokenParser(wordLookahead).slice(slicer -> slicer.step(-1));
        Chain palindrome = new Chain(wordLookahead, reversed);
        try (ParseContext context = new ParseContext(StringSource.createRootSource("aba😀cdc"))) {
            assertTrue(new Chain(palindrome, new WordParser("😀"), palindrome).parse(context).isSucceeded());
            List<Token> candidates = context.getList(reversed, MatchedTokenParser.ScopeVariable.matchedToken.get(), Token.class);
            assertEquals(List.of("cdc"), candidates.stream().map(token -> token.source.sourceAsString()).toList());
            assertEquals(new CodePointLength(3), candidates.get(0).source.codePointLength());
        }
    }
}
