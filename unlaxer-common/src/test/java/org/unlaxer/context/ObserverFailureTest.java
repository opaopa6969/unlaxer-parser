package org.unlaxer.context;

import static org.junit.Assert.*;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.listener.*;
import org.unlaxer.parser.*;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class ObserverFailureTest {
    @Test public void resourceAbortInsideAnObserverPreservesTheSameFatalBoundary() throws Exception {
        for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
            try (ParseContext context = ParseContext.withOptions(StringSource.createRootSource("a"),
                    ParseOptions.DEFAULT.withDiagnostics(diagnostics))) {
                int[] state = {7};
                context.registerTransactionalState(() -> { int saved = state[0]; return () -> state[0] = saved; });
                Parser target = new MatchOnly(new WordParser("a"));
                context.addTransactionListener(Name.of("resource"), new TransactionListener() {
                    public void setLevel(OutputLevel level) {}
                    public void onOpen(ParseContext c) {}
                    public void onClose(ParseContext c) {}
                    public void onBegin(ParseContext c, Parser p) {}
                    public void onRollback(ParseContext c, Parser p, TokenList tokens) {}
                    public void onCommit(ParseContext c, Parser p, TokenList tokens) {
                        if (p != target) return;
                        state[0] = 99;
                        for (int count = 0; count <= ParseContext.MAX_PARSE_DEPTH; count++)
                            c.startParse(p, c, TokenKind.consumed, false);
                    }
                });
                var original = assertThrows(ParseLimitException.class, () -> target.parse(context));
                assertEquals(7, state[0]); assertEquals(0, context.getConsumedPosition().value());
                assertEquals(0, context.getMatchedPosition().value()); assertTrue(context.getCurrent().tokens.isEmpty());
                assertEquals(1, context.getTokenStack().size());
                assertEquals(0, frameCount(context, "deferredFrameDepth"));
                assertSame(original, assertThrows(IllegalStateException.class, () -> target.parse(context)).getCause());
            }
        }
    }

    @Test public void observerErrorIsPreservedWithoutRegisteredState() {
        try (ParseContext context = new ParseContext(StringSource.createRootSource("a"))) {
            Error original = new AssertionError("observer error");
            context.addParserListener(Name.of("error"), new ParserListener() {
                public void setLevel(OutputLevel level) {}
                public void onStart(Parser p, ParseContext c, TokenKind kind, boolean invert) {}
                public void onEnd(Parser p, Parsed parsed, ParseContext c, TokenKind kind, boolean invert) {throw original;}
            });
            assertSame(original, assertThrows(AssertionError.class, () -> new WordParser("a").parse(context)));
            assertEquals(0, context.getConsumedPosition().value()); assertTrue(context.getCurrent().tokens.isEmpty());
            assertSame(original, assertThrows(IllegalStateException.class, () -> new WordParser("a").parse(context)).getCause());
        }
    }

    @Test public void listenerChangesApplyToTheNextNotificationWithoutInvalidatingTheCurrentDispatch() {
        try (ParseContext context = new ParseContext(StringSource.createRootSource("a"))) {
            List<String> events = new ArrayList<>();
            RuntimeException failure = new IllegalStateException("second listener");
            context.addParserListener(Name.of("first"), new ParserListener() {
                public void setLevel(OutputLevel level) {}
                public void onStart(Parser p, ParseContext c, TokenKind kind, boolean invert) {
                    events.add("first"); c.removeParserListerner(Name.of("first"));
                }
                public void onEnd(Parser p, Parsed result, ParseContext c, TokenKind kind, boolean invert) {}
            });
            context.addParserListener(Name.of("second"), new ParserListener() {
                public void setLevel(OutputLevel level) {}
                public void onStart(Parser p, ParseContext c, TokenKind kind, boolean invert) {events.add("second"); throw failure;}
                public void onEnd(Parser p, Parsed result, ParseContext c, TokenKind kind, boolean invert) {}
            });
            assertSame(failure, assertThrows(IllegalStateException.class, () -> new WordParser("a").parse(context)));
            assertEquals(List.of("first", "second"), events);
            assertEquals(1, context.getTokenStack().size()); assertTrue(context.parseFrames.isEmpty());
        }
    }

    @Test public void publicTransactionsHaveTheirOwnBoundaryWithoutParseNotifications() {
        for (String phase : List.of("begin", "commit", "rollback")) {
            try (ParseContext context = new ParseContext(StringSource.createRootSource("a"))) {
                int[] state = {7};
                context.registerTransactionalState(() -> { int saved = state[0]; return () -> state[0] = saved; });
                Parser parser = new WordParser("a");
                RuntimeException original = new IllegalStateException(phase);
                context.addTransactionListener(Name.of("direct"), new TransactionListener() {
                    public void setLevel(OutputLevel level) {}
                    public void onOpen(ParseContext c) {}
                    public void onClose(ParseContext c) {throw new AssertionError("invalid context close callback");}
                    private void event(String actual) { state[0] = 99; if (phase.equals(actual)) throw original; }
                    public void onBegin(ParseContext c, Parser p) {event("begin");}
                    public void onCommit(ParseContext c, Parser p, TokenList tokens) {event("commit");}
                    public void onRollback(ParseContext c, Parser p, TokenList tokens) {event("rollback");}
                });
                assertSame(original, assertThrows(IllegalStateException.class, () -> {
                    context.begin(parser);
                    parser.parse(context);
                    if (phase.equals("rollback")) context.rollback(parser);
                    else context.commit(parser, TokenKind.consumed);
                }));
                assertEquals(7, state[0]); assertEquals(0, context.getConsumedPosition().value());
                assertTrue(context.getCurrent().tokens.isEmpty()); assertEquals(1, context.getTokenStack().size());
            }
        }
    }

    @Test public void parseInsideAnExistingTransactionRestoresItsOwnEntryThenCloseUnwindsTheOwner() {
        int[] state = {7};
        ParseContext context = new ParseContext(StringSource.createRootSource("za"));
        context.registerTransactionalState(() -> { int saved = state[0]; return () -> state[0] = saved; });
        Parser transaction = new WordParser("manual");
        context.begin(transaction);
        new WordParser("z").parse(context);
        state[0] = 11;
        Parser target = new MatchOnly(new WordParser("a"));
        RuntimeException original = new IllegalStateException("nested parse");
        context.addTransactionListener(Name.of("nested"), new TransactionListener() {
            public void setLevel(OutputLevel level) {}
            public void onOpen(ParseContext c) {}
            public void onClose(ParseContext c) {}
            public void onBegin(ParseContext c, Parser p) {}
            public void onCommit(ParseContext c, Parser p, TokenList tokens) {if (p == target) {state[0] = 99; throw original;}}
            public void onRollback(ParseContext c, Parser p, TokenList tokens) {}
        });
        assertSame(original, assertThrows(IllegalStateException.class, () -> target.parse(context)));
        assertEquals(11, state[0]); assertEquals(1, context.getConsumedPosition().value());
        assertEquals(1, context.getCurrent().tokens.size()); assertEquals(2, context.getTokenStack().size());
        context.close();
        assertEquals(7, state[0]); assertEquals(1, context.getTokenStack().size());
    }

    @Test public void sharedFailureOracleRestoresTheOutermostPublishedBoundary() throws Exception {
        var rows = Files.readAllLines(Path.of("../conformance/observer-failures/cases.tsv")).stream()
            .filter(row -> !row.startsWith("#") && !row.isEmpty()).toList();
        assertEquals(20, rows.size());
        for (Memoization memo : Memoization.values()) for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values())
            for (String row : rows) {
                String[] data = row.split("\t", -1);
                String label = data[0] + "/" + memo + "/" + diagnostics;
                int[] state = {7};
                try (ParseContext c = ParseContext.withOptions(StringSource.createRootSource(decode(data[3])),
                        ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                    c.registerTransactionalState(() -> { int saved = state[0]; return () -> state[0] = saved; });
                    c.getCurrent().setResetMatchedWithConsumed(false);
                    Token prior = new WordParser(decode(data[4])).parse(c).getRootToken();
                    if (!data[5].isEmpty()) assertTrue(new WordParser(decode(data[5])).parse(c, TokenKind.matchOnly, false).isSucceeded());
                    PortableTokenMetadata metadata = new PortableTokenMetadata(c);
                    var existing = metadata.register(prior);
                    metadata.putExtra(existing, "label", "before");
                    var fresh = new AtomicReference<PortableTokenMetadata.NodeId>();
                    List<String> events = new ArrayList<>();
                    RuntimeException original = new IllegalStateException(label);
                    WordParser leaf = new WordParser("a");
                    Parser observed = new MatchOnly(data[1].equals("fail") ? new WordParser("b") : leaf);
                    Parser root = observed;
                    switch (data[1]) {
                        case "leaf" -> { observed = leaf; root = leaf; }
                        case "nested" -> root = new Chain(observed, new WordParser("!"));
                        case "child" -> { observed = leaf; root = new MatchOnly(leaf); }
                        case "peg" -> { observed = leaf; root = new PegLookahead(leaf); }
                        default -> { }
                    }
                    Parser target = observed;
                    Choice choice = new Choice(leaf, new WordParser("x"));
                    NonOrdered ordered = new NonOrdered(leaf, new WordParser("x"));
                    Parser oldChoice = new WordParser("old");
                    Parsers oldOrder = new Parsers(oldChoice);
                    c.choose(choice, oldChoice); c.order(ordered, oldOrder);
                    java.util.function.Consumer<String> event = stage -> {
                        events.add(stage); state[0] = 99;
                        metadata.putExtra(existing, "label", "during");
                        var token = new Token(TokenKind.consumed, c.peek(c.getConsumedPosition(), new CodePointLength(0)), leaf);
                        fresh.set(metadata.register(token));
                        metadata.putRelated(existing, "new", fresh.get());
                        c.choose(choice, leaf); c.order(ordered, new Parsers(leaf));
                        if (stage.equals(data[2])) throw original;
                    };
                    c.addParserListener(Name.of("parser"), new ParserListener() {
                        public void setLevel(OutputLevel level) {}
                        public void onStart(Parser p, ParseContext context, TokenKind kind, boolean invert) { if (p == target) event.accept("start"); }
                        public void onEnd(Parser p, Parsed parsed, ParseContext context, TokenKind kind, boolean invert) { if (p == target) event.accept("end"); }
                    });
                    c.addTransactionListener(Name.of("transaction"), new TransactionListener() {
                        public void setLevel(OutputLevel level) {}
                        public void onOpen(ParseContext context) {}
                        public void onClose(ParseContext context) { throw new AssertionError("poisoned close must not notify observers"); }
                        public void onBegin(ParseContext context, Parser p) { if (p == target) event.accept("begin"); }
                        public void onCommit(ParseContext context, Parser p, TokenList tokens) { if (p == target) event.accept("commit"); }
                        public void onRollback(ParseContext context, Parser p, TokenList tokens) { if (p == target) event.accept("rollback"); }
                    });
                    if (data[2].equals("precommit")) c.addActions((Transaction.AdditionalPreCommitAction)
                        (p, context) -> { if (p == target) event.accept("precommit"); });
                    if (data[2].equals("postcommit")) c.addActions((Transaction.AdditionalPostCommitAction)
                        (p, context, tokens) -> { if (p == target) event.accept("postcommit"); });
                    Parser entry = root;
                    long versionBefore = c.getMemoizationStateVersion();
                    assertSame(label, original, assertThrows(IllegalStateException.class, () -> entry.parse(c)));
                    assertEquals(label, data[6], String.join(",", events));
                    assertEquals(label, Integer.parseInt(data[7]), c.getConsumedPosition().value());
                    assertEquals(label, Integer.parseInt(data[8]), c.getMatchedPosition().value());
                    assertEquals(label, Integer.parseInt(data[9]), c.getCurrent().tokens.size());
                    assertSame(label, prior, c.getCurrent().tokens.get(0));
                    assertEquals(label, Integer.parseInt(data[10]), state[0]);
                    assertEquals(label, versionBefore, c.getMemoizationStateVersion());
                    assertEquals(label, data[11], metadata.info(existing).orElseThrow().extra().get("label"));
                    assertFalse(label, metadata.info(existing).orElseThrow().related().containsKey("new"));
                    assertEquals(label, Boolean.parseBoolean(data[12]), metadata.info(fresh.get()).isPresent());
                    assertSame(label, oldChoice, c.getChosen(choice).orElseThrow());
                    assertSame(label, oldOrder, c.getOrdered(ordered));
                    assertEquals(label, 1, c.getTokenStack().size());
                    assertTrue(label, c.parseFrames.isEmpty()); assertEquals(label, 0, frameCount(c, "terminalFrames"));
                    assertEquals(label, 0, frameCount(c, "memoDiagnosticFrames"));
                    assertEquals(label, 0, frameCount(c, "deferredFrameDepth"));
                    assertEquals(label, 0, frameCount(c, "memoTransactionFrameCount"));
                    assertSame(label, original, assertThrows(IllegalStateException.class, () -> leaf.parse(c)).getCause());
                    assertEquals(label, data[6], String.join(",", events));
                }
                // The same grammar/input remains retryable with a new context.
                try (ParseContext retry = new ParseContext(StringSource.createRootSource(decode(data[3])))) {
                    assertTrue(new WordParser(decode(data[4])).parse(retry).isSucceeded());
                    assertTrue(new WordParser("a").parse(retry).isSucceeded() || !data[5].isEmpty());
                }
            }
    }

    private static int frameCount(ParseContext context, String name) throws Exception {
        var field = ParseContext.class.getDeclaredField(name); field.setAccessible(true);
        Object value = field.get(context);
        return value instanceof Collection<?> collection ? collection.size() : (Integer) value;
    }

    private static String decode(String hex) {
        return new String(HexFormat.of().parseHex(hex), StandardCharsets.UTF_8);
    }

    @Test public void nestedSelfListenersUseTheOutermostStateCheckpoint() {
        for (String stage : List.of("begin", "commit", "rollback")) {
            int[] state = {7};
            RuntimeException original = new IllegalStateException(stage);
            class SelfListener extends MatchOnly implements TransactionListener {
                SelfListener() { super(new WordParser(stage.equals("rollback") ? "b" : "a")); }
                public void setLevel(OutputLevel level) {}
                public void onOpen(ParseContext context) {}
                public void onClose(ParseContext context) {}
                private void event(String phase) { state[0] = 99; if (phase.equals(stage)) throw original; }
                public void onBegin(ParseContext context, Parser parser) {event("begin");}
                public void onCommit(ParseContext context, Parser parser, TokenList tokens) {event("commit");}
                public void onRollback(ParseContext context, Parser parser, TokenList tokens) {event("rollback");}
            }
            try (ParseContext c = new ParseContext(StringSource.createRootSource("za"))) {
                c.registerTransactionalState(() -> { int saved = state[0]; return () -> state[0] = saved; });
                WordParser prefix = new WordParser("z") {
                    @Override public Token getToken(ParseContext context, TokenKind kind, boolean invert) {
                        state[0] = 11; return super.getToken(context, kind, invert);
                    }
                };
                Parser root = new Chain(prefix, new SelfListener());
                assertSame(stage, original, assertThrows(stage, IllegalStateException.class, () -> root.parse(c)));
                assertEquals(7, state[0]); assertEquals(0, c.getConsumedPosition().value());
                assertEquals(0, c.getMatchedPosition().value()); assertTrue(c.getCurrent().tokens.isEmpty());
                assertEquals(1, c.getTokenStack().size());
            }
        }
    }

    @Test public void swallowingAnObserverExceptionCannotResumePublication() {
        try (ParseContext c = new ParseContext(StringSource.createRootSource("a"))) {
            Parser child = new WordParser("a");
            RuntimeException original = new IllegalStateException("child observer");
            c.addParserListener(Name.of("throw"), new ParserListener() {
                public void setLevel(OutputLevel level) {}
                public void onStart(Parser p, ParseContext context, TokenKind kind, boolean invert) { if (p == child) throw original; }
                public void onEnd(Parser p, Parsed result, ParseContext context, TokenKind kind, boolean invert) {}
            });
            Parser root = new WordParser("a") {
                @Override public Token getToken(ParseContext context, TokenKind kind, boolean invert) {
                    try { child.parse(context); } catch (IllegalStateException ignored) { }
                    return super.getToken(context, kind, invert);
                }
            };
            assertSame(original, assertThrows(IllegalStateException.class, () -> root.parse(c)).getCause());
            assertEquals(0, c.getConsumedPosition().value()); assertTrue(c.getCurrent().tokens.isEmpty());
        }
    }

    @Test public void commitObserverCannotPublishItsFailedParse() {
        for (Memoization memo : Memoization.values()) for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
            try (ParseContext c = ParseContext.withOptions(StringSource.createRootSource("za"),
                    ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                assertTrue(new WordParser("z").parse(c).isSucceeded());
                Parser parser = new MatchOnly(new WordParser("a"));
                int[] state = {7};
                c.registerTransactionalState(() -> { int saved = state[0]; return () -> state[0] = saved; });
                RuntimeException original = new IllegalStateException("observer failure");
                c.addTransactionListener(Name.of("fail"), new TransactionListener() {
                    public void setLevel(OutputLevel level) {}
                    public void onOpen(ParseContext context) {}
                    public void onBegin(ParseContext context, Parser owner) {state[0] = 99;}
                    public void onClose(ParseContext context) {}
                    public void onCommit(ParseContext context, Parser owner, TokenList tokens) {
                        if (owner == parser) throw original;
                    }
                });
                assertSame(original, assertThrows(IllegalStateException.class, () -> parser.parse(c)));
                assertEquals(1, c.getConsumedPosition().value());
                assertEquals(1, c.getMatchedPosition().value());
                assertEquals(1, c.getCurrent().tokens.size());
                assertEquals(7, state[0]);
                assertEquals(1, c.getTokenStack().size());
                assertSame(original, assertThrows(IllegalStateException.class, () -> new WordParser("a").parse(c)).getCause());
            }
        }
    }
}
