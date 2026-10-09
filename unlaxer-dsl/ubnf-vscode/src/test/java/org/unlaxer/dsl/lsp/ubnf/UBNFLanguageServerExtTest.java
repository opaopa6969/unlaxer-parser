package org.unlaxer.dsl.lsp.ubnf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.ExecutionException;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.junit.Before;
import org.junit.Test;

/**
 * UBNFLanguageServerExt のリッチ機能のテスト。
 * JSON-RPC を介さず service メソッドを直接呼ぶ (既存 smoke テストと同方式)。
 */
public class UBNFLanguageServerExtTest {

    @Test public void declarativeTokensAreIndexedAndVocabularyIncludesV2() {
        var server = new UBNFLanguageServerExt();
        var index = server.ensureIndex("file:///new.ubnf", """
            grammar New {
              @ubnf: v2
              token NUMBER ::= CHAR_RANGE('0', '9')+;
              @root
              Start ::= NUMBER;
            }
            """);
        assertTrue(index.decls.containsKey("NUMBER"));
        assertEquals("token", index.decls.get("NUMBER").kind());
        assertEquals(2, index.decls.get("NUMBER").line());
        assertTrue(UBNFLanguageServerExt.CORE_KEYWORDS.containsAll(List.of("CAPTURE", "SAME_AS", "ADAPTER", "BOF")));
        assertTrue(UBNFLanguageServerExt.BLOCK_SNIPPETS.get(0).body().contains("@ubnf: v2"));
        assertFalse(UBNFLanguageServerExt.BLOCK_SNIPPETS.get(0).body().contains("NumberParser"));
    }

