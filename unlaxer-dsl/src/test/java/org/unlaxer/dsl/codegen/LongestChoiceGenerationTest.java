package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.LongestChoiceAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;

public class LongestChoiceGenerationTest {
    private static final String VALID = """
        grammar Longest {
          @package: org.example.longest
          @root @mapping(Value, params=[value]) @longestChoice
          Root ::= 'a' @value | 'abc' @value ;
        }
        """;

    @Test
    public void mapperAndJavaGeneratorPreserveLongestChoice() {
        var grammar = UBNFMapper.parse(VALID).grammars().get(0);
        assertTrue(grammar.rules().get(0).annotations().stream()
            .anyMatch(LongestChoiceAnnotation.class::isInstance));
        String generated = new ParserGenerator().generate(grammar).source();
        assertTrue(generated.contains("class RootParser extends LazyLongestChoice"));
    }

    @Test
    public void javaRustBackendEmitsLongestChoiceRuntimeNode() {
        var grammar = UBNFMapper.parse(VALID).grammars().get(0);
        String parser = new RustBackend().generate(grammar).stream()
            .filter(file -> file.relativePath().endsWith("parser.rs"))
            .findFirst().orElseThrow().content();
        assertTrue(parser.contains("Expr::LongestChoice(vec!["));
    }

    @Test
    public void javaRustBackendPreservesMixedValueProjectionInsideLongestChoice() {
        String source = """
            grammar MixedLongest {
              @root @mapping(Root, params=[value]) @longestChoice
              Start ::= 'a' @value | Child @value ;
              @mapping(Child, params=[text]) Child ::= 'abc' @text ;
            }
            """;
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        String parser = new RustBackend().generate(grammar).stream()
            .filter(file -> file.relativePath().endsWith("parser.rs"))
            .findFirst().orElseThrow().content();
        assertTrue(parser.contains("Expr::LongestChoice(vec!["));
        assertTrue(parser.contains(".text_value()"));
    }

    @Test
    public void validatorRejectsInvalidPlacementAndDuplicates() {
        assertCodes(VALID.replace("'a' @value | 'abc' @value", "'a' @value"),
            "E-LONGEST-CHOICE-SHAPE");
        assertCodes(VALID.replace("@longestChoice", "@longestChoice @longestChoice"),
            "E-LONGEST-CHOICE-DUPLICATE");
        assertCodes(VALID.replace("@longestChoice", "@leftAssoc @longestChoice"),
            "E-LONGEST-CHOICE-ASSOC");
        assertCodes(VALID.replace("@longestChoice", "@rightAssoc @longestChoice"),
            "E-LONGEST-CHOICE-ASSOC");
    }

    private static void assertCodes(String source, String expected) {
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        List<String> codes = GrammarValidator.validate(grammar).stream()
            .map(GrammarValidator.ValidationIssue::code)
            .toList();
        assertTrue(codes.toString(), codes.contains(expected));
    }
}
