package org.unlaxer.context;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.parser.*;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.WordParser;

/** Shared no-progress oracle, including opaque stateful children and enclosing rollback. */
public class NullableRepeatStateTest {
    private static final class State implements TransactionalState {
        int value=7;
        public Runnable checkpoint(){int saved=value;return ()->value=saved;}
    }
    private static final class Opaque extends ConstructedAbstractParser {
        private static final long serialVersionUID=1L;
        private final Parser delegate;
        Opaque(Parser delegate){super(new Parsers(delegate));this.delegate=delegate;}
        @Override public Parser createParser(){return delegate;}
        @Override public ChildOccurs getChildOccurs(){return ChildOccurs.single;}
    }
    private static Parser zero(){return new Repeat(new WordParser("unused"),0,0);}
    @Test public void nullableSuccessCountsOnceAndFailureRestoresStateAcrossAllModes() throws Exception {
        for(var memo:Memoization.values())for(var mode:ParseOptions.Diagnostics.values())
        for(String row:Files.readAllLines(Path.of("../conformance/nullable-repeat/state.tsv"))){
            if(row.startsWith("#"))continue;var e=row.split("\t");
            try(var c=ParseContext.withOptions(StringSource.createRootSource("p😀x"),ParseOptions.withMemoization(memo).withDiagnostics(mode))){
                State state=new State();c.registerTransactionalState(state);
                assertTrue(new NamedCapture("before",new WordParser("p")).parse(c).isSucceeded());
                assertTrue(new WordParser("😀").parse(c,TokenKind.matchOnly,false).isSucceeded());
                c.getCurrent().setResetMatchedWithConsumed(Boolean.parseBoolean(e[3]));
                AtomicInteger calls=new AtomicInteger();
                Parser effect=new Repeat(new WordParser("unused"),0,0){
                    @Override public Parsed parse(ParseContext context,TokenKind kind,boolean invert){
                        calls.incrementAndGet();state.value++;
                        context.capture("effect",new CapturedValue("",CapturedValue.Provenance.INPUT,context.getConsumedPosition().value(),context.getConsumedPosition().value()));
                        return super.parse(context,kind,invert);
                    }
                };
                Parser child=new Opaque(new Chain(effect,e[1].equals("empty")?zero():ErrorMessageParser.expected("child failure")));
                Parser repeat=new Repeat(child,Integer.parseInt(e[2]),Integer.MAX_VALUE);
                c.beginPreservingMatched(repeat);
                Parsed result=repeat.parse(c,TokenKind.valueOf(e[4]),Boolean.parseBoolean(e[5]));
                assertEquals(e[0],Boolean.parseBoolean(e[6]),result.isSucceeded());
                assertEquals(e[0],Integer.parseInt(e[7]),c.getConsumedPosition().value());
                assertEquals(e[0],Integer.parseInt(e[8]),c.getMatchedPosition().value());
                assertEquals(e[0],Integer.parseInt(e[9]),state.value);
                assertEquals(e[0],Boolean.parseBoolean(e[10]),c.captured("effect").isPresent());
                assertEquals(e[0],Boolean.parseBoolean(e[11]),c.getCurrent().isResetMatchedWithConsumed());
                assertEquals(e[0],Integer.parseInt(e[12]),calls.get());
                assertEquals(e[0],"p",c.captured("before").orElseThrow().text());
                if(result.isSucceeded()){
                    assertEquals(e[0],e[1].equals("empty")?1:0,result.getOriginalTokens().size());
                    assertEquals(e[0],TokenKind.valueOf(e[4]),result.getRootToken().tokenKind);
                    assertTrue(result.getRootToken().source.isEmpty());
                }
                c.rollback(repeat);
                assertEquals(e[0],7,state.value);assertFalse(e[0],c.captured("effect").isPresent());
                assertEquals(e[0],1,c.getConsumedPosition().value());assertEquals(e[0],2,c.getMatchedPosition().value());
                assertEquals(e[0],2,c.getCurrent().getTokens().size());assertEquals(e[0],1,c.getTokenStack().size());
            }
        }
    }
    private static final class Recursive extends ConstructedAbstractParser {
        private static final long serialVersionUID=1L;
        Recursive(){super(new Parsers());}
        @Override public ChildOccurs getChildOccurs(){return ChildOccurs.single;}
    }
    @Test public void nullableOuterRepeatCannotConvertResourceFailureIntoZeroMatches(){
        for(var memo:Memoization.values())for(var mode:ParseOptions.Diagnostics.values()){
            State state=new State();
            try(var c=ParseContext.withOptions(StringSource.createRootSource("p😀"),ParseOptions.withMemoization(memo).withDiagnostics(mode))){
                c.registerTransactionalState(state);
                assertTrue(new NamedCapture("before",new WordParser("p")).parse(c).isSucceeded());
                assertTrue(new WordParser("😀").parse(c,TokenKind.matchOnly,false).isSucceeded());
                Parser effect=new Repeat(new WordParser("unused"),0,0){
                    @Override public Parsed parse(ParseContext context,TokenKind kind,boolean invert){
                        state.value=99;context.capture("effect",new CapturedValue("",CapturedValue.Provenance.INPUT,1,1));
                        return super.parse(context,kind,invert);
                    }
                };
                Parser child=new Opaque(new Chain(effect,new Recursive()));
                Parser parser=new NamedCapture("outer",new Repeat(child,0,Integer.MAX_VALUE));
                var error=assertThrows(ParseLimitException.class,()->parser.parse(c));
                assertSame(error,assertThrows(ParseLimitException.class,()->zero().parse(c)));
                assertEquals(7,state.value);assertFalse(c.captured("effect").isPresent());assertFalse(c.captured("outer").isPresent());
                assertEquals("p",c.captured("before").orElseThrow().text());
                assertEquals(1,c.getConsumedPosition().value());assertEquals(2,c.getMatchedPosition().value());
                assertEquals(1,c.getTokenStack().size());assertEquals(2,c.getCurrent().getTokens().size());
            }
        }
    }

}
