package org.unlaxer.context;

import static org.junit.Assert.*;

import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.WordParser;

import java.nio.file.*;
import java.util.*;

/** Independent Java/Rust control-flow oracle: exceptions abort; Rust Err can be matched. */
public class OrdinaryExceptionOracleTest {
    private static Parser form(String name, Parser callback) {
        return switch (name) {
            case "chain" -> new Chain(callback);
            case "optional" -> new org.unlaxer.parser.combinator.Optional(callback);
            case "repeat" -> new ZeroOrMore(callback);
            case "choice" -> new Choice(callback, new WordParser("😀"));
            case "not" -> new Not(callback);
            case "match" -> new MatchOnly(callback);
            case "owned" -> new NamedCapture("outer", callback);
            case "scope" -> new CaptureScope(callback);
            case "swallow" ->
                    new Chain(
                            new NoneChildParser() {
                                @Override
                                public Parser createParser() {
                                    return this;
                                }

                                @Override
                                public Parsed parse(ParseContext c, TokenKind k, boolean inverse) {
                                    try {
                                        new Chain(callback).parse(c, k, inverse);
                                    } catch (RuntimeException ignored) {
                                    }
                                    return Parsed.SUCCEEDED;
                                }
                            });
            default -> throw new AssertionError(name);
        };
    }

    @Test
    public void sharedControlFlowAndRegisteredEffectsSurviveIndependentRetry() throws Exception {
        int count = 0;
        for (String line :
                Files.readAllLines(Path.of("../conformance/ordinary-exceptions/state.tsv"))) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] row = line.split("\t");
            for (var memo : Memoization.values())
                for (var diagnostic : ParseOptions.Diagnostics.values())
                    for (boolean manual : List.of(false, true))
                        for (boolean abandoned : List.of(false, true)) {
                            String label =
                                    String.join("/", row)
                                            + "/"
                                            + memo
                                            + "/"
                                            + diagnostic
                                            + "/"
                                            + manual
                                            + "/"
                                            + abandoned;
                            try (var c =
                                    ParseContext.withOptions(
                                            StringSource.createRootSource(row[9]),
                                            ParseOptions.withMemoization(memo)
                                                    .withDiagnostics(diagnostic))) {
                                int[] state = {7};
                                c.registerTransactionalState(
                                        () -> {
                                            int saved = state[0];
                                            return () -> state[0] = saved;
                                        });
                                var metadata = new PortableTokenMetadata(c);
                                var old =
                                        new NamedCapture("word", new WordParser("p"))
                                                .parse(c)
                                                .getRootToken();
                                var oldId = metadata.register(old);
                                metadata.putExtra(oldId, "label", "before");
                                RuntimeException original =
                                        new IllegalArgumentException("oracle child");
                                PortableTokenMetadata.NodeId[] fresh = {null};
                                Parser callback =
                                        new NoneChildParser() {
                                            @Override
                                            public Parser createParser() {
                                                return this;
                                            }

                                            @Override
                                            public Parsed parse(
                                                    ParseContext context,
                                                    TokenKind kind,
                                                    boolean inverse) {
                                                if (abandoned) {
                                                    context.startParse(
                                                            this, context, kind, inverse);
                                                    context.begin(this);
                                                }
                                                state[0] = 99;
                                                metadata.putExtra(oldId, "label", "during");
                                                var token =
                                                        new NamedCapture(
                                                                        "word",
                                                                        new WordParser("😀"))
                                                                .parse(context, kind, inverse)
                                                                .getRootToken();
                                                fresh[0] = metadata.register(token);
                                                metadata.putRelated(oldId, "temporary", fresh[0]);
                                                throw original;
                                            }
                                        };
                                Parser parser = form(row[0], callback);
                                if (manual) c.begin(parser);
                                var before = List.copyOf(c.getCurrent().getTokens());
                                assertEquals("exception", row[1]);
                                assertSame(
                                        label,
                                        original,
                                        assertThrows(
                                                RuntimeException.class, () -> parser.parse(c)));
                                assertEquals(label, Integer.parseInt(row[3]), c.position());
                                assertEquals(
                                        label,
                                        Integer.parseInt(row[3]),
                                        c.getMatchedPosition().value());
                                assertEquals(label, before, c.getCurrent().getTokens());
                                assertEquals(label, manual ? 2 : 1, c.getTokenStack().size());
                                assertEquals(label, 7, state[0]);
                                assertEquals(
                                        label,
                                        "before",
                                        metadata.info(oldId).orElseThrow().extra().get("label"));
                                assertEquals(label, "p", c.captured("word").orElseThrow().text());
                                assertTrue(label, c.captured("outer").isEmpty());
                                assertNotNull(label, fresh[0]);
                                assertTrue(label, metadata.info(fresh[0]).isEmpty());
                                assertTrue(
                                        label,
                                        metadata.info(oldId).orElseThrow().related().isEmpty());
                                assertTrue(label, new WordParser(row[5]).parse(c).isSucceeded());
                                assertEquals(label, Integer.parseInt(row[7]), c.position());
                                if (manual) {
                                    c.rollback(parser);
                                    assertEquals(label, 1, c.position());
                                }
                                count++;
                            }
                        }
        }
        assertEquals(216, count);
    }
}
