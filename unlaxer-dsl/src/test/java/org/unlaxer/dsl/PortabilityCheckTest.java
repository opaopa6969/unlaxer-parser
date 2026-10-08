package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot.Span;
import org.unlaxer.dsl.codegen.rust.RustGrammarLowering;

public class PortabilityCheckTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String VALID = """
        grammar Portable {
          @root @mapping(Root, params=[value])
          Start ::= 'ok' @value ;
        }
        """;

    @Test
    public void portableGrammarProducesOneJsonDocumentWithoutWriting() throws Exception {
        Path directory = temporary.newFolder().toPath();
        Path grammar = directory.resolve("grammar.ubnf");
        Files.writeString(grammar, VALID);
        var run = CodegenTestHelper.runCodegen("check", "--target", "rust", "--grammar", grammar.toString());
        assertEquals(0, run.exitCode());
        assertEquals("", run.err());
        assertEquals("{\"schemaVersion\":1,\"target\":\"rust\",\"portable\":true,\"structure\":\"passed\",\"diagnostics\":[]}", run.out().trim());
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
        var explicit = CodegenTestHelper.runCodegen("check", "--target", "rust", "--grammar", grammar.toString(), "--format", "json");
        assertEquals(run.out(), explicit.out());
        assertEquals(0, explicit.exitCode());
    }

    @Test
    public void everyUnsupportedOccurrenceIsReportedInSourceOrder() {
        String source = """
            grammar Unsupported {
              token Bad = example.UnsafeParser
              token Pattern = REGEX('a')
              @root @mapping(Root, params=[value]) @custom
              Start ::= ('') | ERROR('bad') | remote.Item | @typeof(value) 'x' @value ;
              Other ::= remote.Item ;
            }
            """;
        var result = PortabilityCheck.check(source);
        assertFalse(result.portable());
        assertEquals("blocked", result.structure());
        assertEquals(7, result.diagnostics().size());
        assertEquals("P-EXTERNAL-TOKEN", result.diagnostics().get(0).code());
        assertEquals("example.UnsafeParser", result.diagnostics().get(0).subject());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("P-TOKEN-KIND") && d.subject().equals("REGEX")));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("P-ANNOTATION") && d.subject().equals("custom")));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("P-EMPTY-LITERAL")));
        assertEquals(2, result.diagnostics().stream().filter(d -> d.code().equals("P-QUALIFIED-REFERENCE")).count());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("P-TYPEOF") && d.subject().equals("value")));
        Span last = result.diagnostics().get(6).span();
        assertEquals(source.indexOf("remote.Item", source.indexOf("Other ::=")), last.start());
    }

    @Test
    public void syntaxAndStructuralFailuresUseStableDiagnostics() {
        var syntax = PortabilityCheck.check("grammar Broken {");
        assertEquals("unavailable", syntax.structure());
        assertEquals("P-SYNTAX", syntax.diagnostics().get(0).code());
        assertNull(syntax.diagnostics().get(0).span());

        String noRoot = "grammar NoRoot { Item ::= 'a' ; }";
        var structural = PortabilityCheck.check(noRoot);
        assertEquals("failed", structural.structure());
        assertEquals("P-STRUCTURE", structural.diagnostics().get(0).code());
        assertEquals(new Span(0, noRoot.length()), structural.diagnostics().get(0).span());

        String missingPrecedence = """
            grammar Assoc {
              @root @mapping(Root, params=[value])
              Start ::= Term @value ;
              @leftAssoc @mapping(Root, params=[value])
              Term ::= 'a' @value ;
            }
            """;
        var assoc = PortabilityCheck.check(missingPrecedence);
        assertEquals("failed", assoc.structure());
        assertEquals("P-STRUCTURE", assoc.diagnostics().get(0).code());
    }

    @Test
    public void invalidOptionsAndIoHaveNoJsonOutput() {
        var help = CodegenTestHelper.runCodegen("check", "--help");
        assertEquals(0, help.exitCode());
        assertTrue(help.out().contains("Usage: check"));
        assertEquals("", help.err());
        for (String[] args : new String[][] {
            {"check", "--target", "rust", "--grammar", ""},
            {"check", "--target", "rust", "--target", "rust", "--grammar", "x"},
            {"check", "--target", "rust", "--grammar", "x", "--output", "out"},
            {"check", "--target", "java", "--grammar", "x"},
            {"check", "--target", "rust", "--grammar", "x", "--format", "text"}
        }) {
            var run = CodegenTestHelper.runCodegen(args);
            assertEquals(2, run.exitCode());
            assertEquals("", run.out());
            assertFalse(run.err().isBlank());
        }
        var missing = CodegenTestHelper.runCodegen("check", "--target", "rust", "--grammar", "/missing/portability-check.ubnf");
        assertEquals(4, missing.exitCode());
        assertEquals("", missing.out());
        assertFalse(missing.err().isBlank());
    }

    @Test
    public void unsupportedParserClassIsNotInitialized() throws Exception {
        System.clearProperty("portability.probe.initialized");
        String source = """
            grammar Probe {
              token Unsafe = org.unlaxer.dsl.PortabilityProbeParser
              @root @mapping(Root, params=[value]) Start ::= Unsafe @value ;
            }
            """;
        Path grammar = temporary.newFile("portability-probe.ubnf").toPath();
        Files.writeString(grammar, source);
        var run = CodegenTestHelper.runCodegen("check", "--target", "rust", "--grammar", grammar.toString());
        assertEquals(3, run.exitCode());
        assertTrue(run.out().contains("P-EXTERNAL-TOKEN"));
        assertEquals("", run.err());
        assertNull(System.getProperty("portability.probe.initialized"));
    }

    @Test
    public void loweringBoundsAliasAnalysis() {
        RustGrammarLowering.lower(UBNFMapper.parse(aliasChain(64)).grammars().get(0));
        IllegalArgumentException deepAlias = assertThrows(IllegalArgumentException.class,
            () -> RustGrammarLowering.lower(UBNFMapper.parse(aliasChain(96)).grammars().get(0)));
        assertTrue(deepAlias.getMessage().contains("depth exceeds 256"));
    }

    private static String aliasChain(int count) {
        StringBuilder source = new StringBuilder("grammar LongChain {\n"
            + "  @root @mapping(Root, params=[value]) Start ::= R0 @value ;\n");
        for (int i = 0; i < count; i++) source.append("  R").append(i).append(" ::= R").append(i + 1).append(" ;\n");
        return source.append("  R").append(count).append(" ::= 'x' ;\n}\n").toString();
    }

}

final class PortabilityProbeParser {
    static { System.setProperty("portability.probe.initialized", "true"); }
}
