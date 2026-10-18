package org.unlaxer.context;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.WordParser;

/** Ordinary callbacks unwind publication once and permit a later, independent parse. */
public class OrdinaryParserExceptionTest {
    private static final List<String> FORMS = List.of("chain", "optional", "repeat", "item", "separator", "tail",
        "boundary", "choice", "longest", "unique", "not", "match", "peg", "owned", "scope", "swallow");
    private static Parser word(String value) { return new WordParser(value); }
    private static Parser form(String name, Parser callback) {
        return switch (name) {
            case "chain", "item" -> new Chain(callback);
            case "optional" -> new org.unlaxer.parser.combinator.Optional(callback);
            case "repeat" -> new ZeroOrMore(callback);
            case "separator" -> new Chain(word("😀"), new ZeroOrMore(new Chain(callback,word("x"))));
            case "tail" -> new Chain(word("😀"), new ZeroOrMore(new Chain(word(";"),callback)));
            case "boundary" -> CaptureBoundaryParser.wrap(CaptureBoundaryParser.Kind.LIST,
                new Chain(CaptureBoundaryParser.value(callback)));
            case "choice" -> new Choice(callback,word("😀"));
            case "longest" -> new LongestChoice(callback,word("😀"));
            case "unique" -> new UniqueLongestChoice(callback,word("😀"));
            case "not" -> new Not(callback);
            case "match" -> new MatchOnly(callback);
            case "peg" -> new PegLookahead(callback);
            case "owned" -> new NamedCapture("outer",callback);
            case "scope" -> new CaptureScope(callback);
            case "swallow" -> new Chain(new NoneChildParser() {
                @Override public Parser createParser() { return this; }
                @Override public Parsed parse(ParseContext context,TokenKind kind,boolean invert) {
                    try { callback.parse(context,kind,invert); } catch (RuntimeException ignored) {}
                    // Try to hide an exception detected by a guarded inner parser.
                    return Parsed.SUCCEEDED;
                }
            });
            default -> throw new AssertionError(name);
        };
    }
    @Test public void ordinaryErrorsRestorePublicationAndRegisteredStateThenAllowRetry() {
        for (Memoization memo:Memoization.values()) for (ParseOptions.Diagnostics mode:ParseOptions.Diagnostics.values())
        for (String name:FORMS) for (boolean manual:List.of(false,true)) for (boolean abandoned:List.of(false,true)) {
            String label=name+" "+memo+" "+mode+" manual="+manual+" abandoned="+abandoned;
            try (var context=ParseContext.withOptions(StringSource.createRootSource("p😀;x"),
                    ParseOptions.withMemoization(memo).withDiagnostics(mode))) {
                int[] state={7};
                context.registerTransactionalState(()->{int saved=state[0];return ()->state[0]=saved;});
                var metadata=new PortableTokenMetadata(context);
                var old=new NamedCapture("word",word("p")).parse(context).getRootToken();
                var id=metadata.register(old);metadata.putExtra(id,"label","before");
                RuntimeException original=new IllegalStateException("ordinary callback");
                Parser mutation=new NoneChildParser() {
                    @Override public Parser createParser() { return this; }
                    @Override public Parsed parse(ParseContext c,TokenKind kind,boolean invert) {
                        if(abandoned){c.startParse(this,c,kind,invert);c.begin(this);}
                        state[0]=99;metadata.putExtra(id,"label","during");
                        c.capture("word",new CapturedValue("",CapturedValue.Provenance.INPUT,c.position(),c.position()));
                        if(c.position()<4)c.consume(new CodePointLength(1));
                        throw original;
                    }
                };
                // The inner Chain detects the exception even if the custom parent swallows it.
                Parser callback=name.equals("swallow")?new Chain(mutation):mutation;
                Parser parser=form(name,callback);
                if(manual)context.begin(parser);
                int tokens=context.getCurrent().getTokens().size();
                assertSame(label,original,assertThrows(RuntimeException.class,()->parser.parse(context)));
                assertEquals(label,1,context.getConsumedPosition().value());
                assertEquals(label,1,context.getMatchedPosition().value());
                assertEquals(label,manual?2:1,context.getTokenStack().size());
                assertEquals(label,tokens,context.getCurrent().getTokens().size());
                assertEquals(label,7,state[0]);
                assertEquals(label,"p",context.captured("word").orElseThrow().text());
                assertEquals(label,"before",metadata.info(id).orElseThrow().extra().get("label"));
                assertTrue(label,word("😀").parse(context).isSucceeded());
                assertEquals(label,2,context.position());
                if(manual){context.rollback(parser);assertEquals(label,1,context.position());}
            }
        }
    }
    @Test public void terminalErrorIdentityAndCloseArePreserved() {
        for (ParseOptions.Diagnostics mode:ParseOptions.Diagnostics.values()) {
            var original=new AssertionError("terminal callback");
            try (var context=ParseContext.withOptions(StringSource.createRootSource("a"),ParseOptions.DEFAULT.withDiagnostics(mode))) {
                Parser parser=new WordParser("a") {
                    @Override public Token getToken(ParseContext c,TokenKind k,boolean inverse) {throw original;}
                };
                assertSame(original,assertThrows(AssertionError.class,()->parser.parse(context)));
                assertEquals(1,context.getTokenStack().size());
                assertEquals(0,context.position());
                assertTrue(word("a").parse(context).isSucceeded());
            }
        }
    }
    @Test public void invalidRootBeforeParsingPreservesExplicitTransaction() {
        try (var context=ParseContext.withOptions(StringSource.createRootSource("ab"),ParseOptions.DEFAULT)) {
            Parser invalid=new org.unlaxer.parser.PseudoRootParser(org.unlaxer.Name.of("invalid"));
            context.begin(invalid);
            assertTrue(word("a").parse(context).isSucceeded());
            assertThrows(RuntimeException.class,()->invalid.parse(context));
            assertEquals(1,context.position());
            assertEquals(2,context.getTokenStack().size());
            assertTrue(word("b").parse(context).isSucceeded());
            context.rollback(invalid);
            assertEquals(0,context.position());
        }
    }

