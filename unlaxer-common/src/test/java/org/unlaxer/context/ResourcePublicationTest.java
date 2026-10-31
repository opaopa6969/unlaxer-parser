package org.unlaxer.context;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.listener.*;
import org.unlaxer.parser.*;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.*;

/** Public-boundary oracle shared with Rust; forced notifications avoid relying on JVM stack size. */
public class ResourcePublicationTest {
    private static final class State implements TransactionalState {
        int value=7;
        public Runnable checkpoint(){int saved=value;return ()->value=saved;}
    }
    private static class Limit extends NoneChildParser {
        private static final long serialVersionUID=1L;
        @Override public Parser createParser(){return this;}
        @Override public Parsed parse(ParseContext c,TokenKind kind,boolean invert){
            for(int n=0;n<=ParseContext.MAX_PARSE_DEPTH;n++)c.startParse(this,c,kind,invert);
            throw new AssertionError("missing resource limit");
        }
    }
    private static Parser wrapped(String name,Parser child){return switch(name){
        case "chain"->child;
        case "input-boundary"->CaptureBoundaryParser.input(CaptureBoundaryParser.Kind.VALUE,child);
        case "match-only"->new MatchOnly(child);
        case "peg"->new PegLookahead(child);
        case "capture"->new NamedCapture("inside",child);
        case "scope"->new CaptureScope(child);
        case "repeat"->new ZeroOrMore(child);
        case "terminator"->new ZeroOrMore(new WordParser("q"),child);
        case "terminated-body"->new ZeroOrMore(child,new WordParser("q"));
        default->throw new AssertionError(name);
    };}
    private static int frameCount(ParseContext c,String name) throws Exception {
        var field=ParseContext.class.getDeclaredField(name);field.setAccessible(true);var value=field.get(c);return value instanceof java.util.Collection<?> collection?collection.size():(Integer)value;
    }
    @Test public void sharedResourceAbortRestoresThePublicEntryAndOuterManualOwner() throws Exception {
        for(var memo:Memoization.values())for(var mode:ParseOptions.Diagnostics.values())
        for(var row:Files.readAllLines(Path.of("../conformance/resource-abort/cases.tsv"))){
            if(row.startsWith("#"))continue;var e=row.split("\t");String label=row+"/"+memo+"/"+mode;
            State state=new State();AtomicInteger closed=new AtomicInteger(),events=new AtomicInteger();
            ParseContext c=ParseContext.withOptions(StringSource.createRootSource("paaaa"),ParseOptions.withMemoization(memo).withDiagnostics(mode));
            c.registerTransactionalState(state);c.getCurrent().setResetMatchedWithConsumed(false);
            Token prior=new NamedCapture("word",new WordParser("p")).parse(c).getRootToken();
            assertTrue(new WordParser("a").parse(c,TokenKind.matchOnly,false).isSucceeded());
            PortableTokenMetadata metadata=new PortableTokenMetadata(c);var old=metadata.register(prior);metadata.putExtra(old,"label","before");
            AtomicReference<PortableTokenMetadata.NodeId> fresh=new AtomicReference<>();
            Choice choice=new Choice(new WordParser("a"),new WordParser("b"));NonOrdered ordered=new NonOrdered(new WordParser("a"));
            Parser previous=new WordParser("previous");Parsers previousOrder=new Parsers(previous);c.choose(choice,previous);c.order(ordered,previousOrder);
            Parser manual=new WordParser("manual");
            if(Boolean.parseBoolean(e[1])){
                c.beginPreservingMatched(manual);assertTrue(new NamedCapture("word",new WordParser("a")).parse(c).isSucceeded());state.value=11;
                metadata.putExtra(old,"label","outer");
            }
            c.addParserListener(Name.of("events"),new ParserListener(){
                public void setLevel(OutputLevel level){}
                public void onStart(Parser p,ParseContext context,TokenKind kind,boolean invert){events.incrementAndGet();}
                public void onEnd(Parser p,Parsed parsed,ParseContext context,TokenKind kind,boolean invert){events.incrementAndGet();}
            });
            c.addTransactionListener(Name.of("close"),new TransactionListener(){
                public void setLevel(OutputLevel level){} public void onOpen(ParseContext context){} public void onBegin(ParseContext context,Parser owner){}
                public void onClose(ParseContext context){closed.incrementAndGet();assertEquals(label,7,state.value);assertEquals(label,1,context.getTokenStack().size());}
            });
            Parser effect=new WordParser("a"){
                @Override public Token getToken(ParseContext context,TokenKind kind,boolean invert){
                    state.value=99;context.capture("word",new CapturedValue("changed",CapturedValue.Provenance.GENERATED,context.position(),context.position()));
                    context.choose(choice,this);context.order(ordered,new Parsers(this));
                    metadata.putExtra(old,"label","during");Token token=super.getToken(context,kind,invert);fresh.set(metadata.register(token));metadata.putRelated(old,"new",fresh.get());return token;
                }
            };
            Parser root=new Chain(effect,wrapped(e[0],new Limit()));
            var error=assertThrows(label,ParseLimitException.class,()->root.parse(c));
            assertEquals(label,Integer.parseInt(e[2]),c.getConsumedPosition().value());assertEquals(label,Integer.parseInt(e[3]),c.getMatchedPosition().value());
            assertEquals(label,Integer.parseInt(e[4]),c.getTokenStack().size());assertEquals(label,Integer.parseInt(e[5]),c.getTokenStack().stream().mapToInt(frame->frame.getTokens().size()).sum());
            assertEquals(label,Integer.parseInt(e[6]),state.value);assertEquals(label,e[7],c.captured("word").orElseThrow().text());assertFalse(label,c.captured("inside").isPresent());
            assertEquals(label,e[8],metadata.info(old).orElseThrow().extra().get("label"));assertFalse(label,metadata.info(old).orElseThrow().related().containsKey("new"));assertFalse(label,metadata.info(fresh.get()).isPresent());
            assertFalse(label,c.getCurrent().isResetMatchedWithConsumed());
            assertSame(label,previous,c.getChosen(choice).orElseThrow());assertSame(label,previousOrder,c.getOrdered(ordered));
            assertTrue(label,c.parseFrames.isEmpty());assertEquals(label,0,frameCount(c,"terminalFrames"));assertEquals(label,0,frameCount(c,"deferredFrameDepth"));
            int before=events.get();assertSame(label,error,assertThrows(ParseLimitException.class,()->root.parse(c)));assertSame(label,error,assertThrows(ParseLimitException.class,()->c.commit(root,TokenKind.consumed)));assertEquals(label,before,events.get());
            c.close();assertEquals(label,1,closed.get());assertEquals(label,1,c.getTokenStack().size());assertEquals(label,7,state.value);assertEquals(label,"p",c.captured("word").orElseThrow().text());assertEquals(label,"before",metadata.info(old).orElseThrow().extra().get("label"));
        }
    }
    @Test public void swallowedResourceFailureCannotRepublishCallbackSuccess(){
        for(var mode:ParseOptions.Diagnostics.values())try(var c=ParseContext.withOptions(StringSource.createRootSource("a"),ParseOptions.DEFAULT.withDiagnostics(mode))){
            State state=new State();c.registerTransactionalState(state);AtomicReference<ParseLimitException> original=new AtomicReference<>();
            Parser child=new Limit(){
                @Override public Parsed parse(ParseContext context,TokenKind kind,boolean invert){
                    try{super.parse(context,kind,invert);}catch(ParseLimitException error){original.set(error);}
                    state.value=99;context.consume(new CodePointLength(1));return Parsed.SUCCEEDED;
                }
            };
            var error=assertThrows(ParseLimitException.class,()->new Chain(child).parse(c));assertSame(original.get(),error);
            assertEquals(7,state.value);assertEquals(0,c.position());assertEquals(1,c.getTokenStack().size());assertTrue(c.getCurrent().getTokens().isEmpty());
        }
    }
    @Test public void swallowedLimitInsideObserverPreservesTheFirstObserverCause(){
        for(var mode:ParseOptions.Diagnostics.values())try(var c=ParseContext.withOptions(StringSource.createRootSource("a"),ParseOptions.DEFAULT.withDiagnostics(mode))){
            State state=new State();c.registerTransactionalState(state);AtomicReference<ParseLimitException> original=new AtomicReference<>();
            Parser target=new WordParser("a");
            c.addParserListener(Name.of("swallow-resource"),new ParserListener(){
                public void setLevel(OutputLevel level){}
                public void onStart(Parser p,ParseContext context,TokenKind kind,boolean invert){
                    if(p!=target)return;
                    try{new Limit().parse(context,kind,invert);}catch(ParseLimitException error){original.set(error);}
                    state.value=99;
                }
                public void onEnd(Parser p,Parsed parsed,ParseContext context,TokenKind kind,boolean invert){}
            });
            var error=assertThrows(ParseLimitException.class,()->target.parse(c));assertSame(original.get(),error);
            assertEquals(7,state.value);assertEquals(0,c.position());assertEquals(1,c.getTokenStack().size());assertTrue(c.getCurrent().getTokens().isEmpty());
            assertSame(error,assertThrows(IllegalStateException.class,()->target.parse(c)).getCause());
        }
    }
}
