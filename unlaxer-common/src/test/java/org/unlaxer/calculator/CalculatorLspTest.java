package org.unlaxer.calculator;

import static org.junit.Assert.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.Before;
import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.calculator.CalculatorLanguageServer.DocumentState;
import org.unlaxer.calculator.CalculatorLanguageServer.ParseResult;
import org.unlaxer.parser.Parser;

/**
 * Tests for Calculator with LSP support.
 */
public class CalculatorLspTest extends ParserTestBase {

    private CalculatorLanguageServer server;

    @Before
    public void setUp() {
        server = new CalculatorLanguageServer();
    }

    // ========================================
    // Parser Tests - Basic Arithmetic
    // ========================================

    @Test
    public void testBasicNumbers() {
        Parser parser = CalculatorParsers.getRootParser();

        testAllMatch(parser, "1");
        testAllMatch(parser, "42");
        testAllMatch(parser, "123");
        testAllMatch(parser, "3.14");
        testAllMatch(parser, "0.5");
    }

    @Test
    public void testBasicAddition() {
        Parser parser = CalculatorParsers.getRootParser();

        testAllMatch(parser, "1+2");
        testAllMatch(parser, "1+2+3");
        testAllMatch(parser, "10+20+30");
    }

    @Test
    public void testBasicSubtraction() {
        Parser parser = CalculatorParsers.getRootParser();

        testAllMatch(parser, "5-3");
        testAllMatch(parser, "10-5-2");
    }

    @Test
    public void testBasicMultiplication() {
        Parser parser = CalculatorParsers.getRootParser();

        testAllMatch(parser, "2*3");
        testAllMatch(parser, "2*3*4");
    }

    @Test
    public void testBasicDivision() {
        Parser parser = CalculatorParsers.getRootParser();

        testAllMatch(parser, "10/2");
        testAllMatch(parser, "100/10/2");
    }

    @Test
    public void testMixedOperations() {
        Parser parser = CalculatorParsers.getRootParser();

        testAllMatch(parser, "1+2*3");
        testAllMatch(parser, "2*3+4");
        testAllMatch(parser, "10-4/2");
        testAllMatch(parser, "1+2*3-4/2");
    }

    // ========================================
    // Parser Tests - Parentheses
    // ========================================

    @Test
    public void testParentheses() {
        Parser parser = CalculatorParsers.getRootParser();

        testAllMatch(parser, "(1)");
        testAllMatch(parser, "(1+2)");
        testAllMatch(parser, "(1+2)*3");
        testAllMatch(parser, "1+(2*3)");
        testAllMatch(parser, "((1+2))");
        testAllMatch(parser, "(1+2)*(3+4)");
        testAllMatch(parser, "((1+2)*(3+4))");
    }

    // ========================================
    // Parser Tests - Unary Operators
    // ========================================

    @Test
    public void testUnaryOperators() {
        Parser parser = CalculatorParsers.getRootParser();

        testAllMatch(parser, "-1");
        testAllMatch(parser, "+1");
        testAllMatch(parser, "-42");
        testAllMatch(parser, "--1");
        testAllMatch(parser, "1+-2");
        testAllMatch(parser, "1*-2");
    }

    // ========================================
    // Parser Tests - Functions
    // ========================================

    @Test
    public void testSinFunction() {
        Parser parser = CalculatorParsers.getRootParser();

        testAllMatch(parser, "sin(0)");
        testAllMatch(parser, "sin(1)");
        testAllMatch(parser, "sin(3.14)");
        testAllMatch(parser, "sin(1+2)");
    }

    @Test
    public void testSqrtFunction() {
        Parser parser = CalculatorParsers.getRootParser();

        testAllMatch(parser, "sqrt(4)");
        testAllMatch(parser, "sqrt(2)");
        testAllMatch(parser, "sqrt(1+3)");
    }

    @Test
    public void testCosFunction() {
        Parser parser = CalculatorParsers.getRootParser();

        testAllMatch(parser, "cos(0)");
        testAllMatch(parser, "cos(3.14)");
        testAllMatch(parser, "cos(1+2)");
    }

    @Test
    public void testTanFunction() {
        Parser parser = CalculatorParsers.getRootParser();

        testAllMatch(parser, "tan(0)");
        testAllMatch(parser, "tan(1)");
        testAllMatch(parser, "tan(3.14/4)");
    }

    @Test
    public void testNestedFunctions() {
        Parser parser = CalculatorParsers.getRootParser();

        testAllMatch(parser, "sin(cos(0))");
        testAllMatch(parser, "sqrt(sin(1)+cos(1))");
        testAllMatch(parser, "1+sin(2)*cos(3)");
    }

