package org.unlaxer.dsl.bootstrap.generated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.ExecutionException;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.Before;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.generated.UBNFLanguageServer.DocumentState;
import org.unlaxer.dsl.bootstrap.generated.UBNFLanguageServer.ParseResult;

/**
 * Smoke tests for the generated {@link UBNFLanguageServer}.
 *
 * These run as part of the reactor build and act as the regression gate
 * for Issue #4 acceptance criteria:
 *  - real-time syntax error reporting
 *  - keyword completion
 *  - parse status hover
 *
 * The tests bypass the JSON-RPC stdio transport and call the LSP service
 * methods directly, so they can be CI-friendly and fast.
 */
public class UBNFLanguageServerTest {

    private static final String VALID_UBNF = """
        grammar Sample {
          @package: org.example
          @whitespace: javaStyle

          token IDENT = org.unlaxer.parser.clang.IdentifierParser

          @root
          Program ::= IDENT ;
        }
        """;

    private static final String INVALID_UBNF = "this is not valid ubnf!!!";

    private UBNFLanguageServer server;

    @Before
    public void setUp() {
        // The class is `abstract` only so user code can override the protected
        // hooks (additionalCompletionItems, etc.). Default implementations of
        // every required method exist, so an empty subclass is enough.
        server = new UBNFLanguageServer() {};
    }

    @Test
    public void initializeReportsExpectedCapabilities() throws Exception {
        InitializeResult result = server.initialize(new InitializeParams()).get();
        ServerCapabilities caps = result.getCapabilities();

        assertEquals(TextDocumentSyncKind.Full, caps.getTextDocumentSync().getLeft());
        assertNotNull("completion provider must be advertised", caps.getCompletionProvider());
        assertEquals(Boolean.TRUE, caps.getHoverProvider().getLeft());
        assertNotNull("semantic tokens provider must be advertised",
            caps.getSemanticTokensProvider());
    }

    @Test
    public void didOpenWithValidUbnfStoresSuccessfulParse() {
        openDocument("file:///test/sample.ubnf", VALID_UBNF);

        DocumentState state = server.documents.get("file:///test/sample.ubnf");
        assertNotNull(state);
        assertTrue("valid UBNF must parse successfully", state.parseResult().succeeded());
        assertEquals("entire document must be consumed",
            VALID_UBNF.length(), state.parseResult().consumedLength());
    }

    @Test
    public void didOpenWithInvalidUbnfRecordsParseError() {
        openDocument("file:///test/bad.ubnf", INVALID_UBNF);

        DocumentState state = server.documents.get("file:///test/bad.ubnf");
        assertNotNull(state);
        ParseResult result = state.parseResult();
        assertTrue("consumedLength must be < totalLength on parse error",
            result.consumedLength() < result.totalLength());
    }

    @Test
    public void completionReturnsCoreUbnfKeywords() throws Exception {
        openDocument("file:///test/c.ubnf", VALID_UBNF);

        CompletionParams params = new CompletionParams(
            new TextDocumentIdentifier("file:///test/c.ubnf"),
            new Position(0, 0));

        Either<List<CompletionItem>, CompletionList> response =
            server.getTextDocumentService().completion(params).get();

        List<CompletionItem> items = response.getLeft();
        assertNotNull(items);
        assertContainsLabel(items, "grammar");
        assertContainsLabel(items, "token");
        assertContainsLabel(items, "@root");
        assertContainsLabel(items, "@mapping");
        assertContainsLabel(items, "::=");
    }

    @Test
    public void hoverOnValidDocumentReportsValidUbnf() throws Exception {
        openDocument("file:///test/h.ubnf", VALID_UBNF);
        Hover hover = server.getTextDocumentService()
            .hover(hoverParams("file:///test/h.ubnf"))
            .get();
        assertNotNull(hover);
        MarkupContent content = (MarkupContent) hover.getContents().getRight();
        assertEquals("Valid UBNF", content.getValue());
    }

    @Test
    public void hoverOnInvalidDocumentReportsParseErrorOffset() throws Exception {
        openDocument("file:///test/h-bad.ubnf", INVALID_UBNF);
        Hover hover = server.getTextDocumentService()
            .hover(hoverParams("file:///test/h-bad.ubnf"))
            .get();
        assertNotNull(hover);
        MarkupContent content = (MarkupContent) hover.getContents().getRight();
        assertTrue("hover should describe the expected syntax: " + content.getValue(),
            content.getValue().startsWith("Expected "));
    }

    @Test
    public void didChangeUpdatesDocumentState() throws ExecutionException, InterruptedException {
        openDocument("file:///test/edit.ubnf", VALID_UBNF);
        assertTrue(server.documents.get("file:///test/edit.ubnf").parseResult().succeeded());

        DidChangeTextDocumentParams change = new DidChangeTextDocumentParams();
        change.setTextDocument(new VersionedTextDocumentIdentifier("file:///test/edit.ubnf", 2));
        change.setContentChanges(List.of(new TextDocumentContentChangeEvent(INVALID_UBNF)));
        server.getTextDocumentService().didChange(change);

        DocumentState updated = server.documents.get("file:///test/edit.ubnf");
        assertFalse("after edit to invalid content, parse must fail",
            updated.parseResult().consumedLength() == updated.parseResult().totalLength());
    }

    @Test
    public void didCloseRemovesDocumentState() {
        openDocument("file:///test/close.ubnf", VALID_UBNF);
        assertNotNull(server.documents.get("file:///test/close.ubnf"));

        org.eclipse.lsp4j.DidCloseTextDocumentParams close =
            new org.eclipse.lsp4j.DidCloseTextDocumentParams(
                new TextDocumentIdentifier("file:///test/close.ubnf"));
        server.getTextDocumentService().didClose(close);

        assertEquals(null, server.documents.get("file:///test/close.ubnf"));
    }

    // --- helpers ---

    private void openDocument(String uri, String content) {
        TextDocumentItem item = new TextDocumentItem();
        item.setUri(uri);
        item.setLanguageId("ubnf");
        item.setVersion(1);
        item.setText(content);
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(item));
    }

    private static HoverParams hoverParams(String uri) {
        HoverParams params = new HoverParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(0, 0));
        return params;
    }

    private static void assertContainsLabel(List<CompletionItem> items, String label) {
        for (CompletionItem item : items) {
            if (label.equals(item.getLabel())) {
                return;
            }
        }
        throw new AssertionError("completion items missing keyword: " + label);
    }
}
