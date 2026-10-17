package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.dsl.runtime.ScopeStore;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.WordParser;

public class PegLookaheadStateTest {
    private static final class State implements TransactionalState {
        int value=7;
        public Runnable checkpoint() {int before=value;return ()->value=before;}
    }
    @Test public void sharedStateOracle() throws Exception {
        var rows=Files.readAllLines(Path.of("../conformance/peg-assertion/state.tsv")).stream().filter(row->!row.startsWith("#")).toList();
        assertEquals(4,rows.size());
        for(var memo:Memoization.values()) for(var diagnostics:ParseOptions.Diagnostics.values()) for(String row:rows) {
            String[] c=row.split("\t");assertEquals(13,c.length);
            State state=new State();
            try(var context=ParseContext.withOptions(StringSource.createRootSource("a;"),ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                context.registerTransactionalState(state);
                ScopeStore.declare(context,"before",0);ScopeStore.addReference(context,"before",0,1);
                ScopeStore.addDiagnostic(context,"baseline",0,1,ScopeStore.Severity.INFO);
                Parser mutate=new WordParser("a") {
                    @Override public Token getToken(ParseContext current,TokenKind kind,boolean invert) {
                        Token value=super.getToken(current,kind,invert);
                        if(value.source.isPresent()) {
                            state.value=99;ScopeStore.enter(current);ScopeStore.declare(current,"inside",0);
                            ScopeStore.addReference(current,"inside",0,1);
                            ScopeStore.addDiagnostic(current,"speculation",0,1,ScopeStore.Severity.WARNING);
                            value.putExtraObject(Name.of("capture"),"inside");
                        }
                        return value;
                    }
                };
                Parser read=new WordParser(";") {
                    @Override public Token getToken(ParseContext current,TokenKind kind,boolean invert) {
                        assertEquals(99,state.value);assertTrue(ScopeStore.isDeclared(current,"inside"));
                        return super.getToken(current,kind,invert);
                    }
                };
                Parser body=new Chain(mutate,read);
                if(!Boolean.parseBoolean(c[2]))body=new Chain(body,new WordParser("!"));
                for(int retry=0;retry<2;retry++) {
                    Parsed value=new PegLookahead(body,Boolean.parseBoolean(c[1])).parse(context);
                    assertEquals(c[0],Boolean.parseBoolean(c[3]),value.isSucceeded());
                    int[] actual={state.value,ScopeStore.currentScopeDepth(context),ScopeStore.getAllDeclarations(context).size(),ScopeStore.getAllReferences(context).size(),ScopeStore.getDiagnostics(context).size(),RecoveryDiagnostic.from(context).size(),context.getConsumedPosition().value(),context.getMatchedPosition().value(),context.getCurrent().getTokens().size()};
                    for(int i=0;i<actual.length;i++)assertEquals(c[0]+"/"+i,Integer.parseInt(c[i+4]),actual[i]);
                    assertFalse(ScopeStore.isDeclared(context,"inside"));
                    assertTrue(value.getOriginalTokens().isEmpty());
                }
            }
        }
    }
}
