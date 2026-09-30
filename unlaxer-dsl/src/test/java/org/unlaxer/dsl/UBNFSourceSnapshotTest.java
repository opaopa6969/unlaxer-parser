package org.unlaxer.dsl;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot.Kind;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot.Span;

public class UBNFSourceSnapshotTest {
    @Test public void retainsTriviaAwareCodepointSpansAndDistinctEqualOccurrences() {
        String source = "// 😀 header\r\n grammar G {\r\n"
            + " @import lib from '😀.ubnf' // import\r\n"
            + " @package: a.b\r\n @settings: { key:'// 😀' other:'x' }\r\n"
            + " token T = ANY\r\n @root @doc('😀') R ::= '😀' @x // middle\r\n"
            + " '😀' @y; // tail\r\n } // eof\r\n";
        var snapshot = UBNFMapper.parseWithSource(source);
        assertEquals(UBNFMapper.parse(source), snapshot.ast());
        assertEquals(source, snapshot.sourceOf(snapshot.ast()).orElseThrow());
        var grammar = snapshot.ast().grammars().get(0);
        assertSource(snapshot, grammar, source.substring(source.indexOf("grammar"), source.lastIndexOf('}') + 1));
        assertSource(snapshot, grammar.imports().get(0), "@import lib from '😀.ubnf'");
        assertSource(snapshot, grammar.settings().get(0), "@package: a.b");
        assertSource(snapshot, grammar.settings().get(0).value(), "a.b");
        assertSource(snapshot, ((BlockSettingValue) grammar.settings().get(1).value()).entries().get(0), "key:'// 😀'");
        assertSource(snapshot, grammar.tokens().get(0), "token T = ANY");
        var rule = grammar.rules().get(0);
        assertSource(snapshot, rule.annotations().get(0), "@root");
        assertSource(snapshot, rule.annotations().get(1), "@doc('😀')");
        var elements = ((ChoiceBody) rule.body()).alternatives().get(0).elements();
        assertEquals(elements.get(0).element(), elements.get(1).element());
        assertNotEquals(snapshot.spanOf(elements.get(0).element()), snapshot.spanOf(elements.get(1).element()));
        assertSource(snapshot, elements.get(0).element(), "'😀'");
        assertSource(snapshot, elements.get(1), "'😀' @y");
        assertEquals("@x", snapshot.slice(snapshot.captureSpan(elements.get(0)).orElseThrow()));
        assertFalse(snapshot.spanOf(new TerminalElement("😀")).isPresent());
        assertFalse(snapshot.spanOf(null).isPresent());
    }

    @Test public void syntheticAndRepairedNodesHaveHonestOrigins() {
        var snapshot = UBNFMapper.parseWithSource(
            "grammar G { R ::= 'x' @typeof(first) T @next T? T* T+ T{2,3} T % ','; }");
        var elements = ((ChoiceBody) snapshot.ast().grammars().get(0).rules().get(0).body())
            .alternatives().get(0).elements();
        assertSource(snapshot, elements.get(0), "'x'");
        assertSource(snapshot, elements.get(1), "@typeof(first) T @next");
        assertSource(snapshot, elements.get(1).typeofConstraint().orElseThrow(), "@typeof(first)");
        assertEquals(Kind.REWRITTEN, snapshot.originOf(elements.get(1)).orElseThrow().kind());
        assertEquals("@next", snapshot.slice(snapshot.captureSpan(elements.get(1)).orElseThrow()));
        assertFalse(snapshot.captureSpan(elements.get(0)).isPresent());
        assertSource(snapshot, elements.get(2).element(), "T?");
        var optional = (OptionalElement) elements.get(2).element();
        assertSource(snapshot, optional.body(), "T");
        assertEquals(Kind.SYNTHETIC, snapshot.originOf(optional.body()).orElseThrow().kind());
        assertSource(snapshot, elements.get(3).element(), "T*");
        assertSource(snapshot, elements.get(4).element(), "T+");
        assertSource(snapshot, elements.get(5).element(), "T{2,3}");
        assertSource(snapshot, elements.get(6).element(), "T % ','");
    }

    @Test public void snapshotsSurviveLaterFailedAndConcurrentParses() throws Exception {
        String source = "grammar G { R ::= '😀'; }";
        var saved = UBNFMapper.parseWithSource(source);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<UBNFSourceSnapshot>>();
            for (int i = 0; i < 20; i++) {
                String input = "// prefix " + i + "\n" + source;
                tasks.add(() -> UBNFMapper.parseWithSource(input));
            }
            for (var future : pool.invokeAll(tasks)) {
                var other = future.get();
                assertEquals(saved.ast(), other.ast());
                assertFalse(saved.spanOf(other.ast()).isPresent());
                assertEquals(source, other.sourceOf(other.ast().grammars().get(0)).orElseThrow());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThrows(IllegalArgumentException.class, () -> UBNFMapper.parseWithSource("invalid"));
        assertEquals(source, saved.sourceOf(saved.ast()).orElseThrow());
    }

    @Test public void rejectsInvalidSpanAndPreservesImportApi() {
        assertThrows(IllegalArgumentException.class, () -> new Span(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Span(2, 1));
        var snapshot = UBNFMapper.parseWithSource("grammar G { R ::= 'x'; }");
        assertThrows(IndexOutOfBoundsException.class, () -> snapshot.slice(new Span(0, 100)));
        String imported = "grammar G { @import a from 'absent' R ::= a.T; }";
        assertEquals(1, UBNFMapper.parseWithSource(imported).ast().grammars().get(0).imports().size());
        assertThrows(IllegalArgumentException.class, () -> UBNFMapper.parseWithImports(imported, path -> null));
    }

    private static void assertSource(UBNFSourceSnapshot snapshot, Object node, String expected) {
        assertEquals(node.toString(), expected, snapshot.sourceOf(node).orElseThrow());
        var span = snapshot.spanOf(node).orElseThrow();
        assertEquals(expected.codePointCount(0, expected.length()), span.end() - span.start());
    }
}
