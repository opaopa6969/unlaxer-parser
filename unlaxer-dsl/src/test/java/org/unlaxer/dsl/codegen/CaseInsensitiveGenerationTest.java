package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.PortabilityCheck;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.codegen.rust.PlaygroundGenerator;

public class CaseInsensitiveGenerationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static String source(String word) {
        return "grammar Ci { token Keyword = CI('" + word + "') "
            + "@root @mapping(Root, params=[value]) Start ::= Keyword @value; }";
    }

    @Test public void generatedJavaUsesPinnedUnicodeParser() {
        var grammar = UBNFMapper.parse(source("İΣK𐐀")).grammars().get(0);
        String generated = new ParserGenerator().generate(grammar).source();
        assertTrue(generated, generated.contains("new org.unlaxer.parser.elementary.UnicodeIgnoreCaseWordParser("));
        assertFalse(generated.contains("new org.unlaxer.parser.elementary.IgnoreCaseWordParser("));
    }

    @Test public void rustLoweringAndPortabilitySupportExplicitCi() {
        for (String word : new String[]{"select", "İΣK", "𐐀😀"}) {
            String source = source(word);
            var report = PortabilityCheck.check(source);
            assertTrue(report.toString(), report.portable());
            var files = new RustBackend().generate(UBNFMapper.parse(source).grammars().get(0));
            assertTrue(files.stream().filter(f -> f.relativePath().endsWith("parser.rs"))
                .findFirst().orElseThrow().content().contains("Expr::CaseInsensitive("));
        }
    }

    @Test public void ciPredictorDoesNotUseCaseSensitiveLiteral() {
        String source = source("if").replace("@root", "@predictiveChoice @root")
            .replace("Keyword @value;", "Keyword @value | 'other' @value;");
        String generated = new RustBackend().generate(UBNFMapper.parse(source).grammars().get(0)).stream()
            .filter(f -> f.relativePath().endsWith("parser.rs")).findFirst().orElseThrow().content();
        assertTrue(generated, generated.contains("Predictor::Any"));
    }

    @Test public void emptyCiIsRejectedEvenWhenUnused() {
        for (String source : new String[]{source(""), source("").replace("Keyword @value", "'ok' @value")}) {
            var grammar = UBNFMapper.parse(source).grammars().get(0);
            assertTrue(GrammarValidator.validate(grammar).stream().anyMatch(i -> i.code().equals("E-CI-EMPTY")));
            assertThrows(IllegalArgumentException.class, () -> new ParserGenerator().generate(grammar));
            assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(grammar));
            assertFalse(PortabilityCheck.check(source).portable());
        }
    }

    @Test public void unicodeCiPlaygroundContainsItsPinnedCaseModule() throws Exception {
        Path grammar = temporary.newFile("grammar.ubnf").toPath();
        Files.writeString(grammar, source("İΣK𐐀"));
        var files = PlaygroundGenerator.generate(grammar);
        assertTrue(files.containsKey("runtime/src/unicode_case_data.rs"));
        assertTrue(files.get("src/generated/parser.rs").contains("Expr::CaseInsensitive("));
    }

    @Test public void nonScalarCiIsRejectedByBothJavaTargetsEvenWhenUnused() {
        for (char surrogate : new char[]{0xd800, 0xdc00}) {
            String source = source(String.valueOf(surrogate));
            for (String text : new String[]{source, source.replace("Keyword @value", "'ok' @value")}) {
                var grammar = UBNFMapper.parse(text).grammars().get(0);
                assertTrue(GrammarValidator.validateWithoutClassLoading(grammar).stream()
                    .anyMatch(issue -> issue.code().equals("E-CI-SCALAR")));
                assertThrows(IllegalArgumentException.class, () -> new ParserGenerator().generate(grammar));
                assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(grammar));
                assertFalse(PortabilityCheck.check(text).portable());
            }
        }
    }
}
