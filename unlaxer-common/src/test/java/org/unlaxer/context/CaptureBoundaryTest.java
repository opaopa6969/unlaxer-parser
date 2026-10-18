package org.unlaxer.context;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.stream.Collectors;
import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.capture.CaptureSnapshot;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.*;
import static org.unlaxer.parser.combinator.CaptureBoundaryParser.*;

public class CaptureBoundaryTest {
    private static String decode(String text) { return text.equals("~") ? "" : new String(HexFormat.of().parseHex(text),StandardCharsets.UTF_8); }
    private static String hex(String text) { return text.isEmpty() ? "~" : HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8)); }
    private static String canonical(CaptureSnapshot.Element e) {
        String kind=switch(e.kind()) {case VALUE->"V";case OPTIONAL->"O";case LIST->"L";case SEPARATOR->throw new AssertionError("separator is not a snapshot value");};
        return kind+"@"+e.startCP()+":"+e.endCP()+":"+hex(e.text())+"["+e.children().stream().map(CaptureBoundaryTest::canonical).collect(Collectors.joining(","))+"]";
    }
    private static Parser word(String text) { return new WordParser(text); }
    private static Parser group() { return wrap(Kind.LIST,new Chain(word("["),new ZeroOrMore(value(word("a"))),word("]"))); }
    private static Parser generated() {
        return new NoneChildParser() {
            @Override public Parser createParser() { return this; }
            @Override public org.unlaxer.Parsed parse(ParseContext context,TokenKind kind,boolean invert) {
                var token=new org.unlaxer.Token(TokenKind.virtualTokenConsumed,StringSource.createDetachedSource("x"),this);
                context.getCurrent().addToken(token,TokenKind.virtualTokenConsumed);
                return new org.unlaxer.Parsed(token);
            }
        };
    }
    private static Parser parser(String name) {
        Parser a=word("a");
        return switch(name) {
            case "inverse"->value(a);
            case "inverse-missing"->optional(a);
            case "stop-consume"->value(new DoConsumePropagationStopper(a));
            case "projected"->value(new Chain(new MatchOnly(a),a));
            case "invalid-bounds"->list(a,2,1);
            case "trivia"->wrap(Kind.LIST,new Chain(new ZeroOrMore(new org.unlaxer.parser.posix.SpaceParser()),value(a),new ZeroOrMore(new org.unlaxer.parser.posix.SpaceParser()),value(word("b")),new ZeroOrMore(new org.unlaxer.parser.posix.SpaceParser())));
            case "generated"->value(generated());
            case "optional"->optional(a);
            case "empty-value"->value(new EndOfSourceParser());
            case "optional-list"->optional(list(a));
            case "list"->list(a);
            case "list-optional"->list(optional(a));
            case "list-list"->list(list(a));
            case "groups"->list(group());
            case "required-group"->group();
            case "unicode"->list(new Choice(word("😀"),word("\r\n")));
            case "zero"->list(a,0,0);
            case "rollback"->wrap(Kind.LIST,new Choice(new Chain(list(a),word("X")),value(word("ab"))));
            default->throw new AssertionError(name);
        };
    }
    @Test public void independentNestedBoundaryOracle() throws Exception {
        var rows=Files.readAllLines(Path.of("../conformance/capture-boundaries/runtime.tsv")).stream().filter(s->!s.startsWith("#")).toList();
        assertEquals(104,rows.size());
        for(Memoization memo:Memoization.values()) for(ParseOptions.Diagnostics diagnostics:ParseOptions.Diagnostics.values()) for(String row:rows) {
            String[] c=row.split("\t");CaptureSnapshot.Element snapshot=null;
            try(var context=ParseContext.withOptions(StringSource.createRootSource(decode(c[2])),ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                if(!c[5].equals("~"))word(decode(c[5])).parse(context);
                var result=parser(c[1]).parse(context,TokenKind.valueOf(c[3]),Boolean.parseBoolean(c[4]));
                assertEquals(c[0],Boolean.parseBoolean(c[6]),result.isSucceeded());
                assertEquals(c[0],Integer.parseInt(c[7]),context.getConsumedPosition().value());
                assertEquals(c[0],Integer.parseInt(c[8]),context.getMatchedPosition().value());
                if(result.isSucceeded()) {
                    snapshot=CaptureSnapshot.from(result.getRootToken());
                    assertEquals(TokenKind.valueOf(c[3]),snapshot.tokenKind());
                    assertEquals(c[1].equals("generated") && c[3].equals("virtualTokenConsumed"),snapshot.generated());
                }
                assertEquals(c[0],1,context.getTokenStack().size());
            }
            if(snapshot!=null)assertEquals(c[0]+" "+memo+" "+diagnostics,c[9],canonical(snapshot));
        }
    }

    @Test public void ownedCaptureLookaheadFailureAndSnapshotLifetime() {
        CaptureSnapshot.Element before, after;
        try(var context=new ParseContext(StringSource.createRootSource("ab"))) {
            var first=value(new NamedCapture("old",word("a"))).parse(context);
            before=CaptureSnapshot.from(first.getRootToken());
            Parser next=value(new NamedCapture("new",word("b")));
            assertTrue(new Chain(next,word("missing")).parse(context).isFailed());
            assertEquals(1,context.getConsumedPosition().value());
            assertEquals(1,context.getMatchedPosition().value());
            assertEquals("a",context.captured("old").orElseThrow().text());
            assertTrue(context.captured("new").isEmpty());
            assertTrue(new PegLookahead(next).parse(context).isSucceeded());
            assertEquals(1,context.getConsumedPosition().value());
            assertEquals(1,context.getMatchedPosition().value());
            assertTrue(context.captured("new").isEmpty());
            var last=next.parse(context);
            assertTrue(last.isSucceeded());
            after=CaptureSnapshot.from(last.getRootToken());
            assertEquals("b",context.captured("new").orElseThrow().text());
            assertTrue(list(new NamedCapture("never",word("ignored")),0,0).parse(context).isSucceeded());
            assertTrue(context.captured("never").isEmpty());
            assertEquals(1,context.getTokenStack().size());
        }
        assertEquals("V@0:1:61[]",canonical(before));
        assertEquals("V@1:2:62[]",canonical(after));
        assertThrows(UnsupportedOperationException.class,()->before.children().clear());
    }

    @Test public void malformedAndOversizedSnapshotsAreBounded() {
        try(var context=new ParseContext(StringSource.createRootSource("ab"))) {
            var bad=wrap(Kind.OPTIONAL,new Chain(value(word("a")),value(word("b")))).parse(context);
            assertEquals(CaptureSnapshot.Error.INVALID_TREE,assertThrows(CaptureSnapshot.SnapshotException.class,()->CaptureSnapshot.from(bad.getRootToken())).error());
        }
        var parser=wrap(Kind.LIST,new Chain());
        org.unlaxer.Token root=new org.unlaxer.Token(TokenKind.consumed,StringSource.createRootSource(""),parser);
        for(int i=0;i<300;i++)root=new org.unlaxer.Token(TokenKind.consumed,StringSource.createRootSource(""),parser,
                org.unlaxer.TokenList.of(java.util.List.of(root)));
        final var deep=root;
        assertEquals(CaptureSnapshot.Error.LIMIT,assertThrows(CaptureSnapshot.SnapshotException.class,()->CaptureSnapshot.from(deep)).error());
        assertEquals(CaptureSnapshot.Error.INVALID_TREE,assertThrows(CaptureSnapshot.SnapshotException.class,()->CaptureSnapshot.from(null)).error());
        var text="a".repeat(2*1024*1024+1);
        try (var context = new ParseContext(StringSource.createRootSource(text))) {
            var tiny = value(word("a")).parse(context);
            assertEquals("V@0:1:61[]", canonical(CaptureSnapshot.from(tiny.getRootToken())));
        }
        var large=new org.unlaxer.Token(TokenKind.consumed,StringSource.createRootSource(text),value(word("a")));
        assertEquals(CaptureSnapshot.Error.LIMIT,assertThrows(CaptureSnapshot.SnapshotException.class,()->CaptureSnapshot.from(large)).error());
    }

    @Test public void nestedBoundariesUseExistingParseLimit() {
        Parser parser=word("a");
        for(int i=0;i<2000;i++)parser=optional(parser);
        final Parser deep=parser;
        try(var context=new ParseContext(StringSource.createRootSource("a"))) {
            assertThrows(org.unlaxer.context.ParseLimitException.class,()->deep.parse(context));
        }
    }

    private static final class FailingBoundary extends Chain implements SafeFailureMemoizable {
        FailingBoundary() { super(value(new WordParser("a")), new WordParser("!")); }
    }
    @Test public void memoizedFailuresLeaveNoBoundaryAndPreserveDiagnostics() {
        for (Memoization memo : Memoization.values()) for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
            Parser atom = new FailingBoundary();
            Parser parser = new Choice(atom, atom);
            try (var context = ParseContext.withOptions(StringSource.createRootSource("a?"), ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                assertTrue(parser.parse(context).isFailed());
                assertEquals(0, context.getConsumedPosition().value());
                assertEquals(0, context.getMatchedPosition().value());
                assertEquals(memo == Memoization.SAFE_FAILURES, context.getPackratMemoTable() != null && context.getPackratMemoTable().failureHits() > 0);
                if (diagnostics != ParseOptions.Diagnostics.DETAILED_ON_FAILURE) {
                    assertEquals(1, context.getParseFailureDiagnostics().getFarthestOffset());
                    assertTrue(context.getParseFailureDiagnostics().getExpectedTokens().contains("'!'"));
                }
                var parsed = value(word("a")).parse(context);
                assertEquals("V@0:1:61[]", canonical(CaptureSnapshot.from(parsed.getRootToken())));
                assertEquals(1, context.getTokenStack().size());
            }
        }
    }

    @Test public void inputBoundaryFailureResetAndOuterRollbackMatchSharedOracle() throws Exception {
        var rows=Files.readAllLines(Path.of("../conformance/input-capture-boundary-reset/cases.tsv"))
                .stream().filter(row->!row.startsWith("#")&&!row.isBlank()).toList();
        assertEquals(8,rows.size());
        var actual=new java.util.ArrayList<String>();
        var expected=new java.util.ArrayList<String>();
        for(var memo:Memoization.values()) for(var diagnostics:ParseOptions.Diagnostics.values()) for(String row:rows) {
            var fields=row.split("\t");
            String label=fields[1]+"\t"+fields[2]+"\t"+memo+"\t"+diagnostics;
            Parser parser=input(Kind.VALUE,word("a"));
            try(var context=ParseContext.withOptions(StringSource.createRootSource(decode(fields[3])),
                    ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                context.getCurrent().setResetMatchedWithConsumed(Boolean.parseBoolean(fields[2]));
                context.beginPreservingMatched(parser);
                var parsed=parser.parse(context,TokenKind.valueOf(fields[1]),false);
                String inner=parsed.isSucceeded()+"\t"+context.position()+"\t"+context.matchedPosition()
                        +"\t"+context.getCurrent().isResetMatchedWithConsumed()+"\t"+context.getCurrent().getTokens().isEmpty();
                context.rollback(parser);
                actual.add(label+"\t"+inner+"\t"+context.position()+"\t"+context.matchedPosition()
                        +"\t"+context.getCurrent().isResetMatchedWithConsumed()+"\t"+context.getCurrent().getTokens().isEmpty());
                expected.add(label+"\t"+fields[4]+"\t"+fields[5]+"\t"+fields[6]+"\t"+fields[7]+"\ttrue\t0\t0\t"+fields[8]+"\ttrue");
                assertEquals("outer frame restored",1,context.getTokenStack().size());
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/input-capture-boundary-reset-java.tsv"),actual);
        assertEquals(48,actual.size());
        assertEquals("all observations recorded before checking reset contract",expected,actual);
    }
}
