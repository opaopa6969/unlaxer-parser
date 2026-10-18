package org.unlaxer.context;

import static org.junit.Assert.*;

import org.junit.Test;
import org.unlaxer.TokenKind;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.WordParser;

import java.lang.reflect.Field;

/** White-box storage invariants complement the cross-language parse oracle. */
public class FailureMemoBudgetTest {
    private static final Parser PARSER = new WordParser("!");

    private static PackratMemoTable.PositionKey key(int position, long epoch) {
        return new PackratMemoTable.PositionKey(
                position, position, TokenKind.consumed, false, epoch);
    }

    private static PackratMemoTable.Entry entry(ParseContext.FailureDiagnostic diagnostic)
            throws Exception {
        var constructor =
                PackratMemoTable.Entry.class.getDeclaredConstructor(
                        ParseContext.FailureDiagnostic.class);
        constructor.setAccessible(true);
        return constructor.newInstance(diagnostic);
    }

    private static Object field(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }

    private static ParseContext.FailureDiagnostic events(int capacity) throws Exception {
        var diagnostic = new ParseContext.FailureDiagnostic();
        Field field = diagnostic.getClass().getDeclaredField("transactionEvents");
        field.setAccessible(true);
        field.set(diagnostic, new byte[capacity]);
        return diagnostic;
    }

    @Test
    public void replacementUnlinksOldPayloadAndCountsOnlyRetainedEntries() throws Exception {
        var table = new PackratMemoTable();
        table.setWindow(64);
        table.setFailureBudget(2, 32768, 2048);
        var old = entry(events(256));
        table.put(PARSER, key(0, 0), old);
        long oldCharge = table.failureRetainedBytes();
        var replacement = entry(new ParseContext.FailureDiagnostic());
        table.put(PARSER, key(0, 0), replacement);
        assertEquals(1, table.entryCount());
        assertEquals(1, table.failureEntryCount());
        assertEquals(512, table.failureRetainedBytes());
        assertTrue(table.failureRetainedBytes() < oldCharge);
        for (String link :
                new String[] {
                    "indexedKey", "indexedParser", "nextAtPosition", "previousAtPosition"
                })
            assertNull("old payload must be unreachable from the cache: " + link, field(old, link));
        table.put(
                PARSER, key(0, 0), replacement); // A public get/put of the same outcome is a no-op.
        assertEquals(1, table.entryCount());
        assertNull(field(replacement, "nextAtPosition"));
        var tooLarge = entry(events(4096));
        table.put(PARSER, key(0, 0), tooLarge);
        assertSame(replacement, table.get(PARSER, key(0, 0)));
        assertEquals(512, table.failureRetainedBytes());
        assertEquals(1, table.failureBudgetMisses());
        // Reusing the public Entry at a new key must not share intrusive index links.
        table.put(PARSER, key(0, 1), replacement);
        assertNotSame(replacement, table.get(PARSER, key(0, 1)));
        assertEquals(2, table.failureEntryCount());
        table.observeCursor(1024);
        assertEquals(0, table.entryCount());
        assertEquals(0, table.failureEntryCount());
        assertEquals(0, table.failureRetainedBytes());
        assertEquals(2, table.evictedCount());
        table.put(PARSER, key(1024, 2), entry(new ParseContext.FailureDiagnostic()));
        assertEquals(1, table.failureEntryCount());
        assertEquals(512, table.failureRetainedBytes());
    }

    @Test
    public void byteCapacityAndExtremePositionSafelyMissBeforeIndexAllocation() throws Exception {
        var table = new PackratMemoTable();
        table.setFailureBudget(100, 1024, 512);
        for (int n = 0; n < 3; n++)
            table.put(PARSER, key(0, n), entry(new ParseContext.FailureDiagnostic()));
        assertEquals(2, table.failureEntryCount());
        assertEquals(1024, table.failureRetainedBytes());
        assertEquals(1, table.failureBudgetMisses());
        var distant = new PackratMemoTable();
        distant.setFailureBudget(100, 32768, 512); // Payload capacity is available.
        Object index = field(distant, "entryChainByPosition");
        distant.put(PARSER, key(Integer.MAX_VALUE, 0), entry(new ParseContext.FailureDiagnostic()));
        distant.put(PARSER, key(1_000_000, 0), entry(new ParseContext.FailureDiagnostic()));
        assertSame(index, field(distant, "entryChainByPosition"));
        assertEquals(2, distant.failureBudgetMisses());
        assertEquals(0, distant.failureEntryCount());
    }

