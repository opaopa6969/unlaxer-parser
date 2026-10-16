package org.unlaxer.context;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.Optional;
import org.unlaxer.parser.combinator.PegLookahead;
import org.unlaxer.parser.elementary.EndOfSourceParser;
import org.unlaxer.parser.elementary.WordParser;

/** Shared independent resource outcomes; no parser-limit increase or retry bypass. */
public class MemoResourceDepthTest {
    private static Parser wrappers(Parser child, int depth) {
        for (int i = 0; i < depth; i++) child = new Optional(child);
        return child;
    }
    private static final class SafeRule extends LazyChain implements SafeSuccessMemoizable {
        private final Parsers children;
        SafeRule(boolean success) {
            children = new Parsers(wrappers(new WordParser("z"), 64),
                    success ? new EndOfSourceParser() : new PegLookahead(new EndOfSourceParser(), false));
        }
        @Override public Parsers getLazyParsers() { return children; }
    }
    @Test public void warmResultsNeverSkipResourceGuardsAndShallowerRetriesStillHit() throws Exception {
        // Explicit native stack headroom isolates the library's bounded parse depth.
        FutureTask<Void> task = new FutureTask<>(() -> { runCases(); return null; });
        new Thread(null, task, "memo-resource-depth", 2 * 1024 * 1024).start();
        task.get(60, TimeUnit.SECONDS);
    }
    private static void runCases() throws Exception {
        int observations = 0;
        for (String row : Files.readAllLines(Path.of("../conformance/memo-resource-depth/cases.tsv"))) {
            if (row.startsWith("#") || row.isBlank()) continue;
            String[] p = row.split("\t");
            boolean success = Boolean.parseBoolean(p[1]), resource = Boolean.parseBoolean(p[4]);
            int warmDepth = Integer.parseInt(p[2]), nextDepth = Integer.parseInt(p[3]);
            for (var memo : Memoization.values()) {
                for (var diagnostics : new ParseOptions.Diagnostics[]{ParseOptions.Diagnostics.DETAILED,
                        ParseOptions.Diagnostics.DETAILED_ON_FAILURE, ParseOptions.Diagnostics.AUTO}) {
                    SafeRule target = new SafeRule(success);
                    Parser warm = wrappers(target, warmDepth), retry = wrappers(target, nextDepth);
                    try (var context = ParseContext.withOptions(StringSource.createRootSource(""),
                            ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                        context.getCurrent().setResetMatchedWithConsumed(false);
                        context.beginPreservingMatched(target);
                        assertEquals(p[0] + "/warm", success || warmDepth > 0, warm.parse(context).isSucceeded());
                        context.rollback(target);
                        assertEquals(0, context.position());
                        assertEquals(0, context.matchedPosition());
                        if (resource) {
                            var failure = assertThrows(p[0], ParseLimitException.class, () -> retry.parse(context));
                            assertSame(failure, assertThrows(ParseLimitException.class, () -> target.parse(context)));
                            assertEquals(ParseContext.MAX_PARSE_DEPTH, failure.limit());
                            assertEquals(0, failure.offset());
                        } else {
                            assertEquals(p[0], Boolean.parseBoolean(p[5]), retry.parse(context).isSucceeded());
                        }
                        long hits = memo == Memoization.OFF ? 0 : success ? context.getPackratMemoTable().successHits()
                                : context.getPackratMemoTable().failureHits();
                        assertEquals(p[0] + "/" + memo + "/" + diagnostics,
                                memo == Memoization.OFF ? 0 : Integer.parseInt(p[6]), hits);
                        assertEquals(0, context.position());
                        assertEquals(0, context.matchedPosition());
                    }
                    observations++;
                }
            }
        }
        assertEquals(72, observations);
    }
}