    @Test
    public void testComplexExpressions() {
        Parser parser = CalculatorParsers.getRootParser();

        testAllMatch(parser, "sin(3.14/2)+cos(0)");
        testAllMatch(parser, "sqrt(2)*sin(3.14/4)");
        testAllMatch(parser, "(1+sin(0))*(2+cos(0))");
    }

    // ========================================
    // LSP Tests - Parsing and Validation
    // ========================================

    @Test
    public void testLspParseValidExpression() {
        ParseResult result = server.parseDocument("test://file1", "1+2+3");

        assertTrue(result.succeeded);
        assertEquals(5, result.consumedLength);
        assertEquals(5, result.totalLength);
        assertTrue(result.isFullyValid());
    }

    @Test
    public void testLspParsePartiallyValidExpression() {
        // "(1+3)/" - "(1+3)" is valid, "/" is incomplete
        ParseResult result = server.parseDocument("test://file2", "(1+3)/");

        assertTrue(result.succeeded);
        assertEquals(5, result.consumedLength);  // "(1+3)" = 5 chars
        assertEquals(6, result.totalLength);     // "(1+3)/" = 6 chars
        assertFalse(result.isFullyValid());
    }

    @Test
    public void testLspParseValidWithParens() {
        ParseResult result = server.parseDocument("test://file3", "(1+3)");

        assertTrue(result.succeeded);
        assertEquals(5, result.consumedLength);
        assertEquals(5, result.totalLength);
        assertTrue(result.isFullyValid());
    }

    @Test
    public void testLspParseInvalidExpression() {
        ParseResult result = server.parseDocument("test://file4", "abc");

        assertFalse(result.succeeded);
        assertEquals(0, result.consumedLength);
        assertEquals(3, result.totalLength);
    }

    @Test
    public void testLspParseWithFunction() {
        ParseResult result = server.parseDocument("test://file5", "sin(1)+cos(2)");

        assertTrue(result.succeeded);
        assertTrue(result.isFullyValid());
    }

    // ========================================
    // LSP Tests - Completion
    // ========================================

    @Test
    public void testCompletionWithS() throws Exception {
        // Open document with 's'
        openDocument("test://completion1", "s");

        // Request completion at position after 's'
        CompletionParams params = new CompletionParams();
        params.setTextDocument(new TextDocumentIdentifier("test://completion1"));
        params.setPosition(new Position(0, 1));

        CompletableFuture<Either<List<CompletionItem>, CompletionList>> future =
            server.getTextDocumentService().completion(params);

        Either<List<CompletionItem>, CompletionList> result = future.get();
        List<CompletionItem> items = result.getLeft();

        // Should suggest 'sin' and 'sqrt'
        assertNotNull(items);
        assertTrue(items.size() >= 2);

        boolean hasSin = items.stream().anyMatch(item -> "sin".equals(item.getLabel()));
        boolean hasSqrt = items.stream().anyMatch(item -> "sqrt".equals(item.getLabel()));

        assertTrue("Should suggest 'sin'", hasSin);
        assertTrue("Should suggest 'sqrt'", hasSqrt);
    }

    @Test
    public void testCompletionWithC() throws Exception {
        // Open document with 'c'
        openDocument("test://completion2", "c");

        CompletionParams params = new CompletionParams();
        params.setTextDocument(new TextDocumentIdentifier("test://completion2"));
        params.setPosition(new Position(0, 1));

        CompletableFuture<Either<List<CompletionItem>, CompletionList>> future =
            server.getTextDocumentService().completion(params);

        Either<List<CompletionItem>, CompletionList> result = future.get();
        List<CompletionItem> items = result.getLeft();

        // Should suggest 'cos'
        assertNotNull(items);
        assertTrue(items.size() >= 1);

        boolean hasCos = items.stream().anyMatch(item -> "cos".equals(item.getLabel()));
        assertTrue("Should suggest 'cos'", hasCos);
    }

    @Test
    public void testCompletionWithT() throws Exception {
        // Open document with 't'
        openDocument("test://completion3", "t");

        CompletionParams params = new CompletionParams();
        params.setTextDocument(new TextDocumentIdentifier("test://completion3"));
        params.setPosition(new Position(0, 1));

        CompletableFuture<Either<List<CompletionItem>, CompletionList>> future =
            server.getTextDocumentService().completion(params);

        Either<List<CompletionItem>, CompletionList> result = future.get();
        List<CompletionItem> items = result.getLeft();

        // Should suggest 'tan'
        assertNotNull(items);
        assertTrue(items.size() >= 1);

        boolean hasTan = items.stream().anyMatch(item -> "tan".equals(item.getLabel()));
        assertTrue("Should suggest 'tan'", hasTan);
    }

