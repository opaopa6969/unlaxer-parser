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

/** Integer refinement of the Java host's Rust backend (#740). */
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
                    boolean integer = NumericFields.isInteger(grammar, rule, field);
                    assertEquals(rule.name() + "." + field + "\n" + source, integer, NumericFields.numericCaptures(grammar, rule, field));
                    fields++;
                    if (integer) integers++;
                }
            }
        }
        assertTrue(fields + " fields", integers >= 8 && fields > integers);
    }

    /** Past the AST fixed point's depth, the Java host uses the native capture rule instead of rejecting. */
    @Test public void recursiveShapeLimitFallsBackToTheNativeCaptureRule() {
        GrammarDecl grammar = UBNFMapper.parse(NestingLimitGrammars.recursivePlusWithNumber(86)).grammars().get(0);
        RuleDecl root = grammar.rules().get(0);
        var error = assertThrows(IllegalArgumentException.class, () -> new ASTGenerator().inferType(grammar, root, "n"));
        assertEquals("E-RECURSIVE-SHAPE: expression depth exceeds 256", error.getMessage());
        assertTrue(NumericFields.isInteger(grammar, root, "n"));
        assertFalse(NumericFields.isInteger(grammar, root, "x"));
    }

    /** Only the depth limit falls back: exceeding the analysis work still fails, as before #740. */
    @Test public void analysisWorkLimitStillFails() {
        GrammarDecl grammar = UBNFMapper.parse(NestingLimitGrammars.numericBesideAnalysisWork(560)).grammars().get(0);
        RuleDecl root = grammar.rules().get(0);
        var error = assertThrows(IllegalArgumentException.class, () -> NumericFields.isInteger(grammar, root, "n"));
        assertEquals("E-RECURSIVE-SHAPE: analysis work exceeds 1000000", error.getMessage());
        var generation = assertThrows(IllegalArgumentException.class,
            () -> new org.unlaxer.dsl.codegen.rust.RustBackend().generate(grammar));
        assertEquals("E-RECURSIVE-SHAPE: analysis work exceeds 1000000", generation.getMessage());
    }
}
