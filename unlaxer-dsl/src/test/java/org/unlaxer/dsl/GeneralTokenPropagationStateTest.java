package org.unlaxer.dsl;

import static org.junit.Assert.*;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.*;
import org.unlaxer.dsl.runtime.ScopeStore;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.WordParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/**
 * Existing ScopeStore semantics, independent of the Rust implementation and of generated fixtures.
 */
public class GeneralTokenPropagationStateTest {
    private static Parser word(String value) {
        return new WordParser(value);
    }

    private static Parser match(Parser child) {
        return new MatchOnly(child);
    }

    private static void mutate(ParseContext context) {
        ScopeStore.enter(context);
        ScopeStore.declare(context, "inside", 1);
        ScopeStore.addReference(context, "inside", 1, 1);
        ScopeStore.addDiagnostic(context, "speculative", 1, 1, ScopeStore.Severity.ERROR);
    }

    @Test
    public void sharedStateOracleRestoresLexicalScopesDeclarationsReferencesAndDiagnostics()
            throws Exception {
        var rows =
                Files.readAllLines(Path.of("../conformance/token-propagation/state.tsv")).stream()
                        .filter(row -> !row.startsWith("#") && !row.isBlank())
                        .toList();
        assertEquals(11, rows.size());
        for (Memoization memo : Memoization.values())
            for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
                for (String row : rows) {
                    String[] c = row.split("\t", -1);
                    assertEquals(17, c.length);
                    String label = c[0] + "/" + memo + "/" + diagnostics;
                    String input =
                            new String(HexFormat.of().parseHex(c[2]), StandardCharsets.UTF_8);
                    try (var context =
                            ParseContext.withOptions(
                                    StringSource.createRootSource(input),
                                    ParseOptions.withMemoization(memo)
                                            .withDiagnostics(diagnostics))) {
                        context.getCurrent().setResetMatchedWithConsumed(false);
                        assertTrue(word("p").parse(context).isSucceeded());
                        ScopeStore.enter(context);
                        ScopeStore.declare(context, "baseline", 0);
                        Parser mutates =
                                new WordParser("😀") {
                                    @Override
                                    public Token getToken(
                                            ParseContext current, TokenKind kind, boolean invert) {
                                        mutate(current);
                                        if (c[1].endsWith("exception")) {
                                            if (c[1].equals("open-exception")) current.begin(this);
                                            throw new IllegalStateException("callback failure");
                                        }
                                        return super.getToken(current, kind, invert);
                                    }
                                };
                        Parser child = mutates;
                        if (c[1].equals("choice"))
                            child = new Choice(new Chain(mutates, word("x")), word("😀"));
                        if (c[1].equals("raw-consume"))
                            child =
                                    new Chain() {
                                        @Override
                                        public Parsed parse(
                                                ParseContext current,
                                                TokenKind kind,
                                                boolean invert) {
                                            mutate(current);
                                            current.consume(new org.unlaxer.CodePointLength(1));
                                            return Parsed.SUCCEEDED;
                                        }
                                    };
                        if (c[1].contains("recovery")) {
                            child = new SyncPointRecoveryParser(word("ok"), ";");
                            if (c[1].equals("consume-recovery"))
                                child = new DoConsumePropagationStopper(child);
                        }
                        if (c[1].equals("resource")) {
                            Parser nested = word("x");
                            for (int i = 0; i < 1500; i++) nested = match(nested);
                            child = new Chain(mutates, nested);
                        }
                        Parser parser = match(child);
                        if (c[1].equals("outer-rollback")) context.begin(parser);
                        if (c[3].equals("resource_limit")) {
                            ParseLimitException failure =
                                    assertThrows(
                                            ParseLimitException.class, () -> parser.parse(context));
                            assertSame(
                                    failure,
                                    assertThrows(
                                            ParseLimitException.class,
                                            () -> word("p").parse(context)));
                        } else if (c[3].equals("callback_exception")) {
                            assertEquals(
                                    "callback failure",
                                    assertThrows(
                                                    IllegalStateException.class,
                                                    () -> parser.parse(context))
                                            .getMessage());
                            context.begin(parser);
                            assertTrue(word("😀").parse(context).isSucceeded());
                            context.rollback(parser);
                        } else {
                            if (c[1].equals("retry")) {
                                context.begin(parser);
                                assertTrue(parser.parse(context).isSucceeded());
                                context.rollback(parser);
                            }
                            assertEquals(
                                    label,
                                    c[3].equals("success"),
                                    parser.parse(context).isSucceeded());
                        }
                        if (c[1].equals("outer-rollback")) context.rollback(parser);
                        assertEquals(
                                label,
                                Integer.parseInt(c[4]),
                                context.getConsumedPosition().value());
                        assertEquals(
                                label,
                                Integer.parseInt(c[5]),
                                context.getMatchedPosition().value());
                        assertEquals(label, 1, context.getTokenStack().size());
                        assertEquals(
                                label,
                                Integer.parseInt(c[13]),
                                ScopeStore.currentScopeDepth(context));
                        assertEquals(
                                label,
                                Integer.parseInt(c[14]),
                                ScopeStore.getAllDeclarations(context).size());
                        assertEquals(
                                label,
                                Integer.parseInt(c[15]),
                                ScopeStore.getAllReferences(context).size());
                        assertEquals(
                                label,
                                Integer.parseInt(c[16]),
                                ScopeStore.getDiagnostics(context).size());
                        assertTrue(label, ScopeStore.isDeclared(context, "baseline"));
                        assertFalse(label, ScopeStore.isDeclared(context, "inside"));
                    }
                }
            }
    }
}
