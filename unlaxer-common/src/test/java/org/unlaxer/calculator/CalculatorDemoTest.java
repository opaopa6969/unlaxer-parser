package org.unlaxer.calculator;

import org.junit.Test;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.unlaxer.calculator.CalculatorLanguageServer.DocumentState;
import org.unlaxer.calculator.CalculatorLanguageServer.ParseResult;

/**
 * Demo test that shows colored output in console.
 */
public class CalculatorDemoTest {

    // ANSI color codes
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String BOLD = "\u001B[1m";

    @Test
    public void demonstrateCalculatorLsp() {
        System.out.println("\n" + CYAN + BOLD + "=== Calculator LSP Demo ===" + RESET + "\n");

        CalculatorLanguageServer server = new CalculatorLanguageServer();

        // Test cases
        String[] expressions = {
            "1+2*3",           // Fully valid
            "(1+3)/",          // Partially valid - (1+3) green, / red
            "sin(3.14)",       // Valid function call
            "sqrt(2)+cos(0)",  // Valid with multiple functions
            "/1+2",            // Invalid from start
            "1+",              // Incomplete
            "((1+2))",         // Nested parentheses
            "sin(cos(0))",     // Nested functions
        };

        for (String expr : expressions) {
            ParseResult result = parseAndDisplay(server, expr);
            System.out.println();
        }

        System.out.println(CYAN + "=== End of Demo ===" + RESET + "\n");
    }

    private ParseResult parseAndDisplay(CalculatorLanguageServer server, String input) {
        // Parse
        String uri = "test://" + input.hashCode();
        DidOpenTextDocumentParams params = new DidOpenTextDocumentParams();
        params.setTextDocument(new TextDocumentItem(uri, "calculator", 1, input));
        server.getTextDocumentService().didOpen(params);

        DocumentState state = server.getDocuments().get(uri);
        ParseResult result = state.parseResult;

        // Display
        System.out.print("Input:  " + BOLD + "\"" + input + "\"" + RESET);
        System.out.println();

        System.out.print("Output: ");
        displayColored(input, result);

        System.out.print("Status: ");
        if (result.isFullyValid()) {
            System.out.println(GREEN + "✓ Valid" + RESET);
        } else if (result.consumedLength > 0) {
            System.out.println(YELLOW + "⚠ Partial (valid: " + result.consumedLength +
                ", invalid: " + (result.totalLength - result.consumedLength) + " chars)" + RESET);
        } else {
            System.out.println(RED + "✗ Invalid" + RESET);
        }

        return result;
    }

    private void displayColored(String input, ParseResult result) {
        int validEnd = result.consumedLength;

        // Valid part (green)
        if (validEnd > 0) {
            System.out.print(GREEN + input.substring(0, validEnd) + RESET);
        }

        // Invalid part (red)
        if (validEnd < input.length()) {
            System.out.print(RED + input.substring(validEnd) + RESET);
        }

        System.out.println();
    }
}
