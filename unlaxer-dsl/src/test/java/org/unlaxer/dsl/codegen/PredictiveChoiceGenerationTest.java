package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.PredictiveChoiceAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

public class PredictiveChoiceGenerationTest {
    private static final String VALID = """
        grammar Predictive {
          @package: org.example.predictive
          token NumberToken = NumberParser
          token IdentifierToken = IdentifierParser
          token SingleString = SingleQuotedParser
          token DoubleString = DoubleQuotedParser
          token CustomToken = DigitParser
          @root @mapping(Value, params=[value]) @predictiveChoice
          Root ::= Keyword @value | NumberToken @value | IdentifierToken @value
                 | SingleString @value | DoubleString @value | CustomToken @value | Recursive @value ;
          Keyword ::= 'if' | 'else' ;
          Recursive ::= Recursive 'x' | 'r' ;
        }
        """;

    @Test
    public void mapperAndJavaGeneratorPreserveConservativeFirstSets() {
        var grammar = UBNFMapper.parse(VALID).grammars().get(0);
        assertTrue(grammar.rules().get(0).annotations().stream()
            .anyMatch(PredictiveChoiceAnnotation.class::isInstance));
        String generated = new ParserGenerator().generate(grammar).source();
        assertTrue(generated.contains("class RootParser extends LazyPredictiveChoice"));
        assertTrue(generated.contains("ChoicePredictor.anyOf(ChoicePredictor.literal(\"if\"), ChoicePredictor.literal(\"else\"))"));
        assertTrue(generated.contains("ChoicePredictor.number()"));
        assertTrue(generated.contains("ChoicePredictor.identifier()"));
        assertTrue(generated.contains("ChoicePredictor.quoted('\\'')"));
        assertTrue(generated.contains("ChoicePredictor.quoted('\\\"')"));
        assertTrue(generated.contains("ChoicePredictor.any(), ChoicePredictor.any()"));
        CompileVerificationTest.assertCompiles(new ParserGenerator().generate(grammar));
    }

    @Test
    public void recognizesOnlyTheKnownTinyExpressionStringLiteralBinding() {
        String source = """
            grammar TinyStringPredictive {
              @package: org.example.predictive
              token STRING = org.unlaxer.tinyexpression.parser.StringLiteralParser
              token CUSTOM = other.StringLiteralParser
              @root @predictiveChoice Root ::= STRING | CUSTOM ;
            }
            """;
        String generated = new ParserGenerator().generate(
            UBNFMapper.parse(source).grammars().get(0)).source();
        assertTrue(generated.contains("ChoicePredictor.anyOf(ChoicePredictor.quoted('\\\"'), ChoicePredictor.quoted('\\''))"));
        assertTrue(generated.contains("ChoicePredictor.any()"));
    }

    @Test
    public void sharedFirstSubtreesAreFlattenedAndDeduplicated() {
        StringBuilder rules = new StringBuilder("  Leaf ::= 'x' | 'y' ;\n");
        String previous = "Leaf";
        for (int i = 0; i < 14; i++) {
            String current = "Layer" + i;
            rules.append("  ").append(current).append(" ::= ")
                .append(previous).append(" | ").append(previous).append(" ;\n");
            previous = current;
        }
        String source = "grammar Deduplicated {\n"
            + "  @root @predictiveChoice Root ::= " + previous + " | 'z' ;\n"
            + rules + "}\n";
        String generated = new ParserGenerator().generate(
            UBNFMapper.parse(source).grammars().get(0)).source();
        String declaration = predictorDeclaration(generated);
        assertTrue(declaration.length() < 512);
        assertTrue(declaration.contains("ChoicePredictor.anyOf(ChoicePredictor.literal(\"x\"), ChoicePredictor.literal(\"y\"))"));
        assertTrue(count(declaration, "ChoicePredictor.literal(\"x\")") == 1);
        assertTrue(count(declaration, "ChoicePredictor.literal(\"y\")") == 1);
    }

    @Test
    public void firstSetOverAtomLimitFallsBackToAny() {
        String alternatives = java.util.stream.IntStream.range(0, 65)
            .mapToObj(i -> "'k" + i + "'").collect(java.util.stream.Collectors.joining(" | "));
        String source = "grammar Bounded {\n"
            + "  @root @predictiveChoice Root ::= Many | 'fallback' ;\n"
            + "  Many ::= " + alternatives + " ;\n}\n";
        String declaration = predictorDeclaration(new ParserGenerator().generate(
            UBNFMapper.parse(source).grammars().get(0)).source());
        assertTrue(declaration.length() < 256);
        assertTrue(declaration.contains("java.util.List.of(ChoicePredictor.any(), ChoicePredictor.literal(\"fallback\"))"));
    }

    @Test
    public void validatorRejectsInvalidPlacementDuplicatesAndConflicts() {
        assertCodes("""
            grammar InvalidPredictive {
              @root @predictiveChoice Root ::= 'a' ;
            }
            """,
            "E-PREDICTIVE-CHOICE-SHAPE");
        assertCodes(VALID.replace("@predictiveChoice", "@predictiveChoice @predictiveChoice"),
            "E-PREDICTIVE-CHOICE-DUPLICATE");
        assertCodes(VALID.replace("@predictiveChoice", "@leftAssoc @predictiveChoice"),
            "E-PREDICTIVE-CHOICE-ASSOC");
        assertCodes(VALID.replace("@predictiveChoice", "@longestChoice @predictiveChoice"),
            "E-PREDICTIVE-CHOICE-CONFLICT");
    }

    private static void assertCodes(String source, String expected) {
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        List<String> codes = GrammarValidator.validate(grammar).stream()
            .map(GrammarValidator.ValidationIssue::code).toList();
        assertTrue(codes.toString(), codes.contains(expected));
    }

    private static String predictorDeclaration(String generated) {
        int start = generated.indexOf("private static final java.util.List<ChoicePredictor> __CHOICE_PREDICTORS");
        int end = generated.indexOf(';', start);
        return generated.substring(start, end + 1);
    }

    private static int count(String source, String needle) {
        int count = 0;
        for (int offset = 0; (offset = source.indexOf(needle, offset)) >= 0; offset += needle.length()) {
            count++;
        }
        return count;
    }
}
