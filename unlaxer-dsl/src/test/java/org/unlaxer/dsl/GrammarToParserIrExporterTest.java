package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.ir.GrammarToParserIrExporter;
import org.unlaxer.dsl.ir.ParserIrConformanceValidator;
import org.unlaxer.dsl.ir.ParserIrDocument;

public class GrammarToParserIrExporterTest {

    @Test
    public void testExportIncludesAdvancedAnnotationPayloads() {
        GrammarDecl grammar = parseGrammar("""
            grammar G {
              @package: org.example
              @root
              @mapping(RootNode, params=[v])
              @interleave(profile=javaStyle)
              @backref(name=ident)
              @scopeTree(mode=lexical)
              @leftAssoc
              @precedence(level=10)
              Start ::= 'ok' @v ;
            }
            """);

        ParserIrDocument document = GrammarToParserIrExporter.export(grammar, "in-memory://g.ubnf");
        @SuppressWarnings("unchecked")
        List<Object> annotations = (List<Object>) document.payload().get("annotations");
        assertTrue(!annotations.isEmpty());

        assertTrue(hasAnnotation(annotations, "interleave", "profile", "javaStyle"));
        assertTrue(hasAnnotation(annotations, "backref", "name", "ident"));
        assertTrue(hasAnnotation(annotations, "scope-tree", "mode", "lexical"));
        assertTrue(hasAnnotation(annotations, "precedence", "level", 10L));
        assertTrue(document.payload().containsKey("scopeEvents"));
        assertEquals(2, JsonTestUtil.getArray(document.payload(), "scopeEvents").size());
        @SuppressWarnings("unchecked")
        Map<String, Object> firstScopeEvent = (Map<String, Object>) JsonTestUtil.getArray(document.payload(), "scopeEvents").get(0);
        assertEquals("lexical", firstScopeEvent.get("scopeMode"));
    }

    @Test
    public void testExportedDocumentSatisfiesConformanceValidator() {
        GrammarDecl grammar = parseGrammar("""
            grammar G {
              @package: org.example
              @root
              @mapping(RootNode, params=[v])
              @interleave(profile=commentsAndSpaces)
              @scopeTree(mode=dynamic)
              Start ::= 'ok' @v ;
            }
            """);

        ParserIrDocument document = GrammarToParserIrExporter.export(grammar, "in-memory://g.ubnf");
        ParserIrConformanceValidator.validate(document);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstScopeEvent = (Map<String, Object>) JsonTestUtil.getArray(document.payload(), "scopeEvents").get(0);
        assertEquals("dynamic", firstScopeEvent.get("scopeMode"));
    }

    @Test
    public void testExportAllSupportsMultipleGrammars() {
        GrammarDecl a = parseGrammar("""
            grammar A {
              @package: org.example.a
              @root
              @mapping(NodeA, params=[v])
              Start ::= 'a' @v ;
            }
            """);
        GrammarDecl b = parseGrammar("""
            grammar B {
              @package: org.example.b
              @root
              @mapping(NodeB, params=[v])
              Start ::= 'b' @v ;
            }
            """);

        ParserIrDocument document = GrammarToParserIrExporter.exportAll(List.of(a, b), "in-memory://multi.ubnf");
        ParserIrConformanceValidator.validate(document);
        List<Object> nodes = JsonTestUtil.getArray(document.payload(), "nodes");
        assertEquals(2, nodes.size());
    }

    @Test
    public void testExportAllScopeEventsPreserveGrammarQualifiedScopeIds() {
        GrammarDecl a = parseGrammar("""
            grammar A {
              @package: org.example.a
              @scopeTree(mode=lexical)
              Start ::= 'a' ;
            }
            """);
        GrammarDecl b = parseGrammar("""
            grammar B {
              @package: org.example.b
              @scopeTree(mode=dynamic)
              Start ::= 'b' ;
            }
            """);

        ParserIrDocument document = GrammarToParserIrExporter.exportAll(List.of(a, b), "in-memory://multi-scope.ubnf");
        ParserIrConformanceValidator.validate(document);
        List<Object> scopeEvents = JsonTestUtil.getArray(document.payload(), "scopeEvents");
        assertEquals(4, scopeEvents.size());
        String text = scopeEvents.toString();
        assertTrue(text.contains("scope:A::Start"));
        assertTrue(text.contains("scope:B::Start"));
    }

    private static boolean hasAnnotation(List<Object> annotations, String name, String payloadKey, Object payloadValue) {
        for (Object item : annotations) {
            @SuppressWarnings("unchecked")
            Map<String, Object> annotation = (Map<String, Object>) item;
            if (!name.equals(annotation.get("name"))) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) annotation.get("payload");
            Object actual = payload.get(payloadKey);
            if (payloadValue instanceof Long && actual instanceof Number number) {
                if (number.longValue() == (Long) payloadValue) {
                    return true;
                }
                continue;
            }
            if (payloadValue.equals(actual)) {
                return true;
            }
        }
        return false;
    }

    private static GrammarDecl parseGrammar(String source) {
        return UBNFMapper.parse(source).grammars().get(0);
    }
}
