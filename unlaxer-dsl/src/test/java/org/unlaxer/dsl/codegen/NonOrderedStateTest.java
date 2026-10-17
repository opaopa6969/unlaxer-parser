package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.dsl.runtime.ScopeStore;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.WordParser;

public class NonOrderedStateTest {
    private static final class State implements TransactionalState {
        int value = 7;
        public Runnable checkpoint() { int old = value; return () -> value = old; }
    }
    private static final class CaptureSite extends Chain {
        private static final long serialVersionUID = 1L;
        CaptureSite(Parser child) { super(child); }
    }
    private static Parser recovery() { return SyncPointRecoveryParser.withMessage(new WordParser("ok"), "bad", ";"); }
    private static Parser dirty(State state) {
        return new WordParser("a") {
            @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
                if (context.getPosition(kind).value() == 0) {
                    state.value = 99;
                    ScopeStore.enter(context);
                    ScopeStore.declare(context, "leaked", 0);
                    ScopeStore.addReference(context, "leaked", 0, 1);
                    ScopeStore.addDiagnostic(context, "leaked", 0, 1, ScopeStore.Severity.WARNING);
                    new CaptureSite(recovery()).parse(context, kind, invert);
                    return Parsed.FAILED;
                }
                state.value = 9;
                ScopeStore.declare(context, "selected", 2);
                ScopeStore.addReference(context, "selected", 2, 1);
                ScopeStore.addDiagnostic(context, "selected", 2, 1, ScopeStore.Severity.WARNING);
                return new CaptureSite(new WordParser("a")).parse(context, kind, invert);
            }
        };
    }
    @Test public void sharedScopeCaptureStateAndRecoveryRollback() throws Exception {
        for (var memo : Memoization.values()) for (var diagnostics : ParseOptions.Diagnostics.values()) {
            for (String row : Files.readAllLines(Path.of("../spec-corpus/nonordered/state.tsv"))) {
                if (row.startsWith("#")) continue;
                String[] f = row.split("\t");
                State state = new State();
                Parser n = f[0].startsWith("STATE") ? new NonOrdered(dirty(state), new WordParser("b;"))
                    : new NonOrdered(recovery(), new WordParser("a"));
                Parser parser = f[0].endsWith("FAIL") ? new Chain(n, new WordParser("!"))
                    : f[0].endsWith("RETRY") ? new Choice(new Chain(n, new WordParser("!")), n) : n;
                try (var context = ParseContext.withOptions(StringSource.createRootSource(f[1]), ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                    context.registerTransactionalState(state);
                    var result = parser.parse(context);
                    String label = f[0] + "/" + memo + "/" + diagnostics;
                    assertEquals(label, Boolean.parseBoolean(f[2]), result.isSucceeded());
                    assertEquals(label, Integer.parseInt(f[3]), context.getConsumedPosition().value());
                    assertEquals(label, Integer.parseInt(f[3]), context.getMatchedPosition().value());
                    assertEquals(label, Integer.parseInt(f[4]), state.value);
                    assertEquals(label, Integer.parseInt(f[5]), ScopeStore.getAllDeclarations(context).size());
                    assertEquals(label, Integer.parseInt(f[6]), ScopeStore.getAllReferences(context).size());
                    assertEquals(label, Integer.parseInt(f[7]), ScopeStore.getDiagnostics(context).size());
                    assertEquals(label, 0, ScopeStore.currentScopeDepth(context));
                    String recoveries = String.join(",", RecoveryDiagnostic.from(context).stream().map(r -> {
                        assertEquals("bad", r.message()); return r.start() + ":" + r.end();
                    }).toList());
                    assertEquals(label, f[8].equals("~") ? "" : f[8], recoveries);
                    var captures = new ArrayList<String>();
                    for (Token root : context.getCurrent().getTokens()) for (Token token : root.flatten(Token.ScanDirection.Depth, Token.ChildrenKind.original)) {
                        if (token.parser instanceof CaptureSite) {
                            int start = token.source.offsetFromRoot().value();
                            captures.add(start + ":" + (start + token.source.codePointLength().value()));
                        }
                    }
                    assertEquals(label, f[9].equals("~") ? "" : f[9], String.join(",", captures));
                    if (!result.isSucceeded()) {
                        assertTrue(context.getCurrent().getTokens().isEmpty());
                        assertTrue(new WordParser(f[1]).parse(context).isSucceeded());
                    }
                }
            }
        }
    }
}
