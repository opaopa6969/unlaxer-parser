package org.unlaxer.parser.combinator;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.context.*;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.WordParser;

public class PegLookaheadTest {
    private static String text(String value) { return value.equals("~") ? "" : value.replace("\\r", "\r").replace("\\n", "\n"); }
    private static Parser assertion(Parser child, boolean positive) {
        return new PegLookahead(child, positive);
    }
    private static Parser child(String value) {
        return switch (value) {
            case "hint" -> new Chain(new WordParser("a"), new WordParser("b"), new Optional(new WordParser("!")));
            case "failure" -> new Chain(new WordParser("a"), new WordParser("b"), new WordParser("!"));
            case "nested" -> new Chain(assertion(new WordParser("a"), true), new WordParser("b"));
            case "~" -> new Chain();
            default -> new WordParser(text(value));
        };
    }
    @Test public void sharedIndependentOracle() throws Exception {
        var rows = Files.readAllLines(Path.of("../conformance/peg-assertion/cases.tsv")).stream().filter(row -> !row.startsWith("#")).toList();
        assertEquals(22, rows.size());
        for (var memo : Memoization.values()) for (var diagnostics : ParseOptions.Diagnostics.values()) for (String row : rows) {
            String[] c=row.split("\t", -1); assertEquals(13,c.length);
            String label=c[0]+"/"+memo+"/"+diagnostics;
            try (var context=ParseContext.withOptions(StringSource.createRootSource(text(c[1])), ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                context.getCurrent().setResetMatchedWithConsumed(false);
                if (!c[2].equals("~")) assertTrue(new WordParser(text(c[2])).parse(context).isSucceeded());
                if (!c[3].equals("~")) assertTrue(new MatchOnly(new WordParser(text(c[3]))).parse(context).isSucceeded());
                Parser probe=assertion(child(c[4]), Boolean.parseBoolean(c[5]));
                Parser parser=c[6].equals("~") ? probe : new Chain(probe,new WordParser(text(c[6])));
                var result=parser.parse(context);
                assertEquals(label,Boolean.parseBoolean(c[7]),result.isSucceeded());
                assertEquals(label,Integer.parseInt(c[8]),context.getConsumedPosition().value());
                assertEquals(label,Integer.parseInt(c[9]),context.getMatchedPosition().value());
                var failure=context.getParseFailureDiagnostics();
                int offset=diagnostics==ParseOptions.Diagnostics.DETAILED_ON_FAILURE ? 0 : Integer.parseInt(c[10]);
                assertEquals(label,offset,failure.getFarthestOffset());
                boolean hints=diagnostics!=ParseOptions.Diagnostics.DETAILED_ON_FAILURE && Boolean.parseBoolean(c[12]);
                assertEquals(label,hints,!failure.getExpectedTokens().isEmpty());
                if (c[6].equals("~")) assertTrue(label,result.getOriginalTokens().isEmpty());
            }
        }
    }
    private static final class State implements TransactionalState {
        int value=7;
        @Override public Runnable checkpoint() { int before=value;return () -> value=before; }
    }
    @Test public void rawCallbackStateAndProgressAreSpeculative() {
        for(var diagnostics:ParseOptions.Diagnostics.values()) {
            State state=new State();
            Parser child=new Chain() {
                @Override public Parsed parse(ParseContext context,TokenKind kind,boolean invert) {
                    state.value=99;
                    context.consume(new org.unlaxer.CodePointLength(1));
                    return Parsed.SUCCEEDED;
                }
            };
            try(var context=ParseContext.withOptions(StringSource.createRootSource("a"),ParseOptions.withMemoization(Memoization.OFF).withDiagnostics(diagnostics))) {
                context.registerTransactionalState(state);
                assertTrue(new PegLookahead(child).parse(context).isSucceeded());
                assertEquals(7,state.value);
                assertEquals(0,context.getConsumedPosition().value());
                assertEquals(0,context.getMatchedPosition().value());
                assertEquals(0,context.getParseFailureDiagnostics().getFarthestOffset());
                assertTrue(context.getCurrent().getTokens().isEmpty());
            }
        }
    }
    @Test public void exceptionsRestoreOpenChildFramesAndLeaveTheContextReusable() {
        State state=new State();
        Parser child=new WordParser("a") {
            @Override public org.unlaxer.Token getToken(ParseContext context,TokenKind kind,boolean invert) {
                state.value=99;
                context.begin(this);
                throw new IllegalStateException("callback failure");
            }
        };
        try(var context=new ParseContext(StringSource.createRootSource("a"))) {
            context.registerTransactionalState(state);
            assertEquals("callback failure",assertThrows(IllegalStateException.class,()->new PegLookahead(child,false).parse(context)).getMessage());
            assertEquals(7,state.value);
            assertEquals(1,context.getTokenStack().size());
            assertEquals(0,context.getConsumedPosition().value());
            assertEquals(0,context.getMatchedPosition().value());
            assertTrue(new WordParser("a").parse(context).isSucceeded());
        }
    }
    @Test public void recoveryDoesNotEscapeTheAssertion() {
        for(boolean positive:List.of(true,false)) {
            try(var context=new ParseContext(StringSource.createRootSource("bad;"))) {
                Parser recovery=new SyncPointRecoveryParser(new WordParser("ok"),";");
                assertEquals(positive,new PegLookahead(recovery,positive).parse(context).isSucceeded());
                assertTrue(RecoveryDiagnostic.from(context).isEmpty());
                assertTrue(context.getCurrent().getTokens().isEmpty());
                assertEquals(0,context.getConsumedPosition().value());
                assertEquals(0,context.getMatchedPosition().value());
            }
        }
    }
    @Test public void resourceExhaustionIsNeverAFalseNegativeSuccess() {
        for(var diagnostics:ParseOptions.Diagnostics.values()) {
            Parser child=new WordParser("a");
            for(int i=0;i<1500;i++)child=new PegLookahead(child);
            Parser probe=new PegLookahead(child,false);
            try(var context=ParseContext.withOptions(StringSource.createRootSource("a"),ParseOptions.withMemoization(Memoization.OFF).withDiagnostics(diagnostics))) {
                assertThrows(ParseLimitException.class,()->probe.parse(context));
                assertEquals(1,context.getTokenStack().size());
                assertEquals(0,context.getConsumedPosition().value());
                assertEquals(0,context.getMatchedPosition().value());
                assertTrue(context.getCurrent().getTokens().isEmpty());
                assertThrows(ParseLimitException.class,()->new WordParser("a").parse(context));
            }
        }
    }
    @Test public void explicitPolarityIsIndependentOfClassicConsumeAndInvertArguments() {
        try(var context=new ParseContext(StringSource.createRootSource("a"))) {
            assertTrue(new PegLookahead(new WordParser("a")).parse(context,TokenKind.virtualTokenMatchOnly,true).isSucceeded());
            assertEquals(0,context.getConsumedPosition().value());
            assertEquals(0,context.getMatchedPosition().value());
            assertTrue(context.getCurrent().getTokens().isEmpty());
        }
    }
    @Test public void portableMetadataOnExistingTokensIsRestored() {
        try(var context=new ParseContext(StringSource.createRootSource("ab"))) {
            var metadata=new PortableTokenMetadata(context);
            var token=new WordParser("a").parse(context).getRootToken();
            var id=metadata.register(token);
            assertTrue(metadata.putExtra(id,"label","before"));
            Parser child=new WordParser("b") {
                @Override public org.unlaxer.Token getToken(ParseContext current,TokenKind kind,boolean invert) {
                    assertTrue(metadata.putExtra(id,"label","inside"));
                    return super.getToken(current,kind,invert);
                }
            };
            for(int retry=0;retry<2;retry++) {
                assertTrue(new PegLookahead(child).parse(context).isSucceeded());
                assertEquals("before",metadata.info(id).orElseThrow().extra().get("label"));
                assertEquals(1,context.getConsumedPosition().value());
                assertEquals(1,context.getMatchedPosition().value());
                assertEquals(1,context.getCurrent().getTokens().size());
            }
        }
    }
}
