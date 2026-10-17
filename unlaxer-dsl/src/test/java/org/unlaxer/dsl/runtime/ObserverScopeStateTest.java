package org.unlaxer.dsl.runtime;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.listener.*;
import org.unlaxer.parser.*;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.*;

/** Semantic scope journal rollback shares the same handwritten failure-stage oracle. */
public class ObserverScopeStateTest {
    @Test public void sharedFailureOracleRestoresScopesDeclarationsReferencesAndDiagnostics() throws Exception {
        var rows=Files.readAllLines(Path.of("../conformance/observer-failures/cases.tsv")).stream()
            .filter(row->!row.startsWith("#")&&!row.isEmpty()).toList();
        assertEquals(20,rows.size());
        for(Memoization memo:Memoization.values())for(ParseOptions.Diagnostics diagnostics:ParseOptions.Diagnostics.values())for(String row:rows){
            String[] data=row.split("\t",-1);String label=data[0]+"/"+memo+"/"+diagnostics;
            try(var context=ParseContext.withOptions(StringSource.createRootSource(decode(data[3])),ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))){
                context.getCurrent().setResetMatchedWithConsumed(false);
                new WordParser(decode(data[4])).parse(context);
                if(!data[5].isEmpty())new WordParser(decode(data[5])).parse(context,TokenKind.matchOnly,false);
                ScopeStore.enter(context);ScopeStore.declare(context,"baseline",0);ScopeStore.addReference(context,"baseline",0,1);
                WordParser leaf=new WordParser("a");Parser observed=new MatchOnly(data[1].equals("fail")?new WordParser("b"):leaf);Parser root=observed;
                switch(data[1]){
                    case "leaf"->{observed=leaf;root=leaf;}
                    case "nested"->root=new Chain(observed,new WordParser("!"));
                    case "child"->{observed=leaf;root=new MatchOnly(leaf);}
                    case "peg"->{observed=leaf;root=new PegLookahead(leaf);}
                    default->{}
                }
                Parser target=observed;RuntimeException original=new IllegalStateException(label);var events=new ArrayList<String>();
                java.util.function.Consumer<String> event=stage->{
                    events.add(stage);ScopeStore.enter(context);ScopeStore.declare(context,"inside",1);
                    ScopeStore.addReference(context,"inside",1,1);ScopeStore.addDiagnostic(context,"speculative",1,1,ScopeStore.Severity.ERROR);
                    if(stage.equals(data[2]))throw original;
                };
                class Listener implements ParserListener,TransactionListener{
                    public void setLevel(OutputLevel level){}
                    public void onOpen(ParseContext c){}public void onClose(ParseContext c){}
                    public void onStart(Parser p,ParseContext c,TokenKind kind,boolean invert){if(p==target)event.accept("start");}
                    public void onEnd(Parser p,Parsed parsed,ParseContext c,TokenKind kind,boolean invert){if(p==target)event.accept("end");}
                    public void onBegin(ParseContext c,Parser p){if(p==target)event.accept("begin");}
                    public void onCommit(ParseContext c,Parser p,TokenList tokens){if(p==target)event.accept("commit");}
                    public void onRollback(ParseContext c,Parser p,TokenList tokens){if(p==target)event.accept("rollback");}
                }
                var listener=new Listener();context.addParserListener(Name.of("scope"),listener);context.addTransactionListener(Name.of("scope"),listener);
                if(data[2].equals("precommit"))context.addActions((Transaction.AdditionalPreCommitAction)(p,c)->{if(p==target)event.accept("precommit");});
                if(data[2].equals("postcommit"))context.addActions((Transaction.AdditionalPostCommitAction)(p,c,tokens)->{if(p==target)event.accept("postcommit");});
                Parser entry=root;assertSame(label,original,assertThrows(IllegalStateException.class,()->entry.parse(context)));
                assertEquals(label,data[6],String.join(",",events));
                assertEquals(label,Integer.parseInt(data[13]),ScopeStore.currentScopeDepth(context));
                assertEquals(label,Integer.parseInt(data[14]),ScopeStore.getAllDeclarations(context).size());
                assertEquals(label,Integer.parseInt(data[15]),ScopeStore.getAllReferences(context).size());
                assertEquals(label,Integer.parseInt(data[16]),ScopeStore.getDiagnostics(context).size());
                assertTrue(label,ScopeStore.isDeclared(context,"baseline"));assertFalse(label,ScopeStore.isDeclared(context,"inside"));
            }
        }
    }
    private static String decode(String hex){return new String(HexFormat.of().parseHex(hex),StandardCharsets.UTF_8);}
}
