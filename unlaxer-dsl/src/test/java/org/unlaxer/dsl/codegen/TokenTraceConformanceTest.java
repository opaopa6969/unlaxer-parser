package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.clang.IdentifierParser;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.*;

/** Independent terminal oracle, using Java's real filtered Token leaves and Rust's real trace. */
public class TokenTraceConformanceTest {
    static String decode(String s) { return s.equals("~") ? "" : s.replace("\\\\","\0").replace("\\r","\r").replace("\\n","\n").replace("\0","\\"); }
    static String encode(String s) { return s.isEmpty()?"~":s.replace("\\","\\\\").replace("\r","\\r").replace("\n","\\n"); }
    static Parser word(String s) { return new WordParser(s); }
    static Parser sequence() { return new Chain(word("a"),word("b")); }
    static Parser parser(String op,String input) {
        return switch(op) {
            case "word" -> word(input);
            case "a" -> word("a");
            case "prefix-virtual" -> word("b");
            case "consume" -> new DoConsumePropagationStopper(word("a"));
            case "stop-invert" -> new InvertMatchPropagationStopper(word("a"));
            case "invert" -> new NotPropagatableSource(word("a"));
            case "sequence" -> sequence();
            case "choice" -> new Choice(new Chain(word("a"),word("x")),word("ab"));
            case "longest" -> new LongestChoice(word("a"),word("ab"),sequence());
            case "longest-tie" -> new LongestChoice(sequence(),word("ab"),word("a"));
            case "predictive" -> new PredictiveChoice(List.of(ChoicePredictor.literal("x"),ChoicePredictor.literal("ab")),word("x"),word("ab"));
            case "predictive-fallback" -> new PredictiveChoice(List.of(ChoicePredictor.literal("a"),ChoicePredictor.literal("x")),word("x"),word("ab"));
            case "lookahead" -> new Chain(new MatchOnly(word("a")),word("a"));
            case "lookahead-empty" -> new Chain(new MatchOnly(word("ab")),new org.unlaxer.parser.combinator.Optional(new Chain(word("x"))),word("ab"));
            case "negative-lookahead" -> new Chain(new Not(word("b")),word("a"));
            case "eof" -> new Chain(word("a"),new EndOfSourceParser());
            case "eof-empty" -> new EndOfSourceParser();
            case "optional" -> new Chain(new org.unlaxer.parser.combinator.Optional(word("b")),word("a"));
            case "repeat" -> new Chain(new ZeroOrMore(word("b")),word("a"));
            case "number" -> new NumberParser();
            case "identifier" -> new IdentifierParser();
            case "quoted" -> new SingleQuotedParser();
            case "double-quoted" -> new DoubleQuotedParser();
            case "memo" -> { Parser miss=new SafeMiss();yield new Choice(miss,miss,word("ab")); }
            default -> throw new IllegalArgumentException(op);
        };
    }
    static Parser tail() { return new MatchOnly(new Chain(word("|"),word("x"))); }
    /** #866: collectors drop the match-only and stopped leaves below, so they are not retained. */
    static Parser budgetParser(String op) {
        return switch(op) {
            case "matchonly-tail" -> new Chain(word("a"),tail());
            case "optional-matchonly" -> new Chain(word("a"),new org.unlaxer.parser.combinator.Optional(tail()));
            case "stopconsume-tail" -> new Chain(word("a"),new DoConsumePropagationStopper(new Chain(word("|"),word("x"))));
            case "matchonly-empty" -> new Chain(word("a"),new MatchOnly(new org.unlaxer.parser.combinator.Optional(word("b"))));
            case "zero-width" -> new Chain(new org.unlaxer.parser.combinator.Optional(word("b")),word("a"),tail());
            case "choice-loser" -> new Choice(new Chain(word("a"),new MatchOnly(word("y"))),new Chain(word("a"),tail()));
            case "not-tail" -> new Chain(new Not(word("b")),word("a"),tail());
            case "lookahead-tail" -> new Chain(new MatchOnly(word("a")),word("a"),tail());
            case "outer-failure" -> new Chain(new Chain(word("a"),tail()),word("z"));
            case "unicode-crlf" -> new Chain(word("😀"),new MatchOnly(new Chain(word("\r\n"),word("x"))));
            case "interleave" -> new Chain(word("a"),new MatchOnly(word("|")),word("|"),new MatchOnly(word("x")),word("x"));
            case "number-tail" -> new Chain(new NumberParser(),new MatchOnly(word("|")));
            case "invert-stop" -> new InvertMatchPropagationStopper(new Chain(word("a"),tail()));
            case "invert-matchonly" -> new Chain(word("a"),new MatchOnly(word("|")));
            case "matchonly-root" -> new MatchOnly(new Chain(word("a"),word("|")));
            case "closed-nesting" -> new Chain(word("a"),new MatchOnly(new DoConsumePropagationStopper(new Chain(word("|"),word("x")))),word("|"));
            default -> throw new IllegalArgumentException(op);
        };
    }
    // Parser observers intentionally disable Java memoization, so leaf cursors are a separate pass.
    static void observeLeafCursors(ParseContext context,Map<Token,String> cursors) {
        context.addParserListener(Name.of("trace-oracle"),new org.unlaxer.listener.ParserListener(){
            public void setLevel(org.unlaxer.listener.OutputLevel level){}
            public void onStart(Parser p,ParseContext c,TokenKind k,boolean invert){}
            public void onEnd(Parser p,Parsed parsed,ParseContext c,TokenKind k,boolean invert){if(parsed.isSucceeded()){Token t=parsed.getRootToken();if(t.filteredChildren.isEmpty())cursors.put(t,c.getConsumedPosition().value()+":"+c.getMatchedPosition().value());}}
        });
    }
    static String leafCursors(String name,List<Token> roots,Map<Token,String> cursors) {
        var tokens=new ArrayList<Token>();for(Token root:roots)terminalTokens(root,tokens);
        var observed=tokens.stream().map(t->{assertTrue(name+" real terminal callback "+t,cursors.containsKey(t));return cursors.get(t);}).collect(java.util.stream.Collectors.joining(";"));
        return observed.isEmpty()?"~":observed;
    }
    static String expectedLeaves(String field) {
        return Arrays.stream(field.split(";")).map(leaf->{var f=leaf.split(",",5);if(f.length==5)f[4]=encode(decode(f[4]));return String.join(",",f);}).collect(java.util.stream.Collectors.joining(";"));
    }
    static final class SafeMiss extends LazyChain implements SafeFailureMemoizable {
        private static final long serialVersionUID=1L;
        @Override public org.unlaxer.parser.Parsers getLazyParsers(){return new org.unlaxer.parser.Parsers(word("a"),word("x"));}
    }
    static void terminalTokens(Token token,List<Token> out){if(token.filteredChildren.isEmpty())out.add(token);else for(Token child:token.filteredChildren)terminalTokens(child,out);}
    static void leaves(Token token,List<String> out) {
        if(token.filteredChildren.isEmpty()) out.add(token.parser.getClass().getSimpleName()+","+token.tokenKind+","+token.source.offsetFromRoot().value()+","+(token.source.offsetFromRoot().value()+token.source.codePointLength().value())+","+encode(token.source.toString()));
        else for(var child:token.filteredChildren) leaves(child,out);
    }
    @Test public void detachedVirtualSourceUsesTheSharedAnchorAndTextOracle() throws Exception {
        for(String line:Files.readAllLines(Path.of("../conformance/token-trace/generated.tsv"))){if(line.startsWith("#"))continue;var c=line.split("\t");String input=decode(c[0]),text=decode(c[2]);int anchor=Integer.parseInt(c[1]);
            try(var context=new ParseContext(StringSource.createRootSource(input))){
                assertTrue(word(input).parse(context).isSucceeded());
                Token generated=new Token(TokenKind.valueOf(c[3]),StringSource.createDetachedSource(text),word(""));
                Parser collector=new Chain(){@Override public Token collect(List<Token> tokens,TokenKind kind,java.util.function.Predicate<Token> filter){return generated;}};
                Token actual=collector.parse(context).getRootToken();assertSame(generated,actual);
                var metadata=new PortableTokenMetadata(context);var id=metadata.registerGenerated(actual,anchor);var value=metadata.info(id).orElseThrow();
                assertEquals(anchor,value.offset());assertEquals(anchor,value.end());assertEquals(text,value.source());assertTrue(value.detached());assertEquals(TokenKind.valueOf(c[3]),value.kind());assertEquals(Integer.parseInt(c[4]),context.getConsumedPosition().value());assertEquals(Integer.parseInt(c[5]),context.getMatchedPosition().value());
            }
        }
    }
    @Test public void independentJavaAndRustCommittedTerminalOracle() throws Exception {
        var evidence=new ArrayList<String>(List.of("backend\tfixture\tmemo\taccepted\tconsumed\tmatched\tleaves"));
        var rows=Files.readAllLines(Path.of("../conformance/token-trace/leaves.tsv")).stream().filter(s->!s.startsWith("#")).toList();assertEquals(40,rows.size());
        for(var memo:Memoization.values())for(int rerun=0;rerun<3;rerun++)for(String line:rows) {
            var c=line.split("\t",-1);String input=decode(c[1]);var parser=parser(c[2],input);var kind=TokenKind.valueOf(c[3]);
            try(var context=ParseContext.withOptions(StringSource.createRootSource(input),ParseOptions.withMemoization(memo))) {
                context.getCurrent().setResetMatchedWithConsumed(false);
                var cursors=new IdentityHashMap<Token,String>();
                // This third pass independently checks leaf cursors; the first two check real memo.
                if(rerun==2)observeLeafCursors(context,cursors);
                var roots=new ArrayList<Token>();
                if(c[2].equals("prefix-virtual"))roots.add(word("a").parse(context,TokenKind.matchOnly,false).getRootToken());
                context.begin(parser);var result=parser.parse(context,kind,Boolean.parseBoolean(c[4]));
                if(result.isSucceeded()) context.commit(parser,kind);else context.rollback(parser);
                assertEquals(c[0],Boolean.parseBoolean(c[5]),result.isSucceeded());assertEquals(c[0],Integer.parseInt(c[6]),context.getConsumedPosition().value());assertEquals(c[0],Integer.parseInt(c[7]),context.getMatchedPosition().value());
                if(result.isSucceeded())roots.add(result.getRootToken());
                var actual=new ArrayList<String>();for(Token root:roots)leaves(root,actual);
                if(c[2].equals("memo") && rerun<2)assertEquals(memo.toString(),memo==Memoization.SAFE_FAILURES?1:0,context.getPackratMemoTable()==null?0:context.getPackratMemoTable().failureHits());
                if(rerun==2)assertEquals(c[0]+" leaf cursors",c[9],leafCursors(c[0],roots,cursors));
                String observed=actual.isEmpty()?"~":String.join(";",actual);
                assertEquals(c[0],expectedLeaves(c[8]),observed);if(rerun==0)evidence.add("java\t"+c[0]+"\t"+memo+"\t"+result.isSucceeded()+"\t"+context.getConsumedPosition().value()+"\t"+context.getMatchedPosition().value()+"\t"+observed);
            }
        }
        // #866 budget corpus. Java has no trace budget API; its real leaves, cursors and
        // leaf cursors are the retained trace that Rust must fit exactly within n.
        var budgetRows=Files.readAllLines(Path.of("../conformance/token-trace/budget.tsv")).stream().filter(s->!s.startsWith("#")).toList();assertEquals(23,budgetRows.size());
        var javaBudget=new HashMap<String,String>();
        for(var memo:Memoization.values())for(int rerun=0;rerun<3;rerun++)for(String line:budgetRows) {
            var c=line.split("\t",-1);var parser=budgetParser(c[2]);var kind=TokenKind.valueOf(c[3]);
            try(var context=ParseContext.withOptions(StringSource.createRootSource(decode(c[1])),ParseOptions.withMemoization(memo))) {
                context.getCurrent().setResetMatchedWithConsumed(Boolean.parseBoolean(c[5]));
                var cursors=new IdentityHashMap<Token,String>();if(rerun==2)observeLeafCursors(context,cursors);
                context.begin(parser);var result=parser.parse(context,kind,Boolean.parseBoolean(c[4]));
                if(result.isSucceeded()) context.commit(parser,kind);else context.rollback(parser);
                assertEquals(c[0],Boolean.parseBoolean(c[6]),result.isSucceeded());assertEquals(c[0],Integer.parseInt(c[7]),context.getConsumedPosition().value());assertEquals(c[0],Integer.parseInt(c[8]),context.getMatchedPosition().value());
                var roots=result.isSucceeded()?List.of(result.getRootToken()):List.<Token>of();var actual=new ArrayList<String>();for(Token root:roots)leaves(root,actual);
                String observed=actual.isEmpty()?"~":String.join(";",actual);assertEquals(c[0],expectedLeaves(c[9]),observed);
                if(rerun==2){String leafCursors=leafCursors(c[0],roots,cursors);assertEquals(c[0]+" leaf cursors",c[10],leafCursors);
                    String row=result.isSucceeded()+"\t"+context.getConsumedPosition().value()+"\t"+context.getMatchedPosition().value()+"\t"+observed+"\t"+leafCursors;
                    if(javaBudget.putIfAbsent(c[0],row)==null)evidence.add("java-budget\t"+c[0]+"\t"+row);else assertEquals(c[0],javaBudget.get(c[0]),row);}
            }
        }
        org.junit.Assume.assumeTrue("[assumption] token trace Rust conformance requires -DrustConformance=true",Boolean.getBoolean("rustConformance"));
        Path log=Files.createTempFile("token-trace-rust-", ".log");
        var process=new ProcessBuilder("cargo","test","--locked","--manifest-path","rust/Cargo.toml","-p","unlaxer-runtime","--test","token_trace","--","--nocapture","--test-threads=1").directory(Path.of("..").toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {assertTrue("Rust timeout",process.waitFor(120,TimeUnit.SECONDS));assertEquals(Files.readString(log),0,process.exitValue());}finally {if(process.isAlive())process.destroyForcibly();}
        var rust=Files.readAllLines(log).stream().filter(s->s.contains("TRACE\t")).map(s->s.substring(s.indexOf("TRACE\t")+6)).toList();assertEquals(80,rust.size());evidence.addAll(rust);
        // Rust's ample-budget trace equals the Java run above; its n-1/n/n+1 checks ran in cargo.
        var budget=Files.readAllLines(log).stream().filter(s->s.contains("BUDGET\t")).map(s->s.substring(s.indexOf("BUDGET\t")+7)).toList();assertEquals(budgetRows.size(),budget.size());
        for(String row:budget){var f=row.split("\t",-1);assertEquals(f[1],javaBudget.get(f[1]),String.join("\t",Arrays.copyOfRange(f,2,7)));evidence.add("rust-budget\t"+String.join("\t",Arrays.copyOfRange(f,1,f.length)));}
        Files.createDirectories(Path.of("target"));Files.write(Path.of("target/token-trace.tsv"),evidence);
    }
}
