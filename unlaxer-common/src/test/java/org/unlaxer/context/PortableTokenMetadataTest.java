package org.unlaxer.context;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import java.util.List;
import java.util.function.Predicate;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.DoConsumePropagationStopper;
import org.unlaxer.parser.combinator.InvertMatchPropagationStopper;
import org.unlaxer.parser.combinator.MatchOnly;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.NotPropagatableSource;
import org.unlaxer.parser.elementary.WordParser;

public class PortableTokenMetadataTest {
    private static String text(String s) { return s.equals("~") ? "" : s.replace("\\r", "\r").replace("\\n", "\n"); }
    private static List<Token> words(Token token) {
        var words = new java.util.ArrayList<Token>();
        if (token.parser instanceof WordParser) words.add(token);
        else for (Token child : token.getOriginalChildren()) words.addAll(words(child));
        return words;
    }
    private static Parser wrap(Parser word, String wrapper) {
        return switch (wrapper) {
            case "sequence" -> new Chain(word, new WordParser("b"));
            case "choice" -> new Choice(word, new WordParser("b"));
            case "choice-rollback" -> new Choice(new Chain(word, new WordParser("x")), new Chain(word, new WordParser("b")));
            case "consume" -> new DoConsumePropagationStopper(word);
            case "invert" -> new InvertMatchPropagationStopper(word);
            case "not" -> new NotPropagatableSource(word);
            case "double-not" -> new NotPropagatableSource(new NotPropagatableSource(word));
            case "not-stop-not" -> new NotPropagatableSource(new InvertMatchPropagationStopper(new NotPropagatableSource(word)));
            default -> word;
        };
    }

