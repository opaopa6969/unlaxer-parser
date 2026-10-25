package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.unlaxer.dsl.NestingLimitGrammars;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/** Integer refinement of the Java host's Rust backend (#740 / #758). */
public class NumericFieldsTest {
    /** The port of numeric_fields.rs agrees with the Java type inference wherever both apply. */
    @Test public void nativeCaptureRuleAgreesWithJavaTypeInference() throws Exception {
        List<String> sources = new ArrayList<>();
        for (var row : JsonParser.parseString(Files.readString(Path.of("../spec-corpus/numeric-captures/cases.json"))).getAsJsonArray()) {
            sources.add(row.getAsJsonObject().get("grammar").getAsString());
        }
        sources.add(NestingLimitGrammars.recursivePlusWithNumber(4));
        sources.add(Files.readString(Path.of("src/test/resources/cardinality/Cardinality.ubnf")));
        int fields = 0, integers = 0;
        for (String source : sources) {
            GrammarDecl grammar = UBNFMapper.parse(source).grammars().get(0);
            for (RuleDecl rule : grammar.rules()) {
                for (String field : rule.annotations().stream().filter(MappingAnnotation.class::isInstance)
                        .flatMap(mapping -> ((MappingAnnotation) mapping).paramNames().stream()).toList()) {
                    String type = new ASTGenerator().inferType(grammar, rule, field);
                    boolean integer = List.of("int", "Optional<Integer>", "List<Integer>").contains(type);
                    assertEquals(rule.name() + "." + field + "\n" + source, integer, NumericFields.isInteger(grammar, rule, field));
                    fields++;
                    if (integer) integers++;
                }
            }
        }
        assertTrue(fields + " fields", integers >= 8 && fields > integers);
    }

    /** Numeric refinement does not need the AST fixed point, including past its depth limit. */
    @Test public void recursiveShapeLimitDoesNotAffectTheNativeCaptureRule() {
        GrammarDecl grammar = UBNFMapper.parse(NestingLimitGrammars.recursivePlusWithNumber(86)).grammars().get(0);
        RuleDecl root = grammar.rules().get(0);
        var error = assertThrows(IllegalArgumentException.class, () -> new ASTGenerator().inferType(grammar, root, "n"));
        assertEquals("E-RECURSIVE-SHAPE: expression depth exceeds 256", error.getMessage());
        assertTrue(NumericFields.isInteger(grammar, root, "n"));
        assertFalse(NumericFields.isInteger(grammar, root, "x"));
    }

    /** #758: the Java AST guard stays in place, but Rust integer refinement avoids that work. */
    @Test public void analysisWorkLimitDoesNotRejectRustGeneration() {
        GrammarDecl grammar = UBNFMapper.parse(NestingLimitGrammars.numericBesideAnalysisWork(560)).grammars().get(0);
        RuleDecl root = grammar.rules().get(0);
        var error = assertThrows(IllegalArgumentException.class, () -> new ASTGenerator().inferType(grammar, root, "n"));
        assertEquals("E-RECURSIVE-SHAPE: analysis work exceeds 1000000", error.getMessage());
        assertTrue(NumericFields.isInteger(grammar, root, "n"));
        assertFalse(NumericFields.isInteger(grammar, root, "x"));
        var generated = new org.unlaxer.dsl.codegen.rust.RustBackend().generate(grammar);
        assertEquals(5, generated.size());
        String ast = generated.stream().filter(file -> file.relativePath().equals("ast.rs"))
            .findFirst().orElseThrow().content();
        assertTrue(ast, ast.contains("r#n: i32"));
    }

    /** #758: {@code @typeof} captures use the same capture rule instead of the Java AST inference. */
    @Test public void typeofConstraintsDoNotRejectRustGenerationAtTheAnalysisWorkLimit() {
        GrammarDecl grammar = UBNFMapper.parse(NestingLimitGrammars.typeofBesideAnalysisWork(560)).grammars().get(0);
        RuleDecl root = grammar.rules().get(0);
        var error = assertThrows(IllegalArgumentException.class, () -> TypeofConstraints.valueType(grammar, root, "m"));
        assertEquals("E-RECURSIVE-SHAPE: analysis work exceeds 1000000", error.getMessage());
        assertTrue(NumericFields.isConstraintInteger(grammar, root, "m"));
        assertTrue(NumericFields.isConstraintInteger(grammar, root, "k"));
        assertFalse(NumericFields.isConstraintInteger(grammar, root, "x"));
        var generated = new org.unlaxer.dsl.codegen.rust.RustBackend().generate(grammar);
        assertEquals(5, generated.size());
        String ast = generated.stream().filter(file -> file.relativePath().equals("ast.rs"))
            .findFirst().orElseThrow().content();
        assertTrue(ast, ast.contains("r#m: i32"));
    }

    /** Below the limit, the capture rule for {@code @typeof} agrees with the Java type inference it replaces. */
    @Test public void typeofCaptureRuleAgreesWithJavaTypeInference() throws Exception {
        List<String> sources = new ArrayList<>();
        sources.add(NestingLimitGrammars.typeofBesideAnalysisWork(0));
        for (String corpus : List.of("../spec-corpus/typeof/generated-cases.json", "../spec-corpus/nested-typeof/generated-cases.json")) {
            for (var row : JsonParser.parseString(Files.readString(Path.of(corpus))).getAsJsonArray()) {
                sources.add(row.getAsJsonObject().get("grammar").getAsString());
            }
        }
        int constraints = 0, integers = 0;
        for (String source : sources) {
            GrammarDecl grammar = UBNFMapper.parse(source).grammars().get(0);
            for (RuleDecl rule : grammar.rules()) {
                if (NestedCaptures.enabled(rule)) continue;
                for (String capture : TypeofConstraints.captures(rule)) {
                    String type = TypeofConstraints.valueType(grammar, rule, capture);
                    boolean integer = List.of("int", "Optional<Integer>", "List<Integer>").contains(type);
                    assertEquals(rule.name() + "." + capture + "\n" + source, integer, NumericFields.isConstraintInteger(grammar, rule, capture));
                    constraints++;
                    if (integer) integers++;
                }
            }
        }
        assertTrue(constraints + " constraints / " + integers + " integers", integers >= 3 && constraints > integers);
    }
}
