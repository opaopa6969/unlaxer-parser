package org.unlaxer.context;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.ReplayParser;
import org.unlaxer.parser.elementary.WordParser;

public class OwnedCaptureTest {
    private static String decode(String value) {
        return value.equals("~") ? "" : new String(HexFormat.of().parseHex(value), StandardCharsets.UTF_8);
    }
    private static Parser w(String text) { return new WordParser(text); }
    private static Parser cap(Parser child) { return new NamedCapture("value", child); }
    private static Parser replay() { return new ReplayParser("value"); }
    private static void parse(ParseContext c, Parser p) { assertTrue(p.parse(c).isSucceeded()); }
    private static Parser generated() {
        return new NoneChildParser() {
            @Override public Parser createParser() { return this; }
            @Override public Parsed parse(ParseContext c, TokenKind k, boolean inv) {
                Token token = new Token(TokenKind.virtualTokenConsumed, StringSource.createDetachedSource("x"), this);
                c.getCurrent().addToken(token, TokenKind.virtualTokenConsumed);
                return new Parsed(token);
            }
        };
    }
    private static Parsed scenario(ParseContext c, String name, TokenKind kind, boolean invert) {
        switch (name) {
            case "input": parse(c, cap(w("a"))); break;
            case "unicode": parse(c, cap(w("😀"))); break;
            case "combining": parse(c, cap(w("e\u0301"))); break;
            case "family": parse(c, cap(w("👩‍👩‍👧‍👦"))); break;
            case "crlf": parse(c, cap(w("a\r\n😀"))); break;
            case "inverted": parse(c, cap(w("ab"))); break;
            case "empty": parse(c, cap(new Chain())); break;
            case "generated": parse(c, cap(generated())); break;
            case "generated-prefix": parse(c,cap(w("p"))); parse(c,cap(generated())); break;
            case "projected":
                c.getCurrent().setResetMatchedWithConsumed(false);
                assertTrue(w("a").parse(c, TokenKind.matchOnly, false).isSucceeded());
                assertTrue(cap(new Chain(w("b"), w("b"))).parse(c, TokenKind.virtualTokenConsumed, false).isSucceeded());
                parse(c, w("c")); break;
            case "prior-failure": parse(c,cap(w("a"))); assertTrue(w("bc").parse(c,TokenKind.matchOnly,false).isSucceeded()); break;
            case "consume-stopper": parse(c,cap(w("a"))); return new MatchOnly(new DoConsumePropagationStopper(replay())).parse(c);
            case "invert-stopper": parse(c,cap(w("ab"))); return new NotPropagatableSource(new InvertMatchPropagationStopper(replay())).parse(c);
            case "undefined": break;
            case "cap-peg": return cap(new PegLookahead(w("a"))).parse(c);
            case "virtual-empty": parse(c,cap(w("p"))); return cap(new Chain()).parse(c,kind,invert);
            case "shadow":
                parse(c, cap(w("a")));
                parse(c, new CaptureScope(new Chain(cap(w("b")), replay()))); break;
            case "read-scope":
                parse(c, cap(w("a"))); parse(c, new CaptureScope(replay())); break;
            case "local": parse(c, new CaptureScope(cap(w("a")))); break;
            case "nested":
                parse(c, cap(w("a")));
                parse(c, new CaptureScope(new Chain(cap(w("b")),
                    new CaptureScope(cap(w("c"))), replay()))); break;
            case "scope-failure":
                parse(c, cap(w("a")));
                return new CaptureScope(new Chain(cap(w("b")), w("z"))).parse(c);
            case "field-repeat-reset", "field-repeat-preserve":
                c.getCurrent().setResetMatchedWithConsumed(name.equals("field-repeat-reset"));
                // 生成された @f (__CaptureSite) と同じ LazyChain の収集境界。
                Parser field = new LazyChain() {
                    private static final long serialVersionUID = 1L;
                    @Override public org.unlaxer.parser.Parsers getLazyParsers() {
                        return new org.unlaxer.parser.Parsers(new Repeat(new MatchOnly(w("x")), 0, 2));
                    }
                };
                return cap(field).parse(c, kind, invert);
            case "match-only": parse(c, cap(w("a"))); return new MatchOnly(cap(w("b"))).parse(c);
            case "match-new": return new MatchOnly(cap(w("a"))).parse(c);
            case "peg": return new PegLookahead(cap(w("a"))).parse(c);
            case "choice": return new Choice(new Chain(cap(w("a")), w("x")), cap(w("a"))).parse(c);
            case "retry":
                parse(c, cap(w("a")));
                assertTrue(new CaptureScope(new Chain(cap(w("a")), w("z"))).parse(c).isFailed()); break;
            default: throw new AssertionError(name);
        }
        return replay().parse(c, kind, invert);
    }

