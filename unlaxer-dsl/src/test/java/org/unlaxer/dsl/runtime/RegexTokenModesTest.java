package org.unlaxer.dsl.runtime;

import static org.junit.Assert.*;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.*;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.clang.IdentifierParser;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.NumberParser;
import org.unlaxer.parser.elementary.SingleQuotedParser;
import org.unlaxer.parser.elementary.WordParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/** Independent classic-token oracle; deliberately distinct from pure PEG assertions. */
public class RegexTokenModesTest {
    private static String decode(String value) {
        return value.equals("~")
                ? ""
                : new String(HexFormat.of().parseHex(value), StandardCharsets.UTF_8);
    }

    private static final class RangeParser
            extends org.unlaxer.parser.elementary.SingleCharacterParser {
        @Override
        public boolean isMatch(int cp) {
            return cp >= 'a' && cp <= 'c';
        }
    }

    private static Parser word(String value) {
        return new WordParser(value);
    }

    private static Parser match(Parser child) {
        return new MatchOnly(child);
    }

    private static Parser consume(Parser child) {
        return new DoConsumePropagationStopper(child);
    }

    private static final class RegexParser extends RegexTokenParser {
        RegexParser(String p) {
            super(p);
        }
    }

    private static Parser parser(String scenario) {
        String[] parts = scenario.split("/", 2);
        Parser regex = new RegexParser(decode(parts[1]));
        return switch (parts[0]) {
            case "raw" -> regex;
            case "match" -> match(regex);
            case "consume" -> consume(regex);
            case "stopinvert" -> new InvertMatchPropagationStopper(regex);
            case "not" -> new NotPropagatableSource(regex);
            case "matchconsume" -> match(consume(regex));
            default -> throw new AssertionError(scenario);
        };
    }

    private static String signature(Token token) {
        String text = token.source.sourceAsString();
        return token.tokenKind
                + "@"
                + token.source.offsetFromRoot().value()
                + ":"
                + token.source.codePointLength().value()
                + ":"
                + (text.isEmpty()
                        ? "~"
                        : HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String signatures(List<Token> tokens) {
        return tokens.isEmpty()
                ? "~"
                : tokens.stream()
                        .map(RegexTokenModesTest::signature)
                        .collect(Collectors.joining(";"));
    }

    private static boolean atomic(Token token) {
        return token.parser instanceof RegexTokenParser
                || token.parser instanceof WordParser
                || token.parser instanceof NumberParser
                || token.parser instanceof IdentifierParser
                || token.parser instanceof SingleQuotedParser;
    }

    private static void leaves(Token token, List<Token> target) {
        if (atomic(token) || token.getOriginalChildren().isEmpty()) target.add(token);
        else for (Token child : token.getOriginalChildren()) leaves(child, target);
    }

    @Test
    public void sharedIndependentOracleFixesBothCursorsAndBothTokenViews() throws Exception {
        var rows =
                Files.readAllLines(Path.of("../spec-corpus/regex-integration/modes.tsv")).stream()
                        .filter(line -> !line.startsWith("#") && !line.isBlank())
                        .toList();
        assertEquals(109, rows.size());
        var failures = new ArrayList<String>();
        for (Memoization memo : Memoization.values()) {
            for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
                for (String row : rows) {
                    String[] c = row.split("\t", -1);
                    assertEquals(16, c.length);
                    String label = c[0] + "/" + memo + "/" + diagnostics;
                    try (var context =
                            ParseContext.withOptions(
                                    StringSource.createRootSource(decode(c[1])),
                                    ParseOptions.withMemoization(memo)
                                            .withDiagnostics(diagnostics))) {
                        context.getCurrent().setResetMatchedWithConsumed(false);
                        if (!c[5].equals("~"))
                            assertTrue(label, word(decode(c[5])).parse(context).isSucceeded());
                        if (!c[6].equals("~"))
                            assertTrue(
                                    label,
                                    word(decode(c[6]))
                                            .parse(context, TokenKind.matchOnly, false)
                                            .isSucceeded());
                        context.getCurrent()
                                .setResetMatchedWithConsumed(Boolean.parseBoolean(c[7]));
                        Parsed parsed =
                                parser(c[2])
                                        .parse(
                                                context,
                                                TokenKind.valueOf(c[3]),
                                                Boolean.parseBoolean(c[4]));
                        assertEquals(label, Boolean.parseBoolean(c[8]), parsed.isSucceeded());
                        assertEquals(
                                label,
                                Integer.parseInt(c[9]),
                                context.getConsumedPosition().value());
                        assertEquals(
                                label,
                                Integer.parseInt(c[10]),
                                context.getMatchedPosition().value());
                        var diagnostic = context.getParseFailureDiagnostics();
                        assertEquals(
                                label,
                                diagnostics == ParseOptions.Diagnostics.DETAILED_ON_FAILURE
                                        ? 0
                                        : Integer.parseInt(c[11]),
                                diagnostic.getFarthestOffset());
                        assertEquals(
                                label,
                                diagnostics != ParseOptions.Diagnostics.DETAILED_ON_FAILURE
                                        && Boolean.parseBoolean(c[12]),
                                !diagnostic.getExpectedTokens().isEmpty());
                        boolean builtin =
                                c[2].contains("number")
                                        || c[2].contains("identifier")
                                        || c[2].contains("quoted");
                        List<Token> originals =
                                builtin && parsed.isSucceeded()
                                        ? List.of(parsed.getRootToken())
                                        : parsed.getOriginalTokens();
                        assertEquals(label + "/original", c[13], signatures(originals));
                        assertEquals(
                                label + "/committed",
                                c[14],
                                signatures(context.getCurrent().getTokens()));
                        var leaves = new ArrayList<Token>();
                        for (Token token : context.getCurrent().getTokens()) leaves(token, leaves);
                        assertEquals(label + "/leaves", c[15], signatures(leaves));
                        assertEquals(label, 1, context.getTokenStack().size());
                    } catch (AssertionError failure) {
                        failures.add(failure.getMessage());
                    }
                }
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void regexUsesOriginalSourceAfterProjectedTokens() {
        try (var context = new ParseContext(StringSource.createRootSource("abc"))) {
            context.getCurrent().setResetMatchedWithConsumed(false);
            assertTrue(
                    new RegexParser("a").parse(context, TokenKind.matchOnly, false).isSucceeded());
            var projected =
                    new Chain(word("b"), word("b"))
                            .parse(context, TokenKind.virtualTokenConsumed, false);
            assertTrue(projected.isSucceeded());
            assertEquals("bb", projected.getRootToken().source.sourceAsString());
            assertEquals(2, context.getConsumedPosition().value());
            var parsed = new RegexParser("c").parse(context);
            assertTrue(parsed.isSucceeded());
            assertEquals("c", parsed.getRootToken().source.sourceAsString());
            assertEquals(2, parsed.getRootToken().source.offsetFromRoot().value());
            assertEquals(3, context.getConsumedPosition().value());
        }
    }
}
