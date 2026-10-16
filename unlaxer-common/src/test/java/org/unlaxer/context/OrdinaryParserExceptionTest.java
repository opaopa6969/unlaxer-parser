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

}