    @Test public void sharedWordOracleFixesKindsCursorsSourceAndStopperBoundaries() throws Exception {
        Path fixture = Path.of("../conformance/token-metadata/words.tsv");
        assertEquals(32, Files.readAllLines(fixture).stream().filter(line -> !line.startsWith("#")).count());
        for (Memoization memo : Memoization.values()) {
            for (ParseOptions.Diagnostics diagnostics : new ParseOptions.Diagnostics[] {
                    ParseOptions.Diagnostics.DETAILED, ParseOptions.Diagnostics.DETAILED_ON_FAILURE }) {
                for (String line : Files.readAllLines(fixture)) {
                    if (line.startsWith("#")) continue;
                    String[] c = line.split("\t", -1);
                    assertEquals(16, c.length);
                    try (var context = ParseContext.withOptions(StringSource.createRootSource(text(c[1])),
                            ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                        // Explicit independent-cursor mode, also used by the Rust API.
                        context.getCurrent().setResetMatchedWithConsumed(false);
                        if (c[6].equals("consume-a")) assertTrue(new WordParser("a").parse(context).isSucceeded());
                        if (c[6].equals("match-a")) assertTrue(new WordParser("a").parse(context, TokenKind.matchOnly, false).isSucceeded());
                        Parser word = new WordParser(text(c[2]));
                        Parser parser = wrap(word, c[5]);
                        context.begin(parser);
                        Parsed result = parser.parse(context, TokenKind.valueOf(c[3]), Boolean.parseBoolean(c[4]));
                        if (result.isSucceeded()) context.commit(parser, TokenKind.valueOf(c[3]));
                        else context.rollback(parser);
                        assertEquals(c[0], Boolean.parseBoolean(c[7]), result.isSucceeded());
                        assertEquals(c[0], Integer.parseInt(c[8]), context.getConsumedPosition().value());
                        assertEquals(c[0], Integer.parseInt(c[9]), context.getMatchedPosition().value());
                        if (result.isFailed()) {
                            assertEquals(c[0], diagnostics == ParseOptions.Diagnostics.DETAILED_ON_FAILURE ? 0 : Integer.parseInt(c[14]),
                                context.getParseFailureDiagnostics().getFarthestOffset());
                        }
                        if (result.isSucceeded()) {
                            List<Token> tokens = words(result.getRootToken());
                            assertEquals(c[0], c[15], tokens.stream().map(t -> t.source.offsetFromRoot().value() + ":" + (t.source.offsetFromRoot().value() + t.source.codePointLength().value())).collect(java.util.stream.Collectors.joining(";")));
                            Token token = tokens.get(0);
                            assertNotNull(c[0], token);
                            assertEquals(c[0], TokenKind.valueOf(c[10]), token.tokenKind);
                            assertEquals(c[0], Integer.parseInt(c[11]), token.source.offsetFromRoot().value());
                            assertEquals(c[0], Integer.parseInt(c[12]), token.source.offsetFromRoot().value() + token.source.codePointLength().value());
                            assertEquals(c[0], text(c[13]), token.source.toString());
                        }
                    }
                }
            }
        }
    }

    @Test public void portableRollbackRetentionAndHostObjectsRemainIndependent() throws Exception {
        assertEquals(3, Files.readAllLines(Path.of("../conformance/token-metadata/metadata.tsv")).stream().filter(line -> !line.startsWith("#")).count());
        for (String line : Files.readAllLines(Path.of("../conformance/token-metadata/metadata.tsv"))) {
            if (line.startsWith("#")) continue;
            String[] c = line.split("\t", -1);
            for (Memoization memo : Memoization.values()) {
                PortableTokenMetadata.Snapshot retained;
                PortableTokenMetadata.NodeId first;
                Token original;
                Object arbitrary = new Object();
                Name hostName = Name.of("host-object");
                try (var context = ParseContext.withOptions(StringSource.createRootSource(text(c[1])), ParseOptions.withMemoization(memo))) {
                    var metadata = new PortableTokenMetadata(context);
                    original = new WordParser(text(c[2])).parse(context).getRootToken();
                    original.putExtraObject(hostName, arbitrary);
                    first = metadata.register(original);
                    assertTrue(metadata.putExtra(first, "label", text(c[5])));
                    Token generated = new Token(TokenKind.valueOf(c[9]), StringSource.createDetachedSource(text(c[4])), new WordParser(""));
                    original.putRelatedToken(Name.of("host-related"), generated);
                    Parser collector = new Chain() {
                        @Override public Token collect(List<Token> tokens, TokenKind kind, Predicate<Token> filter) { return generated; }
                    };
                    assertSame(generated, collector.parse(context).getRootToken());
                    var second = metadata.registerGenerated(generated, Integer.parseInt(c[7]));
                    assertTrue(metadata.putRelated(first, "repair", second));
                    retained = metadata.snapshot();
                    assertEquals(Integer.parseInt(c[7]), context.getConsumedPosition().value());
                    assertEquals(Integer.parseInt(c[7]), context.getMatchedPosition().value());
                    Parser boundary = new Chain(new WordParser(text(c[3])));
                    context.begin(boundary);
                    assertTrue(metadata.putExtra(first, "label", "changed"));
                    assertTrue(metadata.removeRelated(first, "repair").isPresent());
                    var stale = metadata.register(new WordParser(text(c[3])).parse(context).getRootToken());
                    assertTrue(metadata.putRelated(first, "failed", stale));
                    context.rollback(boundary);
                    assertEquals(text(c[5]), metadata.info(first).orElseThrow().extra().get("label"));
                    assertSame(second, metadata.info(first).orElseThrow().related().get("repair"));
                    assertFalse(metadata.info(first).orElseThrow().related().containsKey("failed"));
                    assertFalse(metadata.info(stale).isPresent());
                    var replacement = metadata.register(new WordParser(text(c[3])).parse(context).getRootToken());
                    assertEquals(stale.index(), replacement.index());
                    assertFalse(metadata.putRelated(first, "stale", stale));
                    assertTrue(metadata.putExtra(first, "label", text(c[6])));
                    assertEquals(Integer.parseInt(c[8]), context.getConsumedPosition().value());
                    assertEquals(text(c[5]), retained.info(first).orElseThrow().extra().get("label"));
                    assertEquals(text(c[4]), retained.info(second).orElseThrow().source());
                    assertTrue(retained.info(second).orElseThrow().detached());
                    assertEquals(TokenKind.valueOf(c[9]), retained.info(second).orElseThrow().kind());
                    assertEquals(Integer.parseInt(c[7]), retained.info(second).orElseThrow().offset());
                    assertEquals(Integer.parseInt(c[7]), retained.info(second).orElseThrow().end());
                    assertEquals(0, retained.info(second).orElseThrow().sourceOffset());
                    assertSame(arbitrary, original.getExtraObject(hostName).orElseThrow());
                    assertSame(arbitrary, original.deepCopy().getExtraObject(hostName).orElseThrow());
                    assertSame(generated, original.getRelatedToken(Name.of("host-related")).orElseThrow());
                    assertSame(generated, original.deepCopy().getRelatedToken(Name.of("host-related")).orElseThrow());
                }
                assertEquals(text(c[5]), retained.info(first).orElseThrow().extra().get("label"));
                assertSame(arbitrary, original.getExtraObject(hostName).orElseThrow());
            }
        }
    }

    @Test public void actualLookaheadAndFailedChoiceRestorePortableState() {
        for (Memoization memo : Memoization.values()) {
            try (var context = ParseContext.withOptions(StringSource.createRootSource("a😀"), ParseOptions.withMemoization(memo))) {
                var metadata = new PortableTokenMetadata(context);
                Token first = new WordParser("a").parse(context).getRootToken();
                var id = metadata.register(first);
                metadata.putExtra(id, "label", "baseline");
                Parser mutates = new WordParser("😀") {
                    @Override public Token getToken(ParseContext context, TokenKind kind, boolean invert) {
                        Token token = super.getToken(context, kind, invert);
                        if (token.source.isPresent()) {
                            metadata.putExtra(id, "label", "temporary");
                            var child = metadata.register(token);
                            metadata.putRelated(id, "child", child);
                        }
                        return token;
                    }
                };
                assertTrue(new MatchOnly(mutates).parse(context).isSucceeded());
                assertEquals("baseline", metadata.info(id).orElseThrow().extra().get("label"));
                assertFalse(metadata.info(id).orElseThrow().related().containsKey("child"));
                assertEquals(1, context.getConsumedPosition().value());
                assertEquals(2, context.getMatchedPosition().value());
                Parser fallback = new Choice(new Chain(mutates, new WordParser("x")), new WordParser("😀"));
                assertTrue(fallback.parse(context).isSucceeded());
                assertEquals("baseline", metadata.info(id).orElseThrow().extra().get("label"));
                assertFalse(metadata.info(id).orElseThrow().related().containsKey("child"));
                assertEquals(2, context.getConsumedPosition().value());
            }
        }
    }

    @Test public void ownerAndGeneratedSourceValidationRejectInvalidReferences() {
        try (var context = new ParseContext(StringSource.createRootSource("a"));
             var other = new ParseContext(StringSource.createRootSource("a"))) {
            var metadata = new PortableTokenMetadata(context);
            var foreign = new PortableTokenMetadata(other);
            Token real = new WordParser("a").parse(context).getRootToken();
            var id = metadata.register(real);
            var otherId = foreign.register(new WordParser("a").parse(other).getRootToken());
            assertEquals(id.index(), otherId.index());
            assertFalse(metadata.putRelated(id, "foreign", otherId));
            assertFalse(metadata.putExtra(otherId, "foreign", "x"));
            assertThrows(IllegalArgumentException.class, () -> metadata.registerGenerated(real, 0));
            Token generated = new Token(TokenKind.virtualTokenConsumed, StringSource.createDetachedSource("x"), new WordParser(""));
            assertThrows(IllegalArgumentException.class, () -> metadata.registerGenerated(generated, 2));
            assertThrows(IllegalArgumentException.class, () -> metadata.registerGenerated(generated, -1));
            var marker = metadata.registerGenerated(generated, 1);
            assertTrue(metadata.putRelated(id, "marker", marker));
            metadata.putExtra(id, "kept", "value");
            var retained = metadata.snapshot();
            assertThrows(UnsupportedOperationException.class, () -> retained.info(id).orElseThrow().extra().put("x", "y"));
            assertThrows(UnsupportedOperationException.class, () -> retained.info(id).orElseThrow().related().clear());
        }
    }

}
