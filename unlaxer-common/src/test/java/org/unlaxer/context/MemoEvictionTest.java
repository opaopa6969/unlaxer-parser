package org.unlaxer.context;

import static org.junit.Assert.*;

import java.util.Optional;
import java.util.function.Supplier;
import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.combinator.LazyZeroOrMore;
import org.unlaxer.parser.elementary.WordParser;

/**
 * Bounding the memo live set below the retained window (#276 round 4).
 *
 * <p>Eviction may never change what a parse produces — a miss simply re-parses — and on the
 * grammars it is meant for it must not change the hit counts either. Both are pinned here, along
 * with the guard that widens the window when a probe does land below the watermark.
 */
public class MemoEvictionTest {

    /** Memoizable leaf: one entry per start position, probed twice per position below. */
    static final class Atom extends LazyChoice implements SafeSuccessMemoizable {
        @Override public Parsers getLazyParsers() { return new Parsers(new WordParser("a")); }
    }

    /** Fails its first alternative after the atom, so the second probe is always a memo hit. */
    static final class Item extends LazyChoice {
        final Parser atom = new Atom();
        @Override public Parsers getLazyParsers() {
            return new Parsers(new Chain(atom, new WordParser("z")), atom);
        }
    }

    static final class Items extends LazyZeroOrMore {
        final Parser item;
        Items(Parser item) { this.item = item; }
        @Override public Supplier<Parser> getLazyParser() { return () -> item; }
        @Override public Optional<Parser> getLazyTerminatorParser() { return Optional.empty(); }
    }

    private static ParseContext context(String source) {
        return ParseContext.withOptions(StringSource.createRootSource(source),
            ParseOptions.withMemoization(Memoization.SAFE_FAILURES));
    }

    private static String repeat(int count) { return "a".repeat(count); }

    @Test public void evictionKeepsEveryHitWhileTheEntryCountPlateaus() {
        String source = repeat(4000);
        Parser retained = new Items(new Item());
        Parser evicting = new Items(new Item());
        try (ParseContext off = context(source); ParseContext on = context(source)) {
            off.getPackratMemoTable().setEvictionEnabled(false);
            on.getPackratMemoTable().setWindow(64);
            assertTrue(retained.parse(off).isSucceeded());
            assertTrue(evicting.parse(on).isSucceeded());
            assertEquals(off.getConsumedPosition(), on.getConsumedPosition());
            assertEquals(off.getPackratMemoTable().successHits(), on.getPackratMemoTable().successHits());
            assertEquals(off.getPackratMemoTable().failureHits(), on.getPackratMemoTable().failureHits());
            assertEquals(0, on.getPackratMemoTable().evictionUnderruns());
            assertEquals(0, on.getPackratMemoTable().deadOnArrival());
            assertTrue(on.getPackratMemoTable().evictedCount() > 0);
            // The retained table grows with the input; the evicting one stops at the window.
            assertTrue(off.getPackratMemoTable().maxEntryCount() > 3000);
            assertTrue(on.getPackratMemoTable().maxEntryCount() < 1000);
        }
    }

    @Test public void aProbeBelowTheWatermarkWidensTheWindowInsteadOfLosingTheRest() {
        String source = repeat(2000);
        Parser atoms = new Items(new Atom());
        // The first alternative consumes everything and then fails, so the second re-parses from 0.
        Parser parser = new Choice(new Chain(atoms, new WordParser("z")), new Items(new Atom()));
        try (ParseContext context = context(source)) {
            context.getPackratMemoTable().setWindow(64);
            Parsed parsed = parser.parse(context);
            assertTrue(parsed.isSucceeded());
            assertEquals(2000, context.getConsumedPosition().value());
            assertTrue(context.getPackratMemoTable().evictionUnderruns() > 0);
            // Widened past twice the observed look-back, so the retry is memoized end to end.
            assertTrue(context.getPackratMemoTable().window() >= 4000);
        }
    }

    @Test public void anInputShorterThanTheWindowIsNeverEvicted() {
        String source = repeat(200);
        try (ParseContext context = context(source)) {
            assertTrue(new Items(new Item()).parse(context).isSucceeded());
            assertEquals(0, context.getPackratMemoTable().evictedCount());
            assertEquals(0, context.getPackratMemoTable().evictionUnderruns());
            assertEquals(context.getPackratMemoTable().maxEntryCount(),
                context.getPackratMemoTable().entryCount());
        }
    }
}
