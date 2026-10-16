package org.unlaxer.context;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.parser.*;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.*;

/** Independent Classic separation oracle; no host-specific parser class names in the fixture. */
public class NullableSeparationStateTest {
    private static final class State implements TransactionalState {
        int value=7;
        public Runnable checkpoint(){int saved=value;return ()->value=saved;}
    }
    private static class Opaque extends ConstructedAbstractParser {
        private static final long serialVersionUID=1L;
        private final Parser delegate;
        Opaque(Parser delegate){super(new Parsers(delegate));this.delegate=delegate;}
        @Override public Parser createParser(){return delegate;}
        @Override public ChildOccurs getChildOccurs(){return ChildOccurs.single;}
    }
    private static Parser zero(){return new Repeat(new WordParser("unused"),0,0);}
    private static Parser separated(Parser item,Parser separator){return new Chain(item,new ZeroOrMore(new Chain(separator,item)));}
    private static Parser child(State state,AtomicInteger calls,String binding,String scenario,boolean separator){
        return new Opaque(zero()){
            @Override public Parsed parse(ParseContext c,TokenKind kind,boolean invert){
                int count=calls.incrementAndGet();
                Parser effect=new Repeat(new WordParser("unused"),0,0){
                    @Override public Parsed parse(ParseContext context,TokenKind k,boolean i){state.value++;return new NamedCapture(binding,zero()).parse(context,k,i);}
                };
                boolean fail=separator?scenario.equals("separator-fails"):scenario.equals("first-item-fails")||scenario.equals("tail-item-fails")&&count>1;
                return new Chain(effect,fail?ErrorMessageParser.expected("child failure"):zero()).parse(c,kind,invert);
            }
        };
    }
    @Test public void nullableTailCountsOnceAndRollsBackFailedSeparatorOrItemAcrossAllModes() throws Exception {
        for(var memo:Memoization.values())for(var mode:ParseOptions.Diagnostics.values())
        for(String row:Files.readAllLines(Path.of("../conformance/nullable-separation/state.tsv"))){
            if(row.startsWith("#"))continue;var e=row.split("\t");
            try(var c=ParseContext.withOptions(StringSource.createRootSource("p😀x"),ParseOptions.withMemoization(memo).withDiagnostics(mode))){
                State state=new State();c.registerTransactionalState(state);
                assertTrue(new NamedCapture("word",new WordParser("p")).parse(c).isSucceeded());
                assertTrue(new WordParser("😀").parse(c,TokenKind.matchOnly,false).isSucceeded());
                c.getCurrent().setResetMatchedWithConsumed(Boolean.parseBoolean(e[2]));
                AtomicInteger items=new AtomicInteger(),separators=new AtomicInteger();
                Parser parser=separated(child(state,items,"word",e[1],false),child(state,separators,"sep",e[1],true));
                c.beginPreservingMatched(parser);
                Parsed result=parser.parse(c,TokenKind.valueOf(e[3]),Boolean.parseBoolean(e[4]));
                assertEquals(e[0],Boolean.parseBoolean(e[5]),result.isSucceeded());
                assertEquals(e[0],Integer.parseInt(e[6]),c.getConsumedPosition().value());
                assertEquals(e[0],Integer.parseInt(e[7]),c.getMatchedPosition().value());
                assertEquals(e[0],Integer.parseInt(e[8]),state.value);
                assertEquals(e[0],e[9].equals("empty")?"":"p",c.captured("word").orElseThrow().text());
                assertEquals(e[0],Boolean.parseBoolean(e[10]),c.captured("sep").isPresent());
                assertEquals(e[0],Boolean.parseBoolean(e[11]),c.getCurrent().isResetMatchedWithConsumed());
                assertEquals(e[0],Integer.parseInt(e[12]),items.get());
                assertEquals(e[0],Integer.parseInt(e[13]),separators.get());
                var diagnostic=c.getParseFailureDiagnostics();
                assertEquals(e[0],mode==ParseOptions.Diagnostics.DETAILED_ON_FAILURE?0:Integer.parseInt(e[15]),diagnostic.getFarthestOffset());
                assertEquals(e[0],mode!=ParseOptions.Diagnostics.DETAILED_ON_FAILURE&&Boolean.parseBoolean(e[16]),!diagnostic.getExpectedTokens().isEmpty());
                if(result.isSucceeded()){
                    assertEquals(e[0],2,result.getOriginalTokens().size());
                    assertEquals(e[0],Integer.parseInt(e[14]),result.getOriginalTokens().get(1).getOriginalChildren().size());
                    assertEquals(e[0],TokenKind.valueOf(e[3]),result.getRootToken().tokenKind);
                    assertTrue(e[0],result.getRootToken().source.isEmpty());
                    assertEquals(e[0],2,result.getRootToken().getOriginalChildren().size());
                    assertEquals(e[0],2,result.getRootToken().filteredChildren.size());
                }
                c.rollback(parser);
                assertEquals(e[0],7,state.value);assertEquals(e[0],"p",c.captured("word").orElseThrow().text());assertFalse(e[0],c.captured("sep").isPresent());
                assertEquals(e[0],1,c.getConsumedPosition().value());assertEquals(e[0],2,c.getMatchedPosition().value());
                assertEquals(e[0],2,c.getCurrent().getTokens().size());assertEquals(e[0],1,c.getTokenStack().size());
            }
        }
    }
    @Test public void enclosingBoundsRollbackRetryAndScopePreserveOwnedBindings(){
        for(var memo:Memoization.values())for(var mode:ParseOptions.Diagnostics.values())
        try(var c=ParseContext.withOptions(StringSource.createRootSource("p😀x"),ParseOptions.withMemoization(memo).withDiagnostics(mode))){
            State state=new State();c.registerTransactionalState(state);
            assertTrue(new NamedCapture("word",new WordParser("p")).parse(c).isSucceeded());
            assertTrue(new WordParser("😀").parse(c,TokenKind.matchOnly,false).isSucceeded());
            c.getCurrent().setResetMatchedWithConsumed(false);
            AtomicInteger items=new AtomicInteger(),separators=new AtomicInteger();
            Parser list=separated(child(state,items,"word","both-empty",false),child(state,separators,"sep","both-empty",true));
            assertTrue(new Repeat(list,0,0).parse(c).isSucceeded());assertEquals(0,items.get());assertEquals(0,separators.get());
            assertEquals(7,state.value);assertEquals("p",c.captured("word").orElseThrow().text());assertEquals(2,c.getMatchedPosition().value());
            assertTrue(new Repeat(list,2,2).parse(c).isFailed());assertEquals(7,state.value);assertFalse(c.captured("sep").isPresent());
            assertEquals("p",c.captured("word").orElseThrow().text());assertEquals(1,c.getConsumedPosition().value());assertEquals(2,c.getMatchedPosition().value());
            c.beginPreservingMatched(list);assertTrue(list.parse(c).isSucceeded());assertEquals(10,state.value);assertEquals("",c.captured("word").orElseThrow().text());
            c.rollback(list);assertEquals(7,state.value);assertEquals("p",c.captured("word").orElseThrow().text());assertFalse(c.captured("sep").isPresent());
            assertTrue(new CaptureScope(list).parse(c).isSucceeded());assertEquals(10,state.value);assertEquals("p",c.captured("word").orElseThrow().text());assertFalse(c.captured("sep").isPresent());
            assertTrue(list.parse(c).isSucceeded());assertEquals(13,state.value);assertEquals("",c.captured("word").orElseThrow().text());assertEquals("",c.captured("sep").orElseThrow().text());
            assertEquals(1,c.getConsumedPosition().value());assertEquals(2,c.getMatchedPosition().value());assertEquals(1,c.getTokenStack().size());
        }
    }
    @Test public void emptyNonemptyAndUnboundOwnedReplayComposeAsItemAndSeparator(){
        for(var memo:Memoization.values())for(var mode:ParseOptions.Diagnostics.values()){
            var options=ParseOptions.withMemoization(memo).withDiagnostics(mode);
            try(var c=ParseContext.withOptions(StringSource.createRootSource(""),options)){
                assertTrue(new NamedCapture("word",zero()).parse(c).isSucceeded());
                var result=separated(new ReplayParser("word"),new ReplayParser("word")).parse(c);
                assertTrue(result.isSucceeded());assertEquals(2,result.getOriginalTokens().size());assertEquals(1,result.getOriginalTokens().get(1).getOriginalChildren().size());
                assertEquals(0,c.getConsumedPosition().value());assertEquals(0,c.getMatchedPosition().value());
                assertTrue(separated(new ReplayParser("missing"),zero()).parse(c).isFailed());
                assertTrue(separated(new ReplayParser("word"),new ReplayParser("missing")).parse(c).isSucceeded());
            }
            try(var c=ParseContext.withOptions(StringSource.createRootSource("😀😀:😀"),options)){
                assertTrue(new NamedCapture("word",new WordParser("😀")).parse(c).isSucceeded());
                assertTrue(separated(new ReplayParser("word"),new WordParser(":")).parse(c).isSucceeded());
                assertEquals(4,c.getConsumedPosition().value());assertEquals(4,c.getMatchedPosition().value());assertEquals("😀",c.captured("word").orElseThrow().text());
            }
            try(var c=ParseContext.withOptions(StringSource.createRootSource("aa"),options)){
                assertTrue(new NamedCapture("word",zero()).parse(c).isSucceeded());
                assertTrue(separated(new WordParser("a"),new ReplayParser("word")).parse(c).isSucceeded());
                assertEquals(2,c.getConsumedPosition().value());assertEquals(2,c.getMatchedPosition().value());
            }
        }
    }
    private static final class Recursive extends ConstructedAbstractParser {
        private static final long serialVersionUID=1L;
        Recursive(){super(new Parsers());}
        @Override public ChildOccurs getChildOccurs(){return ChildOccurs.single;}
    }
    @Test public void firstItemSeparatorAndTailItemCannotSwallowStickyResourceAbort(){
        for(var memo:Memoization.values())for(var mode:ParseOptions.Diagnostics.values())for(String phase:new String[]{"first","separator","tail"})
        try(var c=ParseContext.withOptions(StringSource.createRootSource("p😀"),ParseOptions.withMemoization(memo).withDiagnostics(mode))){
            State state=new State();c.registerTransactionalState(state);assertTrue(new NamedCapture("word",new WordParser("p")).parse(c).isSucceeded());
            assertTrue(new WordParser("😀").parse(c,TokenKind.matchOnly,false).isSucceeded());
            AtomicInteger items=new AtomicInteger();
            Parser resource=new Opaque(zero()){
                @Override public Parsed parse(ParseContext context,TokenKind kind,boolean invert){
                    state.value=99;context.capture("effect",new CapturedValue("",CapturedValue.Provenance.INPUT,1,1));return new Recursive().parse(context,kind,invert);
                }
            };
            Parser item=new Opaque(zero()){
                @Override public Parsed parse(ParseContext context,TokenKind kind,boolean invert){
                    return (phase.equals("first")||phase.equals("tail")&&items.incrementAndGet()>1?resource:zero()).parse(context,kind,invert);
                }
            };
            Parser parser=new NamedCapture("outer",separated(item,phase.equals("separator")?resource:zero()));
            var error=assertThrows(ParseLimitException.class,()->parser.parse(c));assertSame(error,assertThrows(ParseLimitException.class,()->zero().parse(c)));
            assertEquals(7,state.value);assertFalse(c.captured("effect").isPresent());assertFalse(c.captured("outer").isPresent());assertEquals("p",c.captured("word").orElseThrow().text());
            assertEquals(1,c.getConsumedPosition().value());assertEquals(2,c.getMatchedPosition().value());assertEquals(1,c.getTokenStack().size());assertEquals(2,c.getCurrent().getTokens().size());
        }
    }
}
