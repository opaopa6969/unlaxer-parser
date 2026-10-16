package org.unlaxer.parser.combinator;

import static org.junit.Assert.*;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.parser.elementary.WordParser;

public class NonOrderedParityTest {
    private static final class State implements TransactionalState {
        int value = 7;
        public Runnable checkpoint() { int old = value; return () -> value = old; }
    }
    @Test public void failedCandidateCannotConsumeInputOrLeakOwnedState() {
        State state = new State();
        WordParser dirty = new WordParser("a") {
            @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
                if (context.getConsumedPosition().value() == 0) {
                    state.value = 99;
                    new WordParser("b").parse(context);
                    return Parsed.FAILED;
                }
                return super.parse(context, kind, invert);
            }
        };
        try (var context = new ParseContext(StringSource.createRootSource("ba"))) {
            context.registerTransactionalState(state);
            assertTrue(new NonOrdered(dirty, new WordParser("b")).parse(context).isSucceeded());
            assertEquals(2, context.getConsumedPosition().value());
            assertEquals(7, state.value);
        }
    }

    @Test public void tokenKindsInversionAndRepeatedIdentityKeepTheirContracts() {
        for (var meta : CreateMetaTokenSpecifier.values()) for (var kind : TokenKind.values()) {
            for (boolean invert : new boolean[]{false, true}) {
                var child = new WordParser(invert ? "x" : "a");
                var parser = new NonOrdered(child, child);
                try (var context = new ParseContext(StringSource.createRootSource("aa"), meta)) {
                    assertTrue(parser.parse(context, kind, invert).isSucceeded());
                    assertEquals(kind.isConsumed() ? 2 : 0, context.getConsumedPosition().value());
                    assertEquals(2, context.getMatchedPosition().value());
                    assertEquals(2, context.getOrdered(parser).size());
                    assertSame(child, context.getOrdered(parser).get(0));
                    assertSame(child, context.getOrdered(parser).get(1));
                    for (Token root : context.getCurrent().getTokens()) {
                        for (Token token : root.flatten(Token.ScanDirection.Depth, Token.ChildrenKind.original)) {
                            assertTrue("no internal candidate wrapper", token.parser == parser || token.parser == child);
                        }
                    }
                }
            }
        }
    }
    private static final class SafeNonOrdered extends LazyChain implements SafeFailureMemoizable {
        private static final long serialVersionUID = 1L;
        @Override public org.unlaxer.parser.Parsers getLazyParsers() {
            return org.unlaxer.parser.Parsers.of(new NonOrdered(new WordParser("a"), new WordParser("b")));
        }
    }
    @Test public void safeFailureMemoReplaysTheCandidateTransactions() {
        for (var memo : Memoization.values()) for (var diagnostics : ParseOptions.Diagnostics.values()) {
            var child = new SafeNonOrdered();
            var parser = new Choice(child, child);
            try (var context = ParseContext.withOptions(StringSource.createRootSource("a?"),
                    ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                assertFalse(parser.parse(context).isSucceeded());
                assertEquals(0, context.getConsumedPosition().value());
                assertEquals(0, context.getMatchedPosition().value());
                assertTrue(context.getCurrent().getTokens().isEmpty());
                if (memo == Memoization.SAFE_FAILURES) assertTrue(context.getPackratMemoTable().failureHits() > 0);
                if (diagnostics == ParseOptions.Diagnostics.DETAILED) {
                    assertEquals(1, context.getParseFailureDiagnostics().getFarthestOffset());
                }
            }
        }
    }
    @Test public void recursiveCandidateResourceAbortIsStickyAndClosesTransactions() {
        org.unlaxer.parser.Parser nested = new WordParser("a");
        for (int i = 0; i < 1100; i++) nested = new NonOrdered(nested);
        var parser = new Choice(nested, new WordParser("a"));
        try (var context = new ParseContext(StringSource.createRootSource("a"))) {
            var failure = assertThrows(ParseLimitException.class, () -> parser.parse(context));
            assertEquals(ParseLimitException.CODE, failure.code());
            assertSame(failure, assertThrows(ParseLimitException.class, () -> new WordParser("a").parse(context)));
        }
    }

    private static String decode(String hex) {
        if (hex.equals("~")) return "";
        StringBuilder value = new StringBuilder();
        for (String cp : hex.split(" ")) value.appendCodePoint(Integer.parseInt(cp, 16));
        return value.toString();
    }
    private static java.nio.file.Path fixture() {
        var root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.exists(root.resolve("spec-corpus/nonordered/cases.tsv"))) root = root.getParent();
        if (root == null) throw new AssertionError("fixture missing");
        return root.resolve("spec-corpus/nonordered/cases.tsv");
    }
    private static final class Child extends Chain {
        private static final long serialVersionUID = 1L;
        final int index;
        Child(int index, org.unlaxer.parser.Parser parser) { super(parser); this.index = index; }
    }
    private static org.unlaxer.parser.Parser atom(String spec) {
        return switch (spec) {
            case "N" -> new NonOrdered();
            case "E" -> new org.unlaxer.parser.elementary.EndOfSourceParser();
            case "Z" -> new org.unlaxer.parser.elementary.EmptyParser();
            default -> spec.startsWith("O") ? new Optional(new WordParser(decode(spec.substring(1)))) : new WordParser(decode(spec));
        };
    }
    @Test public void sharedGreedyOrderNullableSpansAndDiagnostics() throws Exception {
        for (var memo : Memoization.values()) for (var diagnostics : ParseOptions.Diagnostics.values()) {
            for (String row : java.nio.file.Files.readAllLines(fixture())) {
                if (row.startsWith("#")) continue;
                String[] f = row.split("\t");
                String input = decode(f[1]);
                var children = new java.util.ArrayList<org.unlaxer.parser.Parser>();
                if (!f[2].equals("~")) for (String spec : f[2].split("/")) children.add(new Child(children.size(), atom(spec)));
                var unordered = new NonOrdered(children.toArray(org.unlaxer.parser.Parser[]::new));
                org.unlaxer.parser.Parser parser = switch (f[3]) {
                    case "FAIL" -> new Chain(unordered, new WordParser("!"));
                    case "RETRY" -> new Choice(new Chain(unordered, new WordParser("!")), unordered);
                    case "PREFIX" -> new Chain(new org.unlaxer.parser.elementary.WildCardCharacterParser(), unordered);
                    case "NESTED" -> new NonOrdered(unordered, new WordParser("!"));
                    default -> unordered;
                };
                try (var context = ParseContext.withOptions(StringSource.createRootSource(input), ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                    var result = parser.parse(context);
                    String label = f[0] + "/" + memo + "/" + diagnostics;
                    assertEquals(label, Boolean.parseBoolean(f[4]), result.isSucceeded());
                    assertEquals(label, Integer.parseInt(f[5]), context.getConsumedPosition().value());
                    assertEquals(label, Integer.parseInt(f[6]), context.getMatchedPosition().value());
                    if (result.isSucceeded()) {
                        var spans = new java.util.ArrayList<String>();
                        for (Token root : context.getCurrent().getTokens()) for (Token token : root.flatten(Token.ScanDirection.Depth, Token.ChildrenKind.original)) {
                            if (token.parser instanceof Child child) {
                                int begin = token.source.offsetFromRoot().value();
                                int end = begin + token.source.codePointLength().value();
                                spans.add(child.index + ":" + begin + ":" + end);
                                assertEquals(input.substring(input.offsetByCodePoints(0, begin), input.offsetByCodePoints(0, end)), token.source.toString());
                            }
                        }
                        assertEquals(label, f[7].equals("~") ? "" : f[7], String.join(",", spans));
                        var selected = context.getOrdered(unordered);
                        if (!children.isEmpty()) assertEquals(children.size(), selected.size());
                    } else {
                        assertTrue(label, context.getCurrent().getTokens().isEmpty());
                        assertNull(label, context.getOrdered(unordered));
                        if (diagnostics == ParseOptions.Diagnostics.DETAILED) assertEquals(label, Integer.parseInt(f[8]), context.getParseFailureDiagnostics().getFarthestOffset());
                        assertFalse(label, parser.parse(context).isSucceeded());
                        assertEquals(label, 0, context.getConsumedPosition().value());
                    }
                }
            }
        }
    }
}