    @Test
    public void payloadChargeCoversCapacityStacksAndNamesWithoutInvokingParsers() throws Exception {
        var diagnostic = events(4096);
        assertEquals(512 + 16 + 4096, ParseContext.failureMemoCharge(diagnostic, 8192));
        assertEquals(1025, ParseContext.failureMemoCharge(diagnostic, 1024));
        var expected = new ParseContext.FailureDiagnostic();
        var calls = new int[1];
        Parser callback =
                new WordParser("a") {
                    @Override
                    public java.util.Optional<String> expectedDisplayText() {
                        calls[0]++;
                        throw new AssertionError("budget counting must not call parser callbacks");
                    }
                };
        expected.expected().addFailed(callback);
        for (int i = 0; i < 1024; i++) expected.expected().addFailed(new WordParser("x"));
        ParseContext.sealMemoDiagnostic(expected);
        assertTrue(ParseContext.failureMemoCharge(expected, 16384) > 8192);
        assertEquals(0, calls[0]);
        var stack = new ParseContext.FailureDiagnostic();
        for (int i = 0; i < 100; i++)
            stack.maxReachedStackElements =
                    new ParseContext.StackSnapshot(stack.maxReachedStackElements, PARSER, 0, 0, 0);
        assertEquals(512 + 100 * 64, ParseContext.failureMemoCharge(stack, 8192));
        var trials = new ParseContext.FailureDiagnostic();
        trials.trials()
                .add(new ParseFailureDiagnostics.TrialRecord("x".repeat(1000), 0, 0, false, 0));
        assertTrue(ParseContext.failureMemoCharge(trials, 8192) >= 512 + 2000);
        assertEquals(1025, ParseContext.failureMemoCharge(trials, 1024));
        var table = new PackratMemoTable();
        table.setFailureBudget(100, 16384, 1024);
        table.put(PARSER, key(0, 0), entry(diagnostic));
        table.put(PARSER, key(0, 1), entry(expected));
        table.put(PARSER, key(0, 2), entry(stack));
        table.put(PARSER, key(0, 3), entry(trials));
        assertEquals(0, table.failureEntryCount());
        assertEquals(0, table.failureRetainedBytes());
        assertEquals(4, table.failureBudgetMisses());
        assertEquals(0, calls[0]);
    }

    @Test
    public void restoredDiagnosticRetainsItsTrialCapacityChargeWithoutCopying() throws Exception {
        var diagnostic = new ParseContext.FailureDiagnostic();
        var trial = new ParseFailureDiagnostics.TrialRecord("name", 0, 0, false, 0);
        for (int i = 0; i < 4096; i++) diagnostic.trials().add(trial);
        Object originalList = diagnostic.trials();
        var restore =
                ParseContext.class.getDeclaredMethod(
                        "restoreFailureDiagnostic",
                        ParseContext.FailureDiagnostic.class,
                        ParseContext.FailureDiagnostic.class);
        restore.setAccessible(true);
        restore.invoke(null, diagnostic, new ParseContext.FailureDiagnostic());
        assertSame(originalList, diagnostic.trials());
        assertTrue(diagnostic.trials().isEmpty());
        assertEquals(512 + 128 + 16 * 4096, ParseContext.failureMemoCharge(diagnostic, 100000));
        var table = new PackratMemoTable();
        table.setFailureBudget(100, 100000, 1024);
        table.put(PARSER, key(0, 0), entry(diagnostic));
        assertEquals(0, table.failureEntryCount());
        assertEquals(1, table.failureBudgetMisses());
    }

    @Test
    public void invalidConfigurationUsesDefaultsAndZeroIsAnExplicitDisable() {
        for (String value :
                new String[] {null, "", "-1", "NaN", "-0", "+1", "1001", "9223372036854775808"})
            assertEquals(123, PackratMemoTable.configuredFailureBudget(value, 123, 1000));
        assertEquals(0, PackratMemoTable.configuredFailureBudget("0", 123, 1000));
        assertEquals(1000, PackratMemoTable.configuredFailureBudget("1000", 123, 1000));
        var table = new PackratMemoTable();
        assertThrows(IllegalArgumentException.class, () -> table.setFailureBudget(-1, 0, 0));
        assertThrows(
                IllegalArgumentException.class, () -> table.setFailureBudget(0, Long.MAX_VALUE, 0));
        assertThrows(
                IllegalArgumentException.class, () -> table.setFailureBudget(0, 0, Long.MAX_VALUE));
    }
}
