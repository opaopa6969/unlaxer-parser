package org.unlaxer.dsl.bootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.unlaxer.dsl.PortabilityCheck;
import org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl;
import org.unlaxer.dsl.codegen.ParserGenerator;
import org.unlaxer.dsl.codegen.GrammarValidator;
import org.unlaxer.dsl.codegen.rust.RustBackend;

public class TokenAdapterRegistryTest {
    private static final String CUSTOM_SETTING = "@tokenAdapter: { id: 'example.word' version: '1' "
        + "java: 'example.WordParser' rust: 'crate::word_token' }";

    @Test
    public void adapterSyntaxAndSourceSpanPreserveRawVersion() {
        String source = grammar("token WORD = ADAPTER('tinyexpression.string', version=01)");
        var snapshot = UBNFMapper.parseWithSource(source);
        var adapter = (TokenDecl.Adapter) snapshot.ast().grammars().get(0).tokens().get(0);
        assertEquals("WORD", adapter.name());
        assertEquals("tinyexpression.string", adapter.id());
        assertEquals("01", adapter.version());
        assertEquals("token WORD = ADAPTER('tinyexpression.string', version=01)",
            snapshot.sourceOf(adapter).orElseThrow());
        assertEquals(1, TokenAdapterRegistry.build(snapshot.ast().grammars().get(0), snapshot)
            .registry().find(adapter.id(), 1).orElseThrow().version());
    }

    @Test
    public void customRegistrationResolvesBothTargetsWithoutLoadingClasses() {
        String source = grammar(CUSTOM_SETTING + "\n token WORD = ADAPTER('example.word', version=1)");
        var snapshot = UBNFMapper.parseWithSource(source);
        var grammar = snapshot.ast().grammars().get(0);
        var built = TokenAdapterRegistry.build(grammar, snapshot);
        assertTrue(built.diagnostics().toString(), built.diagnostics().isEmpty());
        var descriptor = built.registry().find("example.word", 1).orElseThrow();
        assertEquals("example.WordParser", descriptor.javaClass());
        assertEquals("crate::word_token", descriptor.rustPath());
        assertFalse(descriptor.builtin());
        assertTrue(new ParserGenerator().generate(grammar).source()
            .contains("extends example.WordParser"));
        String rust = new RustBackend().generate(grammar).stream()
            .filter(file -> file.relativePath().equals("parser.rs")).findFirst().orElseThrow().content();
        assertTrue(rust.contains("Expr::Custom(crate::word_token)"));
        assertTrue(PortabilityCheck.check(source).portable());
    }

    @Test
    public void duplicateUnknownVersionAndMalformedRegistrationHaveTokenOrSettingSpans() {
        String source = grammar(CUSTOM_SETTING + "\n" + CUSTOM_SETTING + "\n"
            + "token A = ADAPTER('missing.word', version=1)\n"
            + "token B = ADAPTER('tinyexpression.string', version=2)\n"
            + "token C = ADAPTER('Tiny.Bad', version=0)");
        var snapshot = UBNFMapper.parseWithSource(source);
        List<TokenAdapterRegistry.Diagnostic> diagnostics = TokenAdapterRegistry.build(
            snapshot.ast().grammars().get(0), snapshot).diagnostics();
        assertEquals(List.of("P-ADAPTER-DUPLICATE", "P-ADAPTER-UNKNOWN", "P-ADAPTER-VERSION",
            "P-ADAPTER-DEFINITION"), diagnostics.stream().map(TokenAdapterRegistry.Diagnostic::code).toList());
        assertEquals("example.word", diagnostics.get(0).subject());
        assertEquals("missing.word", diagnostics.get(1).subject());
        assertEquals("tinyexpression.string", diagnostics.get(2).subject());
        for (var diagnostic : diagnostics) {
            assertTrue(snapshot.slice(diagnostic.span()).contains(diagnostic.subject())
                || snapshot.slice(diagnostic.span()).contains("Tiny.Bad"));
        }
        var report = PortabilityCheck.check(source);
        assertEquals("blocked", report.structure());
        assertEquals(diagnostics.size(), report.diagnostics().size());
    }