    @Test public void pinnedWhitespaceHasCompletionHoverAndReadOnlyDefinition() throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("ubnf-origin-lsp-");
        String module = "grammar Layout {\n @ubnf: v2\n // 😀\n token GAP ::= ' ' | '#';\n}\n";
        var artifact = new com.google.gson.Gson().toJson(java.util.Map.of("schemaVersion", 1, "id", "team/layout", "version", "1.2.3", "entry", "layout.ubnf", "files", java.util.Map.of("layout.ubnf", module), "dependencies", java.util.Map.of()));
        java.nio.file.Files.writeString(directory.resolve("artifact.json"), artifact);
        java.nio.file.Files.writeString(directory.resolve("ubnf.json"), "{\"schemaVersion\":1,\"dependencies\":{\"team/layout\":{\"version\":\"1.2.3\",\"source\":\"local:artifact.json\"}}}");
        org.unlaxer.dsl.bootstrap.UBNFPackageResolver.resolve(directory.resolve("ubnf.json"));
        String content = "grammar G {\n @import layout from 'pkg:team/layout'\n @ubnf: v2\n @whitespace: layout.GAP\n @root\n Start ::= 'a';\n}\n";
        String uri = directory.resolve("root.ubnf").toUri().toString();
        service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "ubnf", 1, content)));
        Position position = new Position(3, 22);
        var hover = service.hover(new HoverParams(new TextDocumentIdentifier(uri), position)).get();
        assertNotNull(hover); String documentation = hover.getContents().getRight().getValue();
        assertTrue(documentation, documentation.contains("team/layout@1.2.3"));
        assertTrue(documentation, documentation.contains("sha256"));
        var target = service.definition(new DefinitionParams(new TextDocumentIdentifier(uri), position)).get().getLeft().get(0);
        assertTrue(target.getUri(), target.getUri().startsWith("ubnf-package:/"));
        assertEquals(new Position(3, 7), target.getRange().getStart());
        assertEquals(new Position(3, 10), target.getRange().getEnd());
        var extended = (UBNFLanguageServerExt.ExtTextDocumentService)service;
        assertEquals(module, extended.packageSource(new UBNFLanguageServerExt.PackageSourceRequest(target.getUri())).get());
        assertTrue(org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints.getSupportedMethods(server.getClass()).containsKey("ubnf/packageSource"));
        var endpoint = org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints.toEndpoint(server);
        assertEquals(module, endpoint.request("ubnf/packageSource", new UBNFLanguageServerExt.PackageSourceRequest(target.getUri())).get());
        assertEquals(null, extended.packageSource(new UBNFLanguageServerExt.PackageSourceRequest("file:///etc/passwd")).get());
        assertEquals(null, extended.packageSource(new UBNFLanguageServerExt.PackageSourceRequest("ubnf-package:/unknown/layout.ubnf")).get());
        var completion = service.completion(new CompletionParams(new TextDocumentIdentifier(uri), new Position(3, 21))).get().getLeft();
        assertTrue(completion.stream().anyMatch(item -> item.getLabel().equals("layout.GAP") && item.getDetail().contains("team/layout@1.2.3")));
        org.junit.Assert.assertThrows(ExecutionException.class, () -> service.rename(new RenameParams(new TextDocumentIdentifier(uri), position, "RENAMED")).get());
    }

    private static final String URI = "file:///sample.ubnf";

    private static final String VALID_UBNF = """
        grammar Sample {
          @package: org.example
          @whitespace: javaStyle

          token IDENT = org.unlaxer.parser.clang.IdentifierParser
          token NUMBER = org.unlaxer.parser.elementary.NumberParser

          @root
          @mapping(Program, params=[value])
          Program ::= Expr @value ;

          Expr ::= IDENT
            | NUMBER ;
        }
        """;

    private static final String UNRESOLVED_TOKEN_UBNF = """
        grammar G {
          @package: org.example
          token NUMBER = NumberParser
          @root
          Start ::= NUMBER ;
        }
        """;

    private static final String LEFT_RECURSIVE_UBNF = """
        grammar G {
          @package: org.example
          @root
          Expr ::= Expr | 'n' ;
        }
        """;

    private UBNFLanguageServerExt server;
    private TextDocumentService service;

    @Before
    public void setUp() {
        server = new UBNFLanguageServerExt();
        service = server.getTextDocumentService();
    }

    private void open(String content) {
        TextDocumentItem item = new TextDocumentItem(URI, "ubnf", 1, content);
        service.didOpen(new DidOpenTextDocumentParams(item));
    }

    // ---------------------------------------------------------------- capabilities

    @Test
    public void initializeAdvertisesRichCapabilities() throws ExecutionException, InterruptedException {
        InitializeResult result = server.initialize(new InitializeParams()).get();
        ServerCapabilities capabilities = result.getCapabilities();
        assertTrue(capabilities.getDefinitionProvider().getLeft());
        assertTrue(capabilities.getReferencesProvider().getLeft());
        assertTrue(capabilities.getRenameProvider().getLeft());
        assertTrue(capabilities.getDocumentSymbolProvider().getLeft());
        assertTrue(capabilities.getFoldingRangeProvider().getLeft());
        assertTrue(capabilities.getLinkedEditingRangeProvider().getLeft());
        assertNotNull(capabilities.getCodeActionProvider());
        assertNotNull(capabilities.getSignatureHelpProvider());
        assertEquals(UBNFLanguageServerExt.TOKEN_TYPES,
            capabilities.getSemanticTokensProvider().getLegend().getTokenTypes());
    }

    // ---------------------------------------------------------------- diagnostics

    @Test
    public void validatorReportsUnresolvedTokenWithFqnHint() {
        List<Diagnostic> diagnostics = server.validationDiagnostics(UNRESOLVED_TOKEN_UBNF);
        Diagnostic unresolved = diagnostics.stream()
            .filter(d -> "W-TOKEN-UNRESOLVED".equals(d.getCode().getLeft()))
            .findFirst().orElseThrow(() -> new AssertionError("expected W-TOKEN-UNRESOLVED"));
        assertTrue("message should carry FQN suggestion: " + unresolved.getMessage(),
            unresolved.getMessage().contains("org.unlaxer.parser.elementary.NumberParser"));
        // range は 'NumberParser' (token 宣言の右辺) を指す
        assertEquals(2, unresolved.getRange().getStart().getLine());
    }

    @Test
    public void validatorReportsLeftRecursionAsWarning() {
        List<Diagnostic> diagnostics = server.validationDiagnostics(LEFT_RECURSIVE_UBNF);
        assertTrue(diagnostics.stream()
            .anyMatch(d -> "W-LEFT-RECURSION".equals(d.getCode().getLeft())));
    }

    @Test
    public void validGrammarHasNoErrors() {
        List<Diagnostic> diagnostics = server.validationDiagnostics(VALID_UBNF);
        assertTrue("expected no validator issues, got: " + diagnostics,
            diagnostics.isEmpty());
    }

    // ---------------------------------------------------------------- code action

    @Test
    public void codeActionOffersFqnQuickFix() throws ExecutionException, InterruptedException {
        open(UNRESOLVED_TOKEN_UBNF);
        List<Diagnostic> diagnostics = server.validationDiagnostics(UNRESOLVED_TOKEN_UBNF);
        Diagnostic unresolved = diagnostics.stream()
            .filter(d -> "W-TOKEN-UNRESOLVED".equals(d.getCode().getLeft()))
            .findFirst().orElseThrow();
        CodeActionParams params = new CodeActionParams(new TextDocumentIdentifier(URI),
            unresolved.getRange(), new CodeActionContext(List.of(unresolved)));
        List<Either<Command, CodeAction>> actions = service.codeAction(params).get();
        assertFalse("quick fix expected", actions.isEmpty());
        CodeAction action = actions.get(0).getRight();
        assertTrue(action.getTitle().contains("org.unlaxer.parser.elementary.NumberParser"));
        List<TextEdit> edits = action.getEdit().getChanges().get(URI);
        assertEquals("org.unlaxer.parser.elementary.NumberParser", edits.get(0).getNewText());
    }

    // ---------------------------------------------------------------- completion

    @Test
    public void completionAfterAtOffersAnnotationSnippets() throws ExecutionException, InterruptedException {
        open("grammar G {\n  @\n}\n");
        CompletionParams params = new CompletionParams(
            new TextDocumentIdentifier(URI), new Position(1, 3));
        Either<List<CompletionItem>, CompletionList> result = service.completion(params).get();
        List<CompletionItem> items = result.getLeft();
        assertTrue(items.stream().anyMatch(i -> i.getLabel().equals("@mapping")));
        CompletionItem mapping = items.stream()
            .filter(i -> i.getLabel().equals("@mapping")).findFirst().orElseThrow();
        assertTrue(mapping.getInsertText().contains("params=["));
    }

    @Test
    public void completionInTokenDeclOffersParserClasses() throws ExecutionException, InterruptedException {
        open("grammar G {\n  token NUMBER = \n}\n");
        CompletionParams params = new CompletionParams(
            new TextDocumentIdentifier(URI), new Position(1, 17));
        List<CompletionItem> items = service.completion(params).get().getLeft();
        assertTrue(items.stream()
            .anyMatch(i -> i.getLabel().equals("org.unlaxer.parser.elementary.NumberParser")));
    }

    @Test
    public void declarativeCompletionAndHoverShareBeginnerHelp() throws Exception {
        open("grammar G {\n  @ubnf: v2\n  token NUMBER ::= CHAR_RANGE('0', '9')+;\n  @root\n  Start ::= NUMBER;\n}\n");
        var items = service.completion(new CompletionParams(new TextDocumentIdentifier(URI), new Position(2, 19))).get().getLeft();
        var range = items.stream().filter(item -> item.getLabel().equals("CHAR_RANGE")).findFirst().orElseThrow();
        assertTrue(range.getDocumentation().getRight().getValue().contains("1 文字"));
        assertTrue(items.stream().anyMatch(item -> item.getLabel().equals("CAPTURE")));
        var hover = service.hover(new HoverParams(new TextDocumentIdentifier(URI), new Position(2, 24))).get();
        assertTrue(hover.getContents().getRight().getValue().contains("1 文字"));
        var locations = service.definition(new DefinitionParams(new TextDocumentIdentifier(URI), new Position(4, 14))).get().getLeft();
        assertEquals(1, locations.size());
        assertEquals(2, locations.get(0).getRange().getStart().getLine());
    }

    @Test
    public void completionOffersDeclaredRuleNames() throws ExecutionException, InterruptedException {
        open(VALID_UBNF);
        CompletionParams params = new CompletionParams(
            new TextDocumentIdentifier(URI), new Position(11, 2));
        List<CompletionItem> items = service.completion(params).get().getLeft();
        assertTrue(items.stream().anyMatch(i -> i.getLabel().equals("Expr")));
        assertTrue(items.stream().anyMatch(i -> i.getLabel().equals("NUMBER")));
        assertTrue("block snippets expected",
            items.stream().anyMatch(i -> i.getLabel().equals("binary-rule")));
    }

    // ---------------------------------------------------------------- hover

    @Test
    public void hoverOnAnnotationShowsDocumentation() throws ExecutionException, InterruptedException {
        open(VALID_UBNF);
        // line 8: "  @mapping(Program, params=[value])"
        Hover hover = service.hover(new HoverParams(
            new TextDocumentIdentifier(URI), new Position(8, 5))).get();
        assertNotNull(hover);
        MarkupContent content = hover.getContents().getRight();
        assertTrue(content.getValue().contains("@mapping"));
        assertTrue(content.getValue().contains("record"));
    }

    @Test
    public void hoverOnRuleNameShowsDeclaration() throws ExecutionException, InterruptedException {
        open(VALID_UBNF);
        // line 9: "  Program ::= Expr @value ;" — hover on "Expr"
        Hover hover = service.hover(new HoverParams(
            new TextDocumentIdentifier(URI), new Position(9, 15))).get();
        assertNotNull(hover);
        String value = hover.getContents().getRight().getValue();
        assertTrue("hover should describe the rule: " + value, value.contains("rule Expr"));
        assertTrue("hover should count references: " + value, value.contains("reference"));
    }

    // ---------------------------------------------------------------- navigation

    @Test
    public void definitionJumpsToRuleDeclaration() throws ExecutionException, InterruptedException {
        open(VALID_UBNF);
        DefinitionParams params = new DefinitionParams(
            new TextDocumentIdentifier(URI), new Position(9, 15)); // "Expr" 参照
        List<? extends Location> locations = service.definition(params).get().getLeft();
        assertEquals(1, locations.size());
        assertEquals(11, locations.get(0).getRange().getStart().getLine()); // Expr ::= の行
    }

    @Test
    public void referencesFindAllOccurrences() throws ExecutionException, InterruptedException {
        open(VALID_UBNF);
        ReferenceParams params = new ReferenceParams(new TextDocumentIdentifier(URI),
            new Position(11, 3), new ReferenceContext(true));
        List<? extends Location> locations = service.references(params).get();
        assertEquals(2, locations.size()); // 宣言 + Program 内の参照
    }

    @Test
    public void renameRewritesAllOccurrences() throws ExecutionException, InterruptedException {
        open(VALID_UBNF);
        RenameParams params = new RenameParams(new TextDocumentIdentifier(URI),
            new Position(11, 3), "Expression");
        WorkspaceEdit edit = service.rename(params).get();
        List<TextEdit> edits = edit.getChanges().get(URI);
        assertEquals(2, edits.size());
        assertTrue(edits.stream().allMatch(e -> e.getNewText().equals("Expression")));
    }

    // ---------------------------------------------------------------- structure

    @Test
    public void documentSymbolBuildsGrammarOutline() throws ExecutionException, InterruptedException {
        open(VALID_UBNF);
        List<Either<SymbolInformation, DocumentSymbol>> symbols = service.documentSymbol(
            new DocumentSymbolParams(new TextDocumentIdentifier(URI))).get();
        assertEquals(1, symbols.size());
        DocumentSymbol grammar = symbols.get(0).getRight();
        assertEquals("Sample", grammar.getName());
        List<String> children = grammar.getChildren().stream().map(DocumentSymbol::getName).toList();
        assertTrue(children.containsAll(List.of("IDENT", "NUMBER", "Program", "Expr")));
    }

    @Test
    public void foldingRangesCoverGrammarBlockAndMultilineRule() throws ExecutionException, InterruptedException {
        open(VALID_UBNF);
        List<FoldingRange> ranges = service.foldingRange(
            new FoldingRangeRequestParams(new TextDocumentIdentifier(URI))).get();
        assertTrue("grammar block fold expected",
            ranges.stream().anyMatch(r -> r.getStartLine() == 0));
        assertTrue("multiline rule fold expected",
            ranges.stream().anyMatch(r -> r.getStartLine() == 11 && r.getEndLine() == 12));
    }

    // ---------------------------------------------------------------- signature help

    @Test
    public void signatureHelpInsideMappingAnnotation() throws ExecutionException, InterruptedException {
        open(VALID_UBNF);
        // line 8 の "@mapping(" 内
        SignatureHelp help = service.signatureHelp(new SignatureHelpParams(
            new TextDocumentIdentifier(URI), new Position(8, 11))).get();
        assertEquals(1, help.getSignatures().size());
        assertTrue(help.getSignatures().get(0).getLabel().startsWith("@mapping("));
    }

    // ---------------------------------------------------------------- semantic tokens

    @Test
    public void semanticTokensProduceData() throws ExecutionException, InterruptedException {
        open(VALID_UBNF);
        SemanticTokens tokens = service.semanticTokensFull(
            new SemanticTokensParams(new TextDocumentIdentifier(URI))).get();
        assertFalse(tokens.getData().isEmpty());
        assertEquals(0, tokens.getData().size() % 5);
    }

    @Test
    public void semanticTokensWorkOnUnparsableContent() {
        List<Integer> data = UBNFLanguageServerExt.tokenize("token X = // broken\n'unclosed");
        assertEquals(0, data.size() % 5);
        assertFalse(data.isEmpty());
    }
}
