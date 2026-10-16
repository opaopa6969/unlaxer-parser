package org.unlaxer.dsl;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.context.*;
import org.unlaxer.dsl.runtime.LexicalExpression;
import org.unlaxer.dsl.runtime.LexicalTokenParser;

public class GeneralLexicalPropagationTest {
    @Test public void lexicalAtomsPreserveKindsAndUseEmptyInverseAssertions() throws Exception {
        var range = new LexicalExpression(LexicalExpression.Op.RANGE, "", 'a', 'c', List.of());
        var parser = new LexicalTokenParser("range", LexicalExpression.repeat(range, 1, -1));
        var rows = Files.readAllLines(Path.of("../conformance/token-propagation/lexical.tsv")).stream()
                .filter(row -> !row.startsWith("#")).toList();
        assertEquals(12, rows.size());
        for (Memoization memo : Memoization.values()) for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
            for (String row : rows) {
                String[] c = row.split("\t", -1);
                assertEquals("false", c[4]);
                String input = new String(HexFormat.of().parseHex(c[1]), StandardCharsets.UTF_8);
                try (var context = ParseContext.withOptions(StringSource.createRootSource(input),
                        ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                    var result = parser.parse(context, TokenKind.valueOf(c[2]), Boolean.parseBoolean(c[3]));
                    assertEquals(c[0], Boolean.parseBoolean(c[5]), result.isSucceeded());
                    assertEquals(c[0], Integer.parseInt(c[6]), context.getConsumedPosition().value());
                    assertEquals(c[0], Integer.parseInt(c[7]), context.getMatchedPosition().value());
                    if (result.isSucceeded()) {
                        var token = result.getRootToken();
                        assertEquals(TokenKind.valueOf(c[2]), token.tokenKind);
                        assertEquals(Integer.parseInt(c[8]), token.source.offsetFromRoot().value());
                        assertEquals(Integer.parseInt(c[9]), token.source.codePointLength().value());
                        assertEquals(c[10].equals("~") ? "" : c[10], HexFormat.of().formatHex(token.source.sourceAsString().getBytes(StandardCharsets.UTF_8)));
                    }
                }
            }
        }
    }

    @Test public void namedTriviaCollectionUsesAtomicLexicalInversion() throws Exception {
        var rows = Files.readAllLines(Path.of("../conformance/token-propagation/sequence-trivia.tsv")).stream()
                .filter(row -> row.startsWith("named-")).toList();
        assertEquals(16, rows.size());
        for (Memoization memo : Memoization.values()) for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
            for (String row : rows) {
                String[] c = row.split("\t", -1);
                var delimiter = new org.unlaxer.parser.combinator.ZeroOrMore(
                        new LexicalTokenParser("Whitespace", LexicalExpression.leaf(LexicalExpression.Op.LITERAL, "~")));
                org.unlaxer.parser.Parser first = new org.unlaxer.parser.elementary.WordParser("A");
                if (c[2].equals("named-stop-consume")) first = new org.unlaxer.parser.combinator.DoConsumePropagationStopper(first);
                var parser = new org.unlaxer.parser.combinator.Chain(delimiter, first, delimiter,
                        new org.unlaxer.parser.elementary.WordParser("B"), delimiter);
                String input = new String(HexFormat.of().parseHex(c[1]), StandardCharsets.UTF_8);
                try (var context = ParseContext.withOptions(StringSource.createRootSource(input),
                        ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                    context.getCurrent().setResetMatchedWithConsumed(false);
                    var result = parser.parse(context, TokenKind.valueOf(c[3]), Boolean.parseBoolean(c[4]));
                    assertEquals(c[0], Boolean.parseBoolean(c[7]), result.isSucceeded());
                    assertEquals(c[0], Integer.parseInt(c[8]), context.getConsumedPosition().value());
                    assertEquals(c[0], Integer.parseInt(c[9]), context.getMatchedPosition().value());
                    assertEquals(c[0], c[10], result.isSucceeded() ? signature(result.getRootToken()) : "~");
                    assertEquals(c[0], c[11], context.getCurrent().getTokens().isEmpty() ? "~" :
                            context.getCurrent().getTokens().stream().map(GeneralLexicalPropagationTest::signature)
                                .collect(java.util.stream.Collectors.joining(";")));
                    assertEquals(1, context.getTokenStack().size());
                }
            }
        }
    }
    private static String signature(org.unlaxer.Token token) {
        String text = token.source.sourceAsString();
        return token.tokenKind + "@" + token.source.offsetFromRoot().value() + ":" + token.source.codePointLength().value()
                + ":" + (text.isEmpty() ? "~" : HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8)));
    }
}
