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
                // Parser observers intentionally disable Java memoization. This third
                // pass independently checks leaf cursors; the first two check real memo.
                if(rerun==2)context.addParserListener(Name.of("trace-oracle"),new org.unlaxer.listener.ParserListener(){
                    public void setLevel(org.unlaxer.listener.OutputLevel level){}
                    public void onStart(Parser p,ParseContext c,TokenKind k,boolean invert){}
                    public void onEnd(Parser p,Parsed parsed,ParseContext c,TokenKind k,boolean invert){if(parsed.isSucceeded()){Token t=parsed.getRootToken();if(t.filteredChildren.isEmpty())cursors.put(t,c.getConsumedPosition().value()+":"+c.getMatchedPosition().value());}}
                });
                var roots=new ArrayList<Token>();
                if(c[2].equals("prefix-virtual"))roots.add(word("a").parse(context,TokenKind.matchOnly,false).getRootToken());
                context.begin(parser);var result=parser.parse(context,kind,Boolean.parseBoolean(c[4]));
                if(result.isSucceeded()) context.commit(parser,kind);else context.rollback(parser);
                assertEquals(c[0],Boolean.parseBoolean(c[5]),result.isSucceeded());assertEquals(c[0],Integer.parseInt(c[6]),context.getConsumedPosition().value());assertEquals(c[0],Integer.parseInt(c[7]),context.getMatchedPosition().value());
                if(result.isSucceeded())roots.add(result.getRootToken());
                var actual=new ArrayList<String>();for(Token root:roots)leaves(root,actual);
                if(c[2].equals("memo") && rerun<2)assertEquals(memo.toString(),memo==Memoization.SAFE_FAILURES?1:0,context.getPackratMemoTable()==null?0:context.getPackratMemoTable().failureHits());
                if(rerun==2){var tokens=new ArrayList<Token>();for(Token root:roots)terminalTokens(root,tokens);var observedCursors=tokens.stream().map(t->{assertTrue(c[0]+" real terminal callback "+t,cursors.containsKey(t));return cursors.get(t);}).collect(java.util.stream.Collectors.joining(";"));assertEquals(c[0]+" leaf cursors",c[9],observedCursors.isEmpty()?"~":observedCursors);}
                String observed=actual.isEmpty()?"~":String.join(";",actual);
                String expected=Arrays.stream(c[8].split(";")).map(leaf->{var f=leaf.split(",",5);if(f.length==5)f[4]=encode(decode(f[4]));return String.join(",",f);}).collect(java.util.stream.Collectors.joining(";"));
                assertEquals(c[0],expected,observed);if(rerun==0)evidence.add("java\t"+c[0]+"\t"+memo+"\t"+result.isSucceeded()+"\t"+context.getConsumedPosition().value()+"\t"+context.getMatchedPosition().value()+"\t"+observed);
            }
        }
        org.junit.Assume.assumeTrue("[assumption] token trace Rust conformance requires -DrustConformance=true",Boolean.getBoolean("rustConformance"));
        Path log=Files.createTempFile("token-trace-rust-", ".log");
        var process=new ProcessBuilder("cargo","test","--locked","--manifest-path","rust/Cargo.toml","-p","unlaxer-runtime","--test","token_trace","--","--nocapture","--test-threads=1").directory(Path.of("..").toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {assertTrue("Rust timeout",process.waitFor(120,TimeUnit.SECONDS));assertEquals(Files.readString(log),0,process.exitValue());}finally {if(process.isAlive())process.destroyForcibly();}
        var rust=Files.readAllLines(log).stream().filter(s->s.contains("TRACE\t")).map(s->s.substring(s.indexOf("TRACE\t")+6)).toList();assertEquals(80,rust.size());evidence.addAll(rust);
        Files.createDirectories(Path.of("target"));Files.write(Path.of("target/token-trace.tsv"),evidence);
    }
}
