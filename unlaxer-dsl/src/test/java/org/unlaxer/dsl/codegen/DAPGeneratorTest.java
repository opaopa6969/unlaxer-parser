package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

public class DAPGeneratorTest {

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
        "  Expression ::= NUMBER | IDENTIFIER ;\n" +
        "}";

    private static CodeGenerator.GeneratedSource adapterResult;
    private static CodeGenerator.GeneratedSource launcherResult;

    @BeforeClass
    public static void setUp() {
        GrammarDecl grammar = UBNFMapper.parse(TINYCALC_GRAMMAR).grammars().get(0);
        adapterResult  = new DAPGenerator().generate(grammar);
        launcherResult = new DAPLauncherGenerator().generate(grammar);
    }

    // --- DAPGenerator ---

    @Test
    public void testAdapterPackageName() {
        assertEquals("org.unlaxer.tinycalc.generated", adapterResult.packageName());
    }

    @Test
    public void testAdapterClassName() {
        assertEquals("TinyCalcDebugAdapter", adapterResult.className());
    }

    @Test
    public void testAdapterImplementsIDebugProtocolServer() {
        assertTrue(adapterResult.source().contains("implements IDebugProtocolServer"));
    }

    @Test
    public void testAdapterHasConnectMethod() {
        assertTrue(adapterResult.source().contains("public void connect(IDebugProtocolClient client)"));
    }

    @Test
    public void testAdapterHasInitializeMethod() {
        assertTrue(adapterResult.source().contains("initialize(InitializeRequestArguments"));
    }

    @Test
    public void testAdapterHasLaunchMethod() {
        assertTrue(adapterResult.source().contains("launch(Map<String, Object>"));
    }

    @Test
    public void testAdapterHasConfigurationDone() {
        assertTrue(adapterResult.source().contains("configurationDone("));
    }

    @Test
    public void testAdapterHasContinue() {
        assertTrue(adapterResult.source().contains("continue_("));
    }

    @Test
    public void testAdapterHasThreads() {
        assertTrue(adapterResult.source().contains("threads()"));
    }

    @Test
    public void testAdapterHasStackTrace() {
        assertTrue(adapterResult.source().contains("stackTrace("));
    }

    @Test
    public void testAdapterHasDisconnect() {
        assertTrue(adapterResult.source().contains("disconnect("));
    }

    @Test
    public void testAdapterReferencesParsers() {
        assertTrue(adapterResult.source().contains("TinyCalcParsers.getRootParser()"));
    }

    @Test
    public void testAdapterImportsLsp4jDebug() {
        assertTrue(adapterResult.source().contains("import org.eclipse.lsp4j.debug"));
    }

    @Test
    public void testAdapterHasParseAndCollectSteps() {
        assertTrue(adapterResult.source().contains("parseAndCollectSteps()"));
    }

    @Test
    public void testAdapterHasNext() {
        assertTrue(adapterResult.source().contains("next(NextArguments"));
    }

    @Test
    public void testAdapterHasScopes() {
        assertTrue(adapterResult.source().contains("scopes(ScopesArguments"));
    }

    @Test
    public void testAdapterHasVariables() {
        assertTrue(adapterResult.source().contains("variables(VariablesArguments"));
    }

    @Test
    public void testAdapterHasStepPointsField() {
        assertTrue(adapterResult.source().contains("List<Token> stepPoints"));
    }

    @Test
    public void testAdapterHasStepIndexField() {
        assertTrue(adapterResult.source().contains("int stepIndex"));
    }

    @Test
    public void testAdapterHasSourceContentField() {
        assertTrue(adapterResult.source().contains("String sourceContent"));
    }

    @Test
    public void testAdapterScopesHasVariablesReference() {
        assertTrue(adapterResult.source().contains("setVariablesReference(1)"));
    }

    @Test
    public void testAdapterStackTraceUsesOffsetFromRoot() {
        assertTrue(adapterResult.source().contains("offsetFromRoot().value()"));
    }

    // --- breakpoint ---

    @Test
    public void testAdapterHasBreakpointLinesField() {
        assertTrue(adapterResult.source().contains("Set<Integer> breakpointLines"));
    }

    @Test
    public void testAdapterSetBreakpointsStoresLines() {
        assertTrue(adapterResult.source().contains("breakpointLines.add(line)"));
    }

    @Test
    public void testAdapterSetBreakpointsReturnsVerified() {
        assertTrue(adapterResult.source().contains("bp.setVerified(true)"));
    }

    @Test
    public void testAdapterHasFindBreakpointIndex() {
        assertTrue(adapterResult.source().contains("findBreakpointIndex("));
    }

    @Test
    public void testAdapterHasGetLineForToken() {
        assertTrue(adapterResult.source().contains("getLineForToken("));
    }

    @Test
    public void testAdapterContinueRunsToBreakpoint() {
        assertTrue(adapterResult.source().contains("stopped.setReason(\"breakpoint\")"));
    }

    @Test
    public void testAdapterConfigurationDoneChecksBreakpoints() {
        // configurationDone should branch on !breakpointLines.isEmpty()
        assertTrue(adapterResult.source().contains("breakpointLines.isEmpty()"));
    }

    // --- DAPLauncherGenerator ---

    @Test
    public void testLauncherPackageName() {
        assertEquals("org.unlaxer.tinycalc.generated", launcherResult.packageName());
    }

    @Test
    public void testLauncherClassName() {
        assertEquals("TinyCalcDapLauncher", launcherResult.className());
    }

    @Test
    public void testLauncherHasMainMethod() {
        assertTrue(launcherResult.source().contains("public static void main(String[] args)"));
    }

    @Test
    public void testLauncherUsesDSPLauncher() {
        assertTrue(launcherResult.source().contains("DSPLauncher.createServerLauncher"));
    }

    @Test
    public void testLauncherReferencesAdapter() {
        assertTrue(launcherResult.source().contains("TinyCalcDebugAdapter"));
    }
}
