package org.unlaxer.context;

import static org.junit.Assert.*;

import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.WordParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

/** Same owned-value/position/rollback oracle as Rust's public pure-success retry test. */
public class PureSuccessRollbackTest {
    static final class CountingWord extends WordParser {
        int calls;

        CountingWord(String text) {
            super(text);
        }

        @Override
        public Token getToken(ParseContext context, org.unlaxer.TokenKind kind, boolean invert) {
            calls++;
            return super.getToken(context, kind, invert);
        }
    }

    static final class PureChoice extends LazyChoice implements SafeSuccessMemoizable {
        final Parsers alternatives;

        PureChoice(Parser... alternatives) {
            this.alternatives = new Parsers(alternatives);
        }

        @Override
        public Parsers getLazyParsers() {
            return alternatives;
        }
    }

    private static String diagnostic(ParseContext context) {
        var d = context.getParseFailureDiagnostics();
        return List.of(d.getFarthestOffset(), d.getExpectedTokens(), d.getExpectedParsers())
                .toString();
    }

    @Test
    public void sharedOracleReplaysSuccessAndDoesNotRetainPortableIdsOrCallerMutation()
            throws Exception {
        for (String row :
                Files.readAllLines(Path.of("../conformance/pure-success-memo/rollback.tsv"))) {
            if (row.startsWith("#")) continue;
            var f = row.split("\t");
            String text =
                    f[1].equals("-")
                            ? ""
                            : new String(HexFormat.of().parseHex(f[1]), StandardCharsets.UTF_8);
            int end = Integer.parseInt(f[2]);
            assertEquals(end, text.codePointCount(0, text.length()));
            assertEquals(Integer.parseInt(f[3]), text.length());
            assertEquals(Integer.parseInt(f[4]), text.getBytes(StandardCharsets.UTF_8).length);
            for (ParseOptions.Diagnostics mode : ParseOptions.Diagnostics.values()) {
                String offDiagnostic = null;
                for (Memoization memo : Memoization.values()) {
                    var word = new CountingWord(text);
                    Parser child = text.isEmpty() ? new ZeroOrMore(word) : word;
                    var parser = new PureChoice(new Chain(word, new WordParser("!")), child);
                    try (var context =
                            ParseContext.withOptions(
                                    StringSource.createRootSource(text + "?"),
                                    ParseOptions.withMemoization(memo).withDiagnostics(mode))) {
                        var metadata = new PortableTokenMetadata(context);
                        context.getCurrent().setResetMatchedWithConsumed(false);
                        int afterWarm = -1;
                        var oldIds = new java.util.ArrayList<PortableTokenMetadata.NodeId>();
                        for (int attempt = 0; attempt < 3; attempt++) {
                            context.begin(parser);
                            assertTrue(row, parser.parse(context).isSucceeded());
                            assertEquals(end, context.getConsumedPosition().value());
                            assertEquals(end, context.getMatchedPosition().value());
                            Token token = context.getCurrent().getTokens().get(0);
                            assertEquals(text, token.source.sourceAsString());
                            assertEquals(0, token.source.offsetFromRoot().value());
                            assertEquals(end, token.source.codePointLength().value());
                            if (memo == Memoization.SAFE_FAILURES && attempt > 0) {
                                assertEquals(afterWarm, word.calls);
                                assertEquals(attempt, context.getPackratMemoTable().successHits());
                            }
                            afterWarm = word.calls;
                            var id = metadata.register(token);
                            for (var oldId : oldIds) {
                                assertNotSame(oldId, id);
                                assertFalse(
                                        "a new replay must not revive a rolled-back ID",
                                        metadata.info(oldId).isPresent());
                            }
                            assertTrue(metadata.info(id).orElseThrow().extra().isEmpty());
                            assertTrue(metadata.putExtra(id, "mutated", "outside memo"));
                            var retained = metadata.snapshot();
                            if (!text.isEmpty()) assertFalse(token.getOriginalChildren().isEmpty());
                            token.getOriginalChildren().clear();
                            token.filteredChildren.clear();
                            context.rollback(parser);
                            assertEquals(0, context.getConsumedPosition().value());
                            assertEquals(0, context.getMatchedPosition().value());
                            assertTrue(context.getCurrent().getTokens().isEmpty());
                            assertFalse(metadata.info(id).isPresent());
                            oldIds.add(id);
                            assertEquals(
                                    "outside memo",
                                    retained.info(id).orElseThrow().extra().get("mutated"));
                        }
                        if (memo == Memoization.OFF) offDiagnostic = diagnostic(context);
                        else assertEquals(row + "/" + mode, offDiagnostic, diagnostic(context));
                    }
                }
            }
        }
    }
}
