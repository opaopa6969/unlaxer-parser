package org.unlaxer.context;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.capture.CaptureSnapshot;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.combinator.CaptureBoundaryParser.Kind;
import org.unlaxer.parser.elementary.WordParser;
import static org.unlaxer.parser.combinator.CaptureBoundaryParser.*;

/** Separator subtrees retain their own CST/state but cannot supply outer list values. */
public class NestedSeparatedBoundaryTest {
    private static final class State implements TransactionalState {
        int value=7;
        public Runnable checkpoint(){int before=value;return ()->value=before;}
    }
    private static String decode(String value){return value.equals("~")?"":new String(HexFormat.of().parseHex(value),StandardCharsets.UTF_8);}
    private static String hex(String value){return value.isEmpty()?"~":HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));}
    private static String canonical(CaptureSnapshot.Element value){
        String kind=switch(value.kind()){case VALUE->"V";case OPTIONAL->"O";case LIST->"L";case SEPARATOR->throw new AssertionError();};
        return kind+"@"+value.startCP()+":"+value.endCP()+":"+hex(value.text())+"["+value.children().stream().map(NestedSeparatedBoundaryTest::canonical).collect(java.util.stream.Collectors.joining(","))+"]";
    }
    private static void separators(Token root,List<Token> result){
        if(root.parser instanceof CaptureBoundaryParser b&&b.boundaryKind()==Kind.SEPARATOR)result.add(root);
        for(Token child:root.getOriginalChildren())separators(child,result);
    }
    @Test public void sharedSeparatorValuesStateAndRollbackOracle()throws Exception {
        for(var memo:Memoization.values())for(var diagnostics:ParseOptions.Diagnostics.values())
        for(String row:Files.readAllLines(Path.of("../conformance/nested-separated/runtime.tsv"))){
            if(row.startsWith("#"))continue;String[] e=row.split("\t");String input=decode(e[2]);
            State state=new State();
            Parser item=value(new NamedCapture("item",e[1].equals("empty")?new org.unlaxer.parser.elementary.EndOfSourceParser():new WordParser("a")));
            Parser punctuation=e[1].equals("empty")?new org.unlaxer.parser.elementary.EndOfSourceParser(){
                @Override public Parsed parse(ParseContext context,TokenKind kind,boolean invert){state.value++;return super.parse(context,kind,invert);}
            }:new WordParser(","){
                @Override public Token getToken(ParseContext context,TokenKind kind,boolean invert){state.value++;return super.getToken(context,kind,invert);}
            };
            Parser separator=wrap(Kind.SEPARATOR,optional(list(new NamedCapture("sep",punctuation))));
            if(e[1].equals("nested-mask"))separator=wrap(Kind.SEPARATOR,separator);
            Parser list=wrap(Kind.LIST,new Chain(item,new ZeroOrMore(new Chain(separator,item))));
            if(e[1].equals("scope"))list=new CaptureScope(list);
            if(e[1].equals("choice"))list=wrap(Kind.LIST,new Choice(new Chain(list,new WordParser("!")),value(new WordParser(input))));
            final Parser parser=list;
            try(var context=ParseContext.withOptions(StringSource.createRootSource(input),ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))){
                context.registerTransactionalState(state);
                assertTrue(new Repeat(parser,2,2).parse(context).isFailed());assertEquals(7,state.value);assertEquals(0,context.position());assertEquals(0,context.matchedPosition());assertTrue(context.captured("item").isEmpty());assertTrue(context.captured("sep").isEmpty());assertTrue(context.getCurrent().getTokens().isEmpty());
                context.beginPreservingMatched(parser);
                var result=parser.parse(context);
                assertEquals(e[0],Boolean.parseBoolean(e[3]),result.isSucceeded());assertEquals(e[0],Integer.parseInt(e[4]),context.position());assertEquals(e[0],Integer.parseInt(e[5]),context.matchedPosition());assertEquals(e[0],Integer.parseInt(e[6]),state.value);
                for(int i=7;i<=8;i++){var captured=context.captured(i==7?"item":"sep");assertEquals(e[0],!e[i].equals("-"),captured.isPresent());if(captured.isPresent())assertEquals(e[0],decode(e[i]),captured.orElseThrow().text());}
                CaptureSnapshot.Element retained=null;
                if(result.isSucceeded()){
                    retained=CaptureSnapshot.from(result.getRootToken());assertEquals(e[0],e[9],canonical(retained));
                    var masks=new ArrayList<Token>();separators(result.getRootToken(),masks);
                    for(Token mask:masks){assertFalse(mask.getOriginalChildren().isEmpty());assertEquals(CaptureSnapshot.Error.NOT_BOUNDARY,assertThrows(CaptureSnapshot.SnapshotException.class,()->CaptureSnapshot.from(mask)).error());}
                    if(e[1].equals("plain"))assertEquals(e[0],1,masks.size());
                    if(e[1].equals("nested-mask"))assertEquals(e[0],2,masks.size());
                }
                context.rollback(parser);assertEquals(7,state.value);assertEquals(0,context.position());assertEquals(0,context.matchedPosition());assertTrue(context.captured("item").isEmpty());assertTrue(context.captured("sep").isEmpty());assertTrue(context.getCurrent().getTokens().isEmpty());assertEquals(1,context.getTokenStack().size());
                if(retained!=null)assertEquals(e[0],e[9],canonical(retained));
            }
        }
    }

    @Test public void resourceAbortAcrossEverySeparatedPositionRemainsStickyAndRestoresPublication()throws Exception {
        for(var memo:Memoization.values())for(var diagnostics:ParseOptions.Diagnostics.values())
        for(String row:Files.readAllLines(Path.of("../conformance/nested-separated/resource.tsv"))){
            if(row.startsWith("#"))continue;String[] e=row.split("\t");State state=new State();
            try(var c=ParseContext.withOptions(StringSource.createRootSource(decode(e[1])),ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))){
                c.registerTransactionalState(state);assertTrue(new NamedCapture("old",new WordParser("p")).parse(c).isSucceeded());
                assertTrue(new WordParser("😀").parse(c,TokenKind.matchOnly,false).isSucceeded());
                var calls=new java.util.concurrent.atomic.AtomicInteger();
                Parser zero=new Repeat(new WordParser("never"),0,0);
                Parser resource=new Chain(){
                    @Override public Parsed parse(ParseContext context,TokenKind kind,boolean invert){
                        state.value=99;context.capture("effect",new CapturedValue("",CapturedValue.Provenance.INPUT,1,1));
                        while(true)context.startParse(this,context,kind,invert);
                    }
                };
                Parser item=new Chain(){
                    @Override public Parsed parse(ParseContext context,TokenKind kind,boolean invert){
                        return (e[0].equals("first")||e[0].equals("tail")&&calls.incrementAndGet()>1?resource:zero).parse(context,kind,invert);
                    }
                };
                Parser mask=wrap(Kind.SEPARATOR,e[0].equals("separator")?resource:zero);
                Parser parser=wrap(Kind.LIST,new Chain(value(item),new ZeroOrMore(new Chain(mask,value(item)))));
                assertTrue(new Repeat(parser,0,0).parse(c).isSucceeded());assertEquals(0,calls.get());
                var before=List.copyOf(c.getCurrent().getTokens());assertEquals(3,before.size());
                var error=assertThrows(ParseLimitException.class,()->parser.parse(c));
                assertSame(error,assertThrows(ParseLimitException.class,()->zero.parse(c)));
                assertEquals(Integer.parseInt(e[2]),c.position());assertEquals(Integer.parseInt(e[3]),c.matchedPosition());assertEquals(Integer.parseInt(e[4]),state.value);assertEquals(Integer.parseInt(e[5]),c.getTokenStack().size());assertEquals("p",c.captured("old").orElseThrow().text());assertTrue(c.captured("effect").isEmpty());assertEquals(before,c.getCurrent().getTokens());
            }
        }
    }

    private static void valueBoundaries(Token token,List<Token> result){
        if(token.parser instanceof CaptureBoundaryParser b&&b.boundaryKind()==Kind.VALUE)result.add(token);
        else for(Token child:token.getOriginalChildren())valueBoundaries(child,result);
    }
    @Test public void opaqueSeparatorDoesNotDestroyGeneralProjectedOrGeneratedSnapshots()throws Exception {
        for(var memo:Memoization.values())for(var diagnostics:ParseOptions.Diagnostics.values())
        for(String row:Files.readAllLines(Path.of("../conformance/nested-separated/provenance.tsv"))){
            if(row.startsWith("#"))continue;String[] e=row.split("\t");
            Parser child=new NoneChildParser(){
                @Override public Parser createParser(){return this;}
                @Override public Parsed parse(ParseContext c,TokenKind kind,boolean invert){
                    Token token;
                    if(e[0].equals("generated"))token=new Token(TokenKind.virtualTokenConsumed,StringSource.createDetachedSource("bb"),this);
                    else{var source=c.peek(new CodePointIndex(1),new CodePointLength(1));Token leaf=new Token(TokenKind.virtualTokenConsumed,source,this);token=new Token(TokenKind.virtualTokenConsumed,TokenList.of(leaf,leaf),this);c.consume(new CodePointLength(1));}
                    c.getCurrent().addToken(token,TokenKind.virtualTokenConsumed);return new Parsed(token);
                }
            };
            Parser parser=wrap(Kind.SEPARATOR,value(child));
            try(var c=ParseContext.withOptions(StringSource.createRootSource(decode(e[1])),ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))){
                var parsed=parser.parse(c,TokenKind.virtualTokenConsumed,false);assertTrue(parsed.isSucceeded());
                assertEquals(Integer.parseInt(e[2]),c.position());assertEquals(Integer.parseInt(e[3]),c.matchedPosition());
                var values=new ArrayList<Token>();valueBoundaries(parsed.getRootToken(),values);assertEquals(1,values.size());
                var snapshot=CaptureSnapshot.from(values.get(0));assertEquals(e[5],canonical(snapshot));assertEquals(Boolean.parseBoolean(e[4]),snapshot.generated());
                assertEquals(CaptureSnapshot.Error.NOT_BOUNDARY,assertThrows(CaptureSnapshot.SnapshotException.class,()->CaptureSnapshot.from(parsed.getRootToken())).error());
            }
        }
    }

    private static final class MemoList extends Chain implements SafeFailureMemoizable,SafeSuccessMemoizable {
        private static final long serialVersionUID=1L;
        MemoList(){super(wrap(Kind.LIST,new Chain(value(new WordParser("a")),new ZeroOrMore(new Chain(wrap(Kind.SEPARATOR,value(new WordParser(","))),value(new WordParser("a")))))));}
    }
    @Test public void memoSuccessReplaysOpaqueSeparatorAndCopiesOriginalBoundaries()throws Exception {
        String expected=Files.readAllLines(Path.of("../conformance/nested-separated/runtime.tsv")).stream().filter(r->r.startsWith("normal\t")).findFirst().orElseThrow().split("\t")[9];
        for(var memo:Memoization.values())for(var diagnostics:ParseOptions.Diagnostics.values()){
            var parser=new MemoList();
            try(var c=ParseContext.withOptions(StringSource.createRootSource("a,a"),ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))){
                c.beginPreservingMatched(parser);var first=parser.parse(c);Token original=first.getRootToken().getOriginalChildren().get(0);assertEquals(expected,canonical(CaptureSnapshot.from(original)));c.rollback(parser);
                var second=parser.parse(c);Token replay=second.getRootToken().getOriginalChildren().get(0);assertEquals(expected,canonical(CaptureSnapshot.from(replay)));assertNotSame(original,replay);
                assertEquals(memo==Memoization.SAFE_FAILURES,c.getPackratMemoTable()!=null&&c.getPackratMemoTable().successHits()>0);assertEquals(3,c.position());assertEquals(3,c.matchedPosition());assertEquals(1,c.getTokenStack().size());
            }
        }
    }
    @Test public void separatedFailureResetSameContextFallbackAndOuterRollbackMatchSharedOracle() throws Exception {
        var rows=Files.readAllLines(Path.of("../conformance/nested-separated/reset.tsv"))
                .stream().filter(row->!row.startsWith("#")&&!row.isBlank()).toList();
        assertEquals(8,rows.size());
        var actual=new java.util.ArrayList<String>();
        var expected=new java.util.ArrayList<String>();
        for(var memo:Memoization.values()) for(var diagnostics:ParseOptions.Diagnostics.values()) for(String row:rows) {
            var fields=row.split("\t");
            String label=fields[1]+"\t"+fields[2]+"\t"+memo+"\t"+diagnostics;
            Parser parser=input(Kind.LIST,new Chain(input(Kind.VALUE,new WordParser("a")),
                    new ZeroOrMore(new Chain(wrap(Kind.SEPARATOR,new WordParser(",")),input(Kind.VALUE,new WordParser("a"))))));
            try(var context=ParseContext.withOptions(StringSource.createRootSource(decode(fields[3])),
                    ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                context.getCurrent().setResetMatchedWithConsumed(Boolean.parseBoolean(fields[2]));
                context.beginPreservingMatched(parser);
                var parsed=parser.parse(context,TokenKind.valueOf(fields[1]),false);
                String inner=parsed.isSucceeded()+"\t"+context.position()+"\t"+context.matchedPosition()
                        +"\t"+context.getCurrent().isResetMatchedWithConsumed()+"\t"+context.getCurrent().getTokens().isEmpty();
                var fallback=new WordParser("?").parse(context);
                inner+="\t"+fallback.isSucceeded()+"\t"+context.position()+"\t"+context.matchedPosition();
                context.rollback(parser);
                actual.add(label+"\t"+inner+"\t"+context.position()+"\t"+context.matchedPosition()
                        +"\t"+context.getCurrent().isResetMatchedWithConsumed()+"\t"+context.getCurrent().getTokens().isEmpty());
                expected.add(label+"\t"+fields[4]+"\t"+fields[5]+"\t"+fields[6]+"\t"+fields[7]+"\ttrue\t"+fields[9]+"\t"+fields[10]+"\t"+fields[11]+"\t0\t0\t"+fields[8]+"\ttrue");
                assertEquals("outer frame restored",1,context.getTokenStack().size());
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/nested-separated-reset-java.tsv"),actual);
        assertEquals(48,actual.size());
        assertEquals("all observations recorded before checking reset contract",expected,actual);
    }
    private static Token snapshotNode(Parser parser,List<Token> children) {
        return new Token(TokenKind.consumed,StringSource.createRootSource(""),parser,TokenList.of(children));
    }
    @Test public void separatorSnapshotVisitDepthLimitsAndOpaqueMapperValidationMatchSharedOracle()throws Exception {
        var actual=new ArrayList<String>();var expected=new ArrayList<String>();
        for(String row:Files.readAllLines(Path.of("../conformance/nested-separated/snapshot-limits.tsv"))) {
            if(row.startsWith("#"))continue;var e=row.split("\t");int size=Integer.parseInt(e[2]);
            Parser marker=wrap(Kind.SEPARATOR,new Chain());Parser scalar=input(Kind.VALUE,new Chain());
            var children=new ArrayList<Token>();
            if(e[1].equals("visits")) {
                children.add(snapshotNode(scalar,List.of()));
                for(int i=0;i<size;i++)children.add(snapshotNode(marker,List.of()));
            } else if(e[1].equals("depth")) {
                Token child=snapshotNode(marker,List.of());
                for(int i=0;i<size;i++)child=snapshotNode(new Chain(),List.of(child));
                children.add(child);
            } else {
                Token value=snapshotNode(scalar,List.of());Token child=value;
                for(int i=0;i<size;i++)child=snapshotNode(new Chain(),List.of(child));
                Token separator=snapshotNode(marker,List.of(child));
                children.add(snapshotNode(scalar,List.of()));children.add(separator);
                assertSame(child,separator.getOriginalChildren().get(0));
                assertEquals("V@0:0:~[]",canonical(CaptureSnapshot.from(value)));
            }
            Token root=snapshotNode(wrap(Kind.LIST,new Chain()),children);String snapshot;
            try {var value=CaptureSnapshot.from(root);snapshot="OK:"+value.children().size();assertEquals("",value.text());assertEquals(0,value.startCP());assertEquals(0,value.endCP());}
            catch(CaptureSnapshot.SnapshotException failure){snapshot=failure.error().name();}
            String guard;
            try {org.unlaxer.mapper.MappingGuard.prepare(root);guard="OK";}
            catch(IllegalArgumentException failure){guard=failure.getMessage().split(":",2)[0];}
            actual.add(e[0]+"\t"+snapshot+"\t"+guard);expected.add(e[0]+"\t"+e[3]+"\t"+e[4]);
        }
        Files.write(Path.of("target/nested-separated-snapshot-limits-java.tsv"),actual);
        assertEquals(5,actual.size());assertEquals(expected,actual);
    }

}