    @Test public void lookaheadCollectorsKeepEmptyCommittedSource() {
        for (Memoization memo : Memoization.values()) {
            for (int variant = 0; variant < 4; variant++) {
                Parser ahead = new MatchOnly(w("x"));
                Parser collector = switch (variant) {
                    case 0 -> new Optional(ahead);
                    case 1 -> new SyncPointRecoveryParser(ahead, ";");
                    case 2 -> CaptureBoundaryParser.value(ahead);
                    default -> CaptureBoundaryParser.input(CaptureBoundaryParser.Kind.VALUE,
                            new Choice(ahead, w("q")));
                };
                try (ParseContext context = ParseContext.withOptions(StringSource.createRootSource("x"),
                        ParseOptions.withMemoization(memo))) {
                    Parsed result = cap(collector).parse(context);
                    assertTrue(result.isSucceeded());
                    assertEquals("", result.getRootToken().source.sourceAsString());
                    var value = context.captured("value").orElseThrow();
                    assertEquals("", value.text());
                    assertEquals(0, value.start());
                    assertEquals(0, value.end());
                    assertEquals(0, context.getConsumedPosition().value());
                    assertEquals(1, context.getMatchedPosition().value());
                }
            }
        }
    }

    @Test public void sharedIndependentOracle() throws Exception {
        var rows = Files.readAllLines(Path.of("../conformance/owned-capture/cases.tsv"));
        var failures = new ArrayList<String>();
        for (Memoization memo : Memoization.values()) for (ParseOptions.Diagnostics diagnostic : ParseOptions.Diagnostics.values()) {
            for (String row : rows) {
                if (row.startsWith("#") || row.isBlank()) continue;
                String[] e = row.split("\t");
                String label = e[0] + "/" + memo + "/" + diagnostic;
                try (ParseContext c = ParseContext.withOptions(StringSource.createRootSource(decode(e[1])),
                    ParseOptions.withMemoization(memo).withDiagnostics(diagnostic))) {
                    Parsed result = scenario(c, e[2], TokenKind.valueOf(e[3]), Boolean.parseBoolean(e[4]));
                    assertEquals(label, Boolean.parseBoolean(e[5]), result.isSucceeded());
                    assertEquals(label, Integer.parseInt(e[6]), c.getConsumedPosition().value());
                    assertEquals(label, Integer.parseInt(e[7]), c.getMatchedPosition().value());
                    var value = c.captured("value");
                    assertEquals(label, !e[8].equals("-"), value.isPresent());
                    if (value.isPresent()) {
                        assertEquals(label, decode(e[8]), value.get().text());
                        assertEquals(label, e[9], value.get().provenance().name());
                        assertEquals(label, Integer.parseInt(e[10]), value.get().start());
                        assertEquals(label, Integer.parseInt(e[11]), value.get().end());
                    }
                    if (result.isSucceeded()) {
                        Source source = result.getRootToken().source;
                        assertEquals(label, decode(e[12]), source.sourceAsString());
                        // Pure PEG assertions intentionally return no token.
                        int start = result.getOriginalTokens().isEmpty() && result.getTokenOptional().isEmpty() ? c.getConsumedPosition().value() : source.offsetFromRoot().value();
                        assertEquals(label, Integer.parseInt(e[13]), start);
                        assertEquals(label, Integer.parseInt(e[14]), start + source.codePointLength().value());
                    }
                    assertEquals(label, Integer.parseInt(e[15]), c.getCurrent().getTokens().size());
                    assertEquals(label, 1, c.getTokenStack().size());
                } catch (AssertionError error) { failures.add(label+": "+error.getMessage()); }
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    private static final class State implements TransactionalState {
        int value = 7;
        @Override public Runnable checkpoint() { int saved = value; return () -> value = saved; }
    }

    @Test public void sharedOwnedStateAndExceptionalCleanup() throws Exception {
        for (Memoization memo : Memoization.values()) for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
            for (String row : Files.readAllLines(Path.of("../conformance/owned-capture/state.tsv"))) {
                if (row.startsWith("#") || row.isBlank()) continue;
                String[] e = row.split("\t");
                State state = new State();
                try (var c = ParseContext.withOptions(StringSource.createRootSource("p😀;"),
                    ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                    c.registerTransactionalState(state);
                    PortableTokenMetadata metadata = new PortableTokenMetadata(c);
                    Token old = cap(w("p")).parse(c).getRootToken();
                    var oldId = metadata.register(old);
                    metadata.putExtra(oldId, "label", "before");
                    var fresh = new java.util.concurrent.atomic.AtomicReference<PortableTokenMetadata.NodeId>();
                    RuntimeException original = new IllegalStateException("child callback");
                    Parser mutates = new WordParser("😀") {
                        @Override public Token getToken(ParseContext context, TokenKind k, boolean inv) {
                            state.value = 99;
                            metadata.putExtra(oldId, "label", "during");
                            Token token = super.getToken(context, k, inv);
                            fresh.set(metadata.register(token));
                            metadata.putRelated(oldId, "new", fresh.get());
                            return token;
                        }
                    };
                    Parser child = cap(mutates);
                    if (e[2].equals("exception")) {
                        Parser succeeds = child;
                        child = new NoneChildParser() {
                            @Override public Parser createParser() { return this; }
                            @Override public Parsed parse(ParseContext context, TokenKind k, boolean inv) {
                                context.startParse(this, context, k, inv);
                                context.begin(this);
                                assertTrue(succeeds.parse(context, k, inv).isSucceeded());
                                throw original; // deliberately abandoned child transaction/parse frames
                            }
                        };
                    } else if (e[2].equals("failure")) child = new Chain(child, w("z"));
                    else if (e[2].equals("resource")) {
                        Parser deep = w("x");
                        for (int i = 0; i < 1500; i++) deep = new MatchOnly(deep);
                        child = new Chain(child, deep);
                    }
                    Parser parser = switch (e[1]) {
                        case "capture" -> new NamedCapture("outer", child);
                        case "scope" -> new CaptureScope(child);
                        case "match" -> new MatchOnly(child);
                        case "peg" -> new PegLookahead(child);
                        default -> throw new AssertionError(e[1]);
                    };
                    if (e[2].equals("exception")) assertSame(original, assertThrows(IllegalStateException.class, () -> parser.parse(c)));
                    else if (e[2].equals("resource")) {
                        var failure = assertThrows(ParseLimitException.class, () -> parser.parse(c));
                        assertSame(failure, assertThrows(ParseLimitException.class, () -> w("p").parse(c)));
                    } else {
                        if (e[2].equals("rollback")) c.begin(parser);
                        assertEquals(e[0], !e[2].equals("failure"), parser.parse(c).isSucceeded());
                        if (e[2].equals("rollback")) c.rollback(parser);
                    }
                    assertEquals(e[0], Integer.parseInt(e[3]), c.getConsumedPosition().value());
                    assertEquals(e[0], Integer.parseInt(e[4]), c.getMatchedPosition().value());
                    assertEquals(e[0], Integer.parseInt(e[5]), state.value);
                    assertEquals(e[0], decode(e[6]), c.captured("value").orElseThrow().text());
                    assertEquals(e[0], e[7], metadata.info(oldId).orElseThrow().extra().get("label"));
                    assertEquals(e[0], Boolean.parseBoolean(e[8]), metadata.snapshot().info(fresh.get()).isPresent());
                    assertEquals(e[0], Integer.parseInt(e[9]), c.getCurrent().getTokens().size());
                    assertEquals(e[0], 1, c.getTokenStack().size());
                    if (e[2].equals("exception")) {
                        assertTrue(w("😀").parse(c).isSucceeded()); // ordinary child errors do not poison
                    }
                }
            }
        }
    }

    @Test public void valuesOwnTheirTextAndSurviveLaterParsesAndContextClose() {
        CapturedValue retained;
        try (var c = new ParseContext(StringSource.createRootSource("abcbb"))) {
            c.getCurrent().setResetMatchedWithConsumed(false);
            assertTrue(w("a").parse(c, TokenKind.matchOnly,false).isSucceeded());
            assertTrue(cap(new Chain(w("b"),w("b"))).parse(c,TokenKind.virtualTokenConsumed,false).isSucceeded());
            retained = c.captured("value").orElseThrow();
            assertEquals("bb", retained.text());
            assertEquals(CapturedValue.Provenance.PROJECTED, retained.provenance());
            assertTrue(new Chain(cap(w("c")),w("z")).parse(c).isFailed());
            assertSame(retained,c.captured("value").orElseThrow());
            parse(c,w("c")); parse(c,replay());
            parse(c,new CaptureScope(cap(generated())));
            assertSame(retained,c.captured("value").orElseThrow());
        }
        try (var other = new ParseContext(StringSource.createRootSource("different"))) {
            parse(other, cap(w("different")));
            assertEquals("bb",retained.text());
            assertEquals(1,retained.start()); assertEquals(3,retained.end());
        }
    }

    @Test public void observerFailureRestoresOwnedBindingsAndInvalidatesContext() {
        for (boolean scope : new boolean[] { false, true }) {
            var c = new ParseContext(StringSource.createRootSource("p😀"));
            parse(c,cap(w("p")));
            CapturedValue previous = c.captured("value").orElseThrow();
            Parser child = cap(w("😀"));
            Parser boundary = scope ? new CaptureScope(child) : new NamedCapture("outer",child);
            RuntimeException original = new IllegalStateException("commit observer");
            c.addTransactionListener(Name.of("owned-capture-observer"), new org.unlaxer.listener.TransactionListener() {
                @Override public void setLevel(org.unlaxer.listener.OutputLevel level) {}
                @Override public void onBegin(ParseContext current, Parser parser) {}
                @Override public void onRollback(ParseContext current, Parser parser, TokenList tokens) {}
                @Override public void onOpen(ParseContext current) {}
                @Override public void onClose(ParseContext current) {}
                @Override public void onCommit(ParseContext current, Parser parser, TokenList tokens) {
                    if (parser == boundary) throw original;
                }
            });
            assertSame(original,assertThrows(IllegalStateException.class,() -> boundary.parse(c)));
            assertSame(previous,c.captured("value").orElseThrow());
            assertTrue(c.captured("outer").isEmpty());
            assertEquals(1,c.getConsumedPosition().value());
            assertEquals(1,c.getCurrent().getTokens().size());
            assertSame(original,assertThrows(IllegalStateException.class,() -> replay().parse(c)).getCause());
            c.close();
        }
    }

    private static void terminalLeaves(Token token, java.util.List<Token> target) {
        if (token.parser instanceof org.unlaxer.parser.TerminalSymbol) target.add(token);
        else for (Token child : token.filteredChildren) terminalLeaves(child,target);
    }

    @Test public void committedTerminalTraceKeepsReplayPositionsAndDiscardsFailedBranches() throws Exception {
        for (String row : Files.readAllLines(Path.of("../conformance/owned-capture/trace.tsv"))) {
            if (row.startsWith("#")) continue;
            String[] e=row.split("\t");
            try (var c=new ParseContext(StringSource.createRootSource(decode(e[0])))) {
                var cursors=new java.util.IdentityHashMap<Token,String>();
                c.addParserListener(Name.of("owned-trace"),new org.unlaxer.listener.ParserListener() {
                    public void setLevel(org.unlaxer.listener.OutputLevel level) {}
                    public void onStart(Parser p,ParseContext c,TokenKind k,boolean inv) {}
                    public void onEnd(Parser p,Parsed result,ParseContext c,TokenKind k,boolean inv) {
                        if (p instanceof org.unlaxer.parser.TerminalSymbol && result.isSucceeded())
                            cursors.put(result.getRootToken(),c.getConsumedPosition().value()+":"+c.getMatchedPosition().value());
                    }
                });
                scenario(c,e[1],TokenKind.consumed,false);
                var leaves=new ArrayList<Token>();
                for (Token token:c.getCurrent().getTokens()) terminalLeaves(token,leaves);
                var actual=new ArrayList<String>();
                for (Token t:leaves) {
                    assertNotNull(cursors.get(t));
                    actual.add(t.parser.getClass().getSimpleName()+"@"+t.source.offsetFromRoot().value()+":"+
                        (t.source.offsetFromRoot().value()+t.source.codePointLength().value())+":"+
                        HexFormat.of().formatHex(t.source.sourceAsString().getBytes(StandardCharsets.UTF_8))+","+t.tokenKind+","+cursors.get(t));
                }
                assertEquals(e[1],e[2],String.join(";",actual));
            }
        }
    }

    @Test public void recoveredFailureDoesNotLeakBindingAndOuterRollbackDiscardsRecoveryCst() {
        try (var c=new ParseContext(StringSource.createRootSource("a;"))) {
            Parser parser=new SyncPointRecoveryParser(new Chain(cap(w("a")),w("z")),";");
            c.begin(parser);
            assertTrue(parser.parse(c).isSucceeded());
            assertTrue(c.captured("value").isEmpty());
            assertEquals(2,c.getConsumedPosition().value());
            assertFalse(c.getCurrent().getTokens().isEmpty());
            c.rollback(parser);
            assertEquals(0,c.getConsumedPosition().value());
            assertTrue(c.getCurrent().getTokens().isEmpty());
            assertTrue(c.captured("value").isEmpty());
        }
    }

    @Test public void failedReplayHasTheSharedNameAndCodePointDiagnostic() throws Exception {
        for (Memoization memo:Memoization.values()) for (ParseOptions.Diagnostics diagnostics:ParseOptions.Diagnostics.values()) {
            for (String row:Files.readAllLines(Path.of("../conformance/owned-capture/failures.tsv"))) {
                if (row.startsWith("#")) continue;
                String[] e=row.split("\t");
                try (var c=ParseContext.withOptions(StringSource.createRootSource(decode(e[0])),
                    ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                    assertTrue(scenario(c,e[1],TokenKind.valueOf(e[2]),Boolean.parseBoolean(e[3])).isFailed());
                    var failure=c.getParseFailureDiagnostics();
                    boolean deferred=diagnostics==ParseOptions.Diagnostics.DETAILED_ON_FAILURE;
                    assertEquals(e[1],deferred?0:Integer.parseInt(e[4]),failure.getFarthestOffset());
                    assertEquals(e[1],deferred?java.util.Set.of():java.util.Set.of(e[5]),failure.getExpectedTokens());
                }
            }
        }
    }

    @Test public void recursiveGeneralCaptureWrappersStopAtTheBudgetOnTwoMibStack() throws Exception {
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread thread=new Thread(null,() -> {
            try {
                for (boolean scoped:new boolean[] {false,true}) {
                    Parser parser=w("x");
                    for (int i=0;i<1500;i++) parser=scoped?new CaptureScope(parser):cap(parser);
                    Parser nested=parser;
                    try (var c=new ParseContext(StringSource.createRootSource("x"))) {
                        assertThrows(ParseLimitException.class,() -> nested.parse(c));
                        assertEquals(0,c.getConsumedPosition().value());
                        assertEquals(0,c.getMatchedPosition().value());
                        assertTrue(c.captured("value").isEmpty());
                        assertEquals(1,c.getTokenStack().size());
                    }
                }
            } catch (Throwable error) {failure.set(error);}
        },"owned-capture-budget",2*1024*1024);
        thread.start(); thread.join();
        if (failure.get()!=null) throw new AssertionError("general capture wrapper budget",failure.get());
    }
}