    @Test
    public void registrationSchemaAndCodePathsAreValidatedAsData() {
        for (String setting : List.of(
            "@tokenAdapter: { id: 'example.word' version: '1' java: 'example.class' rust: 'crate::word_token' }",
            "@tokenAdapter: { id: 'example.word' version: '1' java: 'example.WordParser' rust: 'crate::fn' }",
            "@tokenAdapter: { id: 'example.word' version: '1' java: 'example.WordParser' rust: 'crate::_' }",
            "@tokenAdapter: { id: 'example.word' version: '1' java: 'example.WordParser' rust: '_::word_token' }",
            "@tokenAdapter: { id: 'example.word' version: '1' java: 'example.WordParser' rust: 'crate::word_token' extra: 'x' }",
            "@tokenAdapter: { id: 'example.word' version: '2147483648' java: 'example.WordParser' rust: 'crate::word_token' }"
        )) {
            String source = grammar(setting + "\n token WORD = ADAPTER('tinyexpression.string', version=1)");
            var snapshot = UBNFMapper.parseWithSource(source);
            var errors = TokenAdapterRegistry.build(snapshot.ast().grammars().get(0), snapshot).diagnostics();
            assertEquals(setting, 1, errors.size());
            assertEquals("P-ADAPTER-DEFINITION", errors.get(0).code());
            assertEquals(setting, snapshot.slice(errors.get(0).span()));
        }
    }

    @Test
    public void javaAdaptersKeepExternalParserMemoizationConservative() {
        String token = "token WORD = ADAPTER('tinyexpression.string', version=1)";
        var plain = UBNFMapper.parse(grammar(token)).grammars().get(0);
        String unmarked = new ParserGenerator().generate(plain).source().lines()
            .filter(line -> line.contains("class StartParser ")).findFirst().orElseThrow();
        assertFalse(unmarked, unmarked.contains("Memoizable"));
        var optedIn = UBNFMapper.parse(grammar("@memoSafeToken: WORD\n" + token)).grammars().get(0);
        String marked = new ParserGenerator().generate(optedIn).source().lines()
            .filter(line -> line.contains("class StartParser ")).findFirst().orElseThrow();
        assertTrue(marked, marked.contains("SafeFailureMemoizable"));
        var custom = UBNFMapper.parse(grammar(CUSTOM_SETTING + "\n@memoSafeToken: WORD\n"
            + "token WORD = ADAPTER('example.word', version=1)")).grammars().get(0);
        assertTrue(GrammarValidator.validate(custom).stream()
            .anyMatch(issue -> issue.toString().contains("E-MEMO-SAFE-TOKEN-KIND")));
        assertFalse(PortabilityCheck.check(grammar(CUSTOM_SETTING + "\n"
            + "token WORD = ADAPTER('example.word', version=1)")
            .replace("WORD @value", "WORD* @value")).portable());
    }

    @Test
    public void adapterWrapperNameCollisionsAreRejectedInEitherDeclarationOrder() {
        for (String declarations : List.of(
            "token WORD = ADAPTER('tinyexpression.string', version=1)\n"
                + "token Word = ADAPTER('tinyexpression.string', version=1)",
            "token ID = IdentifierParser\n"
                + "token IDENTIFIER = ADAPTER('tinyexpression.string', version=1)",
            "token IDENTIFIER = ADAPTER('tinyexpression.string', version=1)\n"
                + "token ID = IdentifierParser"
        )) {
            var grammar = UBNFMapper.parse(grammar(declarations)).grammars().get(0);
            List<GrammarValidator.ValidationIssue> collisions = GrammarValidator.validateWithoutClassLoading(grammar)
                .stream().filter(issue -> "E-TOKEN-ADAPTER-NAME-COLLISION".equals(issue.code())).toList();
            assertEquals(declarations, 1, collisions.size());
            assertTrue(declarations, collisions.get(0).message().contains("Parser"));
        }

        var legacy = UBNFMapper.parse(grammar("token ID = IdentifierParser\n"
            + "token NAME = IdentifierParser")).grammars().get(0);
        assertFalse(GrammarValidator.validateWithoutClassLoading(legacy).stream()
            .anyMatch(issue -> "E-TOKEN-ADAPTER-NAME-COLLISION".equals(issue.code())));
    }

    private static String grammar(String declarations) {
        return "grammar AdapterGrammar {\n " + declarations + "\n"
            + "@root @mapping(Root, params=[value]) Start ::= "
            + (declarations.contains("token WORD") ? "WORD" : "'x'") + " @value ;\n}\n";
    }
}