    @Test public void entryPolicyAndTransparentCallbacksRestoreBeforeRetry() {
        int count=0;
        for(var memo:Memoization.values()) for(var diagnostic:ParseOptions.Diagnostics.values())
        for(boolean manual:List.of(false,true)) for(boolean reset:List.of(false,true))
        for(boolean abandoned:List.of(false,true)) for(String form:List.of("chain","holder","mapped-holder","finally")) {
            try(var c=ParseContext.withOptions(StringSource.createRootSource("p😀"),
                    ParseOptions.withMemoization(memo).withDiagnostics(diagnostic))) {
                int[] state={7}; c.registerTransactionalState(()->{int saved=state[0];return ()->state[0]=saved;});
                new NamedCapture("word",word("p")).parse(c);
                c.getCurrent().setResetMatchedWithConsumed(reset);
                RuntimeException original=new IllegalStateException("first callback");
                RuntimeException secondary=new IllegalArgumentException("finally callback");
                Parser child=new NoneChildParser(){
                    @Override public Parser createParser(){return this;}
                    @Override public Parsed parse(ParseContext x,TokenKind k,boolean inverse){
                        if(abandoned){x.startParse(this,x,k,inverse);x.begin(this);}
                        state[0]=99;x.capture("word",new CapturedValue("",CapturedValue.Provenance.INPUT,x.position(),x.position()));
                        x.consume(new CodePointLength(1));throw original;
                    }
                };
                Parser parser=switch(form){
                    case "chain" -> new Chain(child);
                    case "holder" -> new ParserHolder(child);
                    case "mapped-holder" -> new MappedSingleCharacterParserHolder(
                        new org.unlaxer.parser.elementary.MappedSingleCharacterParser("a"){
                            @Override public Parsed parse(ParseContext x,TokenKind k,boolean inverse){return child.parse(x,k,inverse);}
                        });
                    default -> new Chain(new NoneChildParser(){
                        @Override public Parser createParser(){return this;}
                        @Override public Parsed parse(ParseContext x,TokenKind k,boolean inverse){
                            var inner=new Chain(child);
                            try{return inner.parse(x,k,inverse);}
                            finally {try{x.rollback(inner);}catch(RuntimeException ignored){}throw secondary;}
                        }
                    });
                };
                if(manual)c.begin(parser);
                var before=List.copyOf(c.getCurrent().getTokens());
                assertSame(form,original,assertThrows(RuntimeException.class,()->parser.parse(c)));
                assertEquals(form,1,c.position());assertEquals(form,1,c.getMatchedPosition().value());
                assertEquals(form,reset,c.getCurrent().isResetMatchedWithConsumed());
                assertEquals(form,manual?2:1,c.getTokenStack().size());
                assertEquals(form,before,c.getCurrent().getTokens());assertEquals(form,7,state[0]);
                assertEquals(form,"p",c.captured("word").orElseThrow().text());
                assertTrue(form,word("😀").parse(c).isSucceeded());
                if(manual){c.rollback(parser);assertEquals(1,c.position());}
                count++;
            }
        }
        assertEquals(192,count);
    }

    @Test public void suggestionCallbackFailureDoesNotPublishEmptyToken() {
        int count=0;
        for(var memo:Memoization.values()) for(var diagnostic:ParseOptions.Diagnostics.values())
        for(boolean manual:List.of(false,true)) {
            RuntimeException original=new IllegalStateException("suggest callback");
            boolean[] fail={true};
            var collector=new org.unlaxer.parser.SuggestsCollectorParser();
            var sibling=new org.unlaxer.parser.SuggestableParser(false,"x"){
                @Override public String getSuggestString(String value){return value;}
                @Override public java.util.Optional<org.unlaxer.parser.Suggest> getSuggests(String value){
                    if(fail[0])throw original;return super.getSuggests(value);
                }
            };
            var parent=new Choice(collector,sibling);parent.getChildren();collector.setParent(parent);
            try(var c=ParseContext.withOptions(StringSource.createRootSource("x"),
                    ParseOptions.withMemoization(memo).withDiagnostics(diagnostic))) {
                if(manual)c.begin(collector);
                var before=List.copyOf(c.getCurrent().getTokens());
                assertSame(original,assertThrows(RuntimeException.class,()->collector.parse(c)));
                assertEquals(before,c.getCurrent().getTokens());assertEquals(0,c.position());
                assertEquals(manual?2:1,c.getTokenStack().size());
                fail[0]=false;assertTrue(collector.parse(c).isStopped());
                assertEquals(before.size()+1,c.getCurrent().getTokens().size());
                if(manual)c.rollback(collector);count++;
            }
        }
        assertEquals(12,count);
    }

}
