package org.unlaxer.dsl.bootstrap;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public class UBNFAnnotationParityTest {
    @Test public void evalStrategyIsNamedAndDefaultsIndependentlyOfParameters() {
        assertEquals(new UBNFAST.EvalAnnotation("variable_ref", "default", Map.of("strip_prefix", "$")),
            annotation("@eval(kind='variable_ref', strip_prefix='$')"));
        assertEquals(new UBNFAST.EvalAnnotation("literal", "manual", Map.of("prefix", "😀\n", "suffix", "'")),
            annotation("@eval(kind='literal', prefix='😀\\n', strategy='manual', suffix='\\\'')"));
        assertEquals(new UBNFAST.EvalAnnotation("literal", "manual", Map.of()),
            annotation("@eval(kind='literal', strategy='manual')"));
        for (String args : List.of("kind='literal'", "strategy='manual', strategy='default'", "x='a', x='b'")) {
            assertThrows(args, IllegalArgumentException.class,
                () -> annotation("@eval(kind='literal', " + args + ")"));
        }
    }

    @Test public void evalParametersKeepSourceOrder() {
        var eval = (UBNFAST.EvalAnnotation) annotation("@eval(kind='literal', z='1', a='2', m='3', b='4')");
        assertEquals(List.of("z", "a", "m", "b"), List.copyOf(eval.params().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> eval.params().put("c", "5"));
    }

    @Test public void builtinPrefixesRemainWholeSimpleAnnotationNames() {
        for (String keyword : List.of("root", "whitespace", "leftAssoc", "rightAssoc", "longestChoice",
                "uniqueLongestChoice", "predictiveChoice", "skip", "enum")) {
            for (String suffix : List.of("ed", "9", "_extra")) {
                assertEquals(new UBNFAST.SimpleAnnotation(keyword + suffix), annotation("@" + keyword + suffix));
            }
        }
        assertEquals(new UBNFAST.RootAnnotation(), annotation("@root"));
        assertEquals(new UBNFAST.EnumAnnotation(), annotation("@enum"));
        assertThrows(IllegalArgumentException.class, () -> annotation("@rooted('x')"));
    }

    @Test public void commonFieldAcceptsOrdinaryTriviaAndPreservesOrder() {
        assertEquals(new UBNFAST.CommonFieldAnnotation(List.of("left", "right", "third")),
            annotation("@commonField(left, // 😀 comment\r\n right,\tthird)"));
    }

    private static UBNFAST.Annotation annotation(String text) {
        String prefix = "// 😀\r\ngrammar G {\r\n ";
        var snapshot = UBNFMapper.parseWithSource(prefix + text + " Root ::= 'x'; }");
        var value = snapshot.ast().grammars().get(0).rules().get(0).annotations().get(0);
        assertEquals(text, snapshot.sourceOf(value).orElseThrow());
        int start = prefix.codePointCount(0, prefix.length());
        assertEquals(new UBNFSourceSnapshot.Span(start, start + text.codePointCount(0, text.length())),
            snapshot.spanOf(value).orElseThrow());
        return value;
    }
}
