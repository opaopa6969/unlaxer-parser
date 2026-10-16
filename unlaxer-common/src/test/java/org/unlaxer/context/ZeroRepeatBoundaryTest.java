package org.unlaxer.context;

import static org.junit.Assert.*;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.parser.*;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.*;
import java.nio.file.*;
import java.util.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class ZeroRepeatBoundaryTest {
    private static final class State implements TransactionalState {
        int value=7;
        @Override public Runnable checkpoint(){int saved=value;return () -> value=saved;}
    }
    private static final class Bound extends ConstructedOccurs implements SafeSuccessMemoizable {
        private static final long serialVersionUID=1L;
        private final int minimum,maximum;
        private final Parser child;
        final AtomicInteger getters=new AtomicInteger(),terminators=new AtomicInteger();
        Bound(Parser child,int min,int max){super(new Parsers());this.child=child;minimum=min;maximum=max;}
        @Override public int min(){return minimum;}
        @Override public int max(){return maximum;}
        @Override public Parser getChild(){getters.incrementAndGet();return child;}
        @Override public ChildOccurs getChildOccurs(){return ChildOccurs.multi;}
        @Override public Optional<Parser> getTerminator(){terminators.incrementAndGet();throw new AssertionError("unused terminator getter");}
    }
    private static final class LazyBoundary extends LazyOccurs {
        private static final long serialVersionUID=1L;
        @Override public int min(){return 0;}
        @Override public int max(){return 0;}
        @Override public Supplier<Parser> getLazyParser(){throw new AssertionError("zero count must not resolve lazy child");}
        @Override public Optional<Parser> getLazyTerminatorParser(){throw new AssertionError("zero count must not resolve lazy terminator");}
    }
    private static Parser word(String text){return new WordParser(text);}
    private static void baseline(ParseContext c,State state){
        c.registerTransactionalState(state);
        assertTrue(new NamedCapture("before",word("p")).parse(c).isSucceeded());
        assertTrue(word("😀").parse(c,TokenKind.matchOnly,false).isSucceeded());
    }
    @Test public void maximumZeroDoesNotAttemptItsMatchingChild(){
        try(var c=new ParseContext(StringSource.createRootSource("a"))){
            assertTrue(new Repeat(word("a"),0,0).parse(c).isSucceeded());
            assertEquals(0,c.getConsumedPosition().value());
        }
    }
    @Test public void publicTerminatorAndLazyFormsSkipEvaluationAndFirstSetResolution(){
        for(Memoization memo:Memoization.values()) for(ParseOptions.Diagnostics mode:ParseOptions.Diagnostics.values())
        for(TokenKind kind:TokenKind.values()) for(boolean invert:List.of(false,true)){
            Parser forbidden=new WordParser("😀"){
                @Override public Token getToken(ParseContext c,TokenKind k,boolean i){throw new AssertionError("zero count child/terminator executed");}
            };
            for(Parser parser:List.of(new Repeat(forbidden,0,0).newWithTerminator(forbidden),new LazyBoundary()))
            try(var c=ParseContext.withOptions(StringSource.createRootSource("p😀x"),ParseOptions.withMemoization(memo).withDiagnostics(mode))){
                State state=new State();baseline(c,state);
                assertEquals(FirstSet.EPSILON,FirstSets.of(parser));
                assertTrue(parser.parse(c,kind,invert).isSucceeded());
                assertEquals(1,c.getConsumedPosition().value());assertEquals(2,c.getMatchedPosition().value());
                assertEquals(7,state.value);assertEquals("p",c.captured("before").orElseThrow().text());
            }
        }
        Bound invalid=new Bound(word("x"),2,1);
        assertEquals(FirstSet.EMPTY,FirstSets.of(invalid));assertEquals(0,invalid.getters.get());
    }
    @Test public void sharedBoundedZeroOracleSkipsChildrenTerminatorsAndRetainsBothCursors() throws Exception {
        for(Memoization memo:Memoization.values()) for(ParseOptions.Diagnostics mode:ParseOptions.Diagnostics.values()) {
            for(String row:Files.readAllLines(Path.of("../conformance/zero-repeat/bounded.tsv"))){
                if(row.startsWith("#"))continue;String[] e=row.split("\t");
                try(var c=ParseContext.withOptions(StringSource.createRootSource("p😀x"),ParseOptions.withMemoization(memo).withDiagnostics(mode))){
                    State state=new State();baseline(c,state);
                    c.getCurrent().setResetMatchedWithConsumed(Boolean.parseBoolean(e[4]));
                    var calls=new AtomicInteger();
                    Parser child=new WordParser(e[1].equals("failure")?"z":"😀"){
                        @Override public Token getToken(ParseContext c,TokenKind kind,boolean invert){
                            calls.incrementAndGet();state.value=99;
                            if(e[1].equals("throw"))throw new IllegalStateException("child must not run");
                            if(e[1].equals("recursive"))return parse(c,kind,invert).getRootToken();
                            return super.getToken(c,kind,invert);
                        }
                    };
                    Bound parser=new Bound(child,Integer.parseInt(e[2]),Integer.parseInt(e[3]));
                    Parsed result=parser.parse(c,TokenKind.valueOf(e[5]),Boolean.parseBoolean(e[6]));
                    assertEquals(e[0],Boolean.parseBoolean(e[7]),result.isSucceeded());
                    assertEquals(e[0],Integer.parseInt(e[8]),c.getConsumedPosition().value());
                    assertEquals(e[0],Integer.parseInt(e[9]),c.getMatchedPosition().value());
                    assertEquals(e[0],0,calls.get());assertEquals(e[0],0,parser.getters.get());assertEquals(e[0],0,parser.terminators.get());
                    assertEquals(e[0],7,state.value);assertEquals(e[0],"p",c.captured("before").orElseThrow().text());
                    assertEquals(e[0],Boolean.parseBoolean(e[4]),c.getCurrent().isResetMatchedWithConsumed());
                    assertEquals(e[0],1,c.getTokenStack().size());
                    if(result.isSucceeded()){
                        assertEquals(e[0],3,c.getCurrent().getTokens().size());
                        assertEquals(e[0],TokenKind.valueOf(e[5]),result.getRootToken().tokenKind);
                        assertEquals(e[0],Integer.parseInt(e[10]),result.getRootToken().source.offsetFromRoot().value());
                        assertTrue(result.getRootToken().source.isEmpty());assertTrue(result.getOriginalTokens().isEmpty());
                        assertTrue(e[0],c.getParseFailureDiagnostics().getExpectedTokens().isEmpty());
                    }else{
                        assertEquals(e[0],2,c.getCurrent().getTokens().size());
                        var diagnostic=c.getParseFailureDiagnostics(); // no unreachable child resolution
                        boolean deferred=mode==ParseOptions.Diagnostics.DETAILED_ON_FAILURE;
                        assertEquals(e[0],deferred?0:2,diagnostic.getFarthestOffset());
                        assertEquals(e[0],!deferred,!diagnostic.getExpectedTokens().isEmpty());
                        assertEquals(e[0],0,parser.getters.get());
                    }
                }
            }
        }
    }
    private static final class LazyCheck extends LazyZero {
        @Override public Supplier<Parser> getLazyParser(){return WildCardCharacterParser::new;}
        @Override public Optional<Parser> getLazyTerminatorParser(){return Optional.empty();}
    }
    private static final class NestedCheck extends LazyZero {
        private static final long serialVersionUID=1L;
        private final Parser child;
        NestedCheck(Parser child){this.child=child;}
        @Override public Supplier<Parser> getLazyParser(){return ()->child;}
        @Override public Optional<Parser> getLazyTerminatorParser(){return Optional.empty();}
    }
    @Test public void explicitZeroChecksCannotSwallowResourceFailureOnAStandardStack() throws Exception {
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread thread=new Thread(null,()->{
            try{
                for(Memoization memo:Memoization.values()) for(ParseOptions.Diagnostics mode:ParseOptions.Diagnostics.values()){
                    Parser parser=word("x");for(int i=0;i<1500;i++)parser=new NestedCheck(parser);
                    final Parser nested=parser;
                    try(var c=ParseContext.withOptions(StringSource.createRootSource("x"),ParseOptions.withMemoization(memo).withDiagnostics(mode))){
                        var limit=assertThrows(ParseLimitException.class,()->nested.parse(c));
                        assertSame(limit,assertThrows(ParseLimitException.class,()->word("x").parse(c)));
                        assertEquals(0,limit.getSuppressed().length);
                    }
                }
            }catch(Throwable error){failure.set(error);}
        },"legacy-zero-resource",2*1024*1024);
        thread.start();thread.join(10000);assertFalse(thread.isAlive());
        if(failure.get()!=null)throw new AssertionError("controlled resource boundary",failure.get());
    }
    private static Parser legacy(String variant){
        return switch(variant){
            case "eof" -> new EndOfSourceParser();
            case "zero" -> new Zero(WildCardCharacterParser.class);
            case "lazy-zero" -> new LazyCheck();
            default -> throw new AssertionError(variant);
        };
    }
    private static Parser wrap(Parser p,String wrapper){return switch(wrapper){
        case "none" -> p;case "consume" -> new DoConsumePropagationStopper(p);
        case "invert" -> new InvertMatchPropagationStopper(p);case "match" -> new MatchOnly(p);
        default -> throw new AssertionError(wrapper);
    };}
    @Test public void sharedHistoricalZeroAndEofOraclePreservesModesStoppersAndDiagnostics() throws Exception {
        var rows=Files.readAllLines(Path.of("../conformance/zero-repeat/legacy-zero.tsv")).stream().filter(r->!r.startsWith("#")).map(r->r.split("\t")).toList();
        for(Memoization memo:Memoization.values()) for(ParseOptions.Diagnostics mode:ParseOptions.Diagnostics.values())
        for(String variant:List.of("eof","zero","lazy-zero")) for(String wrapper:List.of("none","consume","invert","match")) for(String[] input:rows){
            String kind=wrapper.equals("consume")?"consumed":wrapper.equals("match")?"matchOnly":input[2];
            String invert=wrapper.equals("invert")?"false":input[3];
            String[] e=rows.stream().filter(r->r[0].equals(input[0])&&r[1].equals(input[1])&&r[2].equals(kind)&&r[3].equals(invert)).findFirst().orElseThrow();
            String label=String.join("/",variant,wrapper,String.join(",",input),memo.toString(),mode.toString());
            try(var c=ParseContext.withOptions(StringSource.createRootSource(input[0].equals("ahead")?"a😀":input[0].equals("start")?"😀":""),ParseOptions.withMemoization(memo).withDiagnostics(mode))){
                if(input[0].equals("ahead")){assertTrue(word("a").parse(c).isSucceeded());assertTrue(word("😀").parse(c,TokenKind.matchOnly,false).isSucceeded());}
                c.getCurrent().setResetMatchedWithConsumed(Boolean.parseBoolean(input[1]));
                Parsed result=wrap(legacy(variant),wrapper).parse(c,TokenKind.valueOf(input[2]),Boolean.parseBoolean(input[3]));
                assertEquals(label,Boolean.parseBoolean(e[4]),result.isSucceeded());
                assertEquals(label,Integer.parseInt(e[5]),c.getConsumedPosition().value());assertEquals(label,Integer.parseInt(e[6]),c.getMatchedPosition().value());
                var diagnostic=c.getParseFailureDiagnostics();
                boolean deferred=mode==ParseOptions.Diagnostics.DETAILED_ON_FAILURE;
                assertEquals(label,deferred?0:Integer.parseInt(e[7]),diagnostic.getFarthestOffset());
                assertEquals(label,!deferred,!diagnostic.getExpectedTokens().isEmpty());
                if(result.isSucceeded()){
                    assertEquals(label,TokenKind.valueOf(kind),result.getRootToken().tokenKind);
                    assertEquals(label,Integer.parseInt(e[8]),result.getRootToken().source.offsetFromRoot().value());
                    assertTrue(result.getRootToken().source.isEmpty());
                }
                assertEquals(label,1,c.getTokenStack().size());
            }
        }
    }
    @Test public void zeroSuccessMemoHitPreservesTheAdvancedMatchedCursor(){
        for(TokenKind kind:TokenKind.values())try(var c=ParseContext.withOptions(StringSource.createRootSource("p😀"),ParseOptions.withMemoization(Memoization.SAFE_FAILURES))){
            State state=new State();baseline(c,state);c.getCurrent().setResetMatchedWithConsumed(true);
            Bound parser=new Bound(word("a"),0,0);
            c.beginPreservingMatched(parser);assertTrue(parser.parse(c,kind,false).isSucceeded());c.rollback(parser);
            long hits=c.getPackratMemoTable().successHits();
            assertTrue(parser.parse(c,kind,false).isSucceeded());
            assertEquals(hits+1,c.getPackratMemoTable().successHits());
            assertEquals(1,c.getConsumedPosition().value());assertEquals(2,c.getMatchedPosition().value());
            assertEquals(0,parser.getters.get());
        }
    }
}
