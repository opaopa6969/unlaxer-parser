package org.unlaxer.dsl.codegen.rust;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

public class RustPredictiveChoiceBackendTest {
    private static final String VALID = """
        grammar PredictiveRust {
          token NumberToken = NumberParser
          token IdentifierToken = IdentifierParser
          token StringToken = org.unlaxer.tinyexpression.parser.StringLiteralParser
          @root @mapping(Value, params=[value]) @predictiveChoice
          Root ::= Keyword @value | NumberToken @value | IdentifierToken @value | StringToken @value ;
          Keyword ::= 'if' | 'else' ;
        }
        """;

    @Test
    public void javaFrontendEmitsTheSharedRustPredictiveRuntimeNode() {
        String parser = parser(VALID);
        assertTrue(parser.contains("Expr::PredictiveChoice {"));
        assertTrue(parser.contains("unlaxer_runtime::Predictor::OneOf(vec!["));
        assertTrue(parser.contains("unlaxer_runtime::Predictor::Literal(\"if\")"));
        assertTrue(parser.contains("unlaxer_runtime::Predictor::Literal(\"else\")"));
        assertTrue(parser.contains("unlaxer_runtime::Predictor::Number"));
        assertTrue(parser.contains("unlaxer_runtime::Predictor::Identifier"));
        assertTrue(parser.contains("unlaxer_runtime::Predictor::Quoted('\\u{22}')"));
        assertTrue(parser.contains("unlaxer_runtime::Predictor::Quoted('\\u{27}')"));
    }

    @Test
    public void mixedValueProjectionPreservesPredictors() {
        String source = """
            grammar MixedPredictiveRust {
              @root @mapping(Root, params=[value]) @predictiveChoice
              Start ::= 'a' @value | Child @value ;
              @mapping(Child, params=[text]) Child ::= 'child' @text ;
            }
            """;
        String parser = parser(source);
        assertTrue(parser.contains("Expr::PredictiveChoice {"));
        assertTrue(parser.contains("Expr::Literal(\"a\").text_value()"));
        assertTrue(parser.contains("unlaxer_runtime::Predictor::Literal(\"child\")"));
    }

    @Test
    public void sharedFirstSetsAreFlatAndDeduplicated() {
        String source = """
            grammar CanonicalPredictiveRust {
              @root @mapping(Value, params=[value]) @predictiveChoice
              Root ::= Shared @value | 'z' @value ;
              Shared ::= Atoms | Atoms | Atoms ;
              Atoms ::= 'a' | 'b' | 'a' | 'b' ;
            }
            """;
        String parser = parser(source);
        assertEquals(1, occurrences(parser, "Predictor::Literal(\"a\")"));
        assertEquals(1, occurrences(parser, "Predictor::Literal(\"b\")"));
        assertFalse(parser.contains("OneOf(vec![unlaxer_runtime::Predictor::OneOf"));
    }

    @Test
    public void largeFirstSetsFallBackToBoundedAny() {
        String atoms = java.util.stream.IntStream.range(0, 65)
            .mapToObj(index -> "'k" + index + "'")
            .collect(java.util.stream.Collectors.joining(" | "));
        String source = """
            grammar BoundedPredictiveRust {
              @root @mapping(Value, params=[value]) @predictiveChoice
              Root ::= Atoms @value | 'z' @value ;
              Atoms ::= %s ;
            }
            """.formatted(atoms);
        String parser = parser(source);
        assertTrue(parser.contains("predictors: vec![unlaxer_runtime::Predictor::Any"));
        assertEquals(0, occurrences(parser, "Predictor::Literal(\"k"));
    }

    @Test
    public void directRustLoweringRejectsInvalidPredictiveAnnotations() {
        for (String source : new String[] {
            VALID.replace("Keyword @value | NumberToken @value | IdentifierToken @value | StringToken @value", "Keyword @value"),
            VALID.replace("@predictiveChoice", "@predictiveChoice @predictiveChoice"),
            VALID.replace("@predictiveChoice", "@longestChoice @predictiveChoice")
        }) {
            assertThrows(IllegalArgumentException.class,
                () -> new RustBackend().generate(UBNFMapper.parse(source).grammars().get(0)));
        }
    }

    private static String parser(String source) {
        return new RustBackend().generate(UBNFMapper.parse(source).grammars().get(0)).stream()
            .filter(file -> file.relativePath().endsWith("parser.rs"))
            .findFirst().orElseThrow().content();
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int offset = 0; (offset = source.indexOf(needle, offset)) >= 0; offset += needle.length()) {
            count++;
        }
        return count;
    }
}
