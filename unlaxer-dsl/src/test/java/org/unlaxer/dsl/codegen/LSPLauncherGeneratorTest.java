package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

public class LSPLauncherGeneratorTest {

    private static final String TINYCALC_GRAMMAR =
        "grammar TinyCalc {\n" +
        "  @package: org.unlaxer.tinycalc.generated\n" +
        "  @whitespace: javaStyle\n" +
        "\n" +
        "  token NUMBER     = NumberParser\n" +
        "  token IDENTIFIER = IdentifierParser\n" +
        "\n" +
        "  @root\n" +
        "  TinyCalc ::= Expression ;\n" +
        "\n" +
        "  Expression ::= NUMBER ;\n" +
        "}";

    private static CodeGenerator.GeneratedSource result;

    @BeforeClass
    public static void setUp() {
        GrammarDecl grammar = UBNFMapper.parse(TINYCALC_GRAMMAR).grammars().get(0);
        result = new LSPLauncherGenerator().generate(grammar);
    }

    @Test
    public void testPackageName() {
        assertEquals("org.unlaxer.tinycalc.generated", result.packageName());
    }

    @Test
    public void testClassName() {
        assertEquals("TinyCalcLspLauncher", result.className());
    }

    @Test
    public void testContainsMainMethod() {
        assertTrue(result.source().contains("main("));
    }

    @Test
    public void testContainsLSPLauncherCreateServerLauncher() {
        assertTrue(result.source().contains("LSPLauncher.createServerLauncher("));
    }

    @Test
    public void testContainsTinyCalcLanguageServer() {
        assertTrue(result.source().contains("TinyCalcLanguageServer"));
    }
}