    @Test
    public void testCompletionInExpression() throws Exception {
        // Open document with expression ending in 's'
        openDocument("test://completion4", "1+s");

        CompletionParams params = new CompletionParams();
        params.setTextDocument(new TextDocumentIdentifier("test://completion4"));
        params.setPosition(new Position(0, 3));

        CompletableFuture<Either<List<CompletionItem>, CompletionList>> future =
            server.getTextDocumentService().completion(params);

        Either<List<CompletionItem>, CompletionList> result = future.get();
        List<CompletionItem> items = result.getLeft();

        // Should suggest 'sin' and 'sqrt'
        assertNotNull(items);
        boolean hasSin = items.stream().anyMatch(item -> "sin".equals(item.getLabel()));
        boolean hasSqrt = items.stream().anyMatch(item -> "sqrt".equals(item.getLabel()));

        assertTrue("Should suggest 'sin'", hasSin);
        assertTrue("Should suggest 'sqrt'", hasSqrt);
    }

    // ========================================
    // LSP Tests - Syntax Highlighting (Semantic Tokens)
    // ========================================

    @Test
    public void testSyntaxHighlightingFullyValid() {
        ParseResult result = server.parseDocument("test://highlight1", "1+2+3");

        // All 5 characters should be valid (green)
        assertTrue(result.isFullyValid());
        assertEquals(5, result.consumedLength);
    }

    @Test
    public void testSyntaxHighlightingPartiallyValid() {
        // "(1+3)/" - valid part: "(1+3)", invalid part: "/"
        ParseResult result = server.parseDocument("test://highlight2", "(1+3)/");

        assertFalse(result.isFullyValid());
        assertEquals(5, result.consumedLength);  // "(1+3)" is green
        assertEquals(6, result.totalLength);     // "/" is red

        // Valid range: 0-4 (inclusive)
        // Invalid range: 5-5 (the '/')
    }

    @Test
    public void testSyntaxHighlightingInvalidStart() {
        ParseResult result = server.parseDocument("test://highlight3", "/1+2");

        // '/' at start is invalid
        assertFalse(result.succeeded);
        assertEquals(0, result.consumedLength);  // Nothing is valid
    }

    @Test
    public void testSyntaxHighlightingMissingOperand() {
        ParseResult result = server.parseDocument("test://highlight4", "1+");

        // "1" is valid, "+" is incomplete/invalid
        assertTrue(result.succeeded);
        assertEquals(1, result.consumedLength);  // "1" is green
        assertEquals(2, result.totalLength);     // "+" is red
    }

    @Test
    public void testSyntaxHighlightingUnclosedParen() {
        ParseResult result = server.parseDocument("test://highlight5", "(1+2");

        // Unclosed parenthesis - nothing is valid since we can't complete the parse
        assertFalse(result.succeeded);
    }

    // ========================================
    // Integration Tests
    // ========================================

    @Test
    public void testFullWorkflow() throws Exception {
        String uri = "test://workflow";

        // Step 1: Open document with partial expression - 'sin' alone is incomplete
        openDocument(uri, "sin");

        DocumentState state = server.getDocuments().get(uri);
        assertNotNull(state);
        // 'sin' alone is not a valid expression (needs parentheses)
        assertFalse(state.parseResult.isFullyValid());

        // Step 2: Update to complete expression
        server.parseDocument(uri, "sin(1)");

        state = server.getDocuments().get(uri);
        assertTrue(state.parseResult.isFullyValid());

        // Step 3: Add more to expression
        server.parseDocument(uri, "sin(1)+cos(2)");

        state = server.getDocuments().get(uri);
        assertTrue(state.parseResult.isFullyValid());

        // Step 4: Make it invalid
        server.parseDocument(uri, "sin(1)+cos(2)+");

        state = server.getDocuments().get(uri);
        assertFalse(state.parseResult.isFullyValid());
        // "sin(1)+cos(2)" should be valid, "+" should be invalid
        assertTrue(state.parseResult.consumedLength < state.parseResult.totalLength);
    }

    // ========================================
    // Helper Methods
    // ========================================

    private void openDocument(String uri, String content) {
        DidOpenTextDocumentParams params = new DidOpenTextDocumentParams();
        params.setTextDocument(new TextDocumentItem(uri, "calculator", 1, content));
        server.getTextDocumentService().didOpen(params);
    }
}
