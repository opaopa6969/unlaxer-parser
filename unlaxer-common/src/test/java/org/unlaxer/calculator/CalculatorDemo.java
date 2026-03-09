package org.unlaxer.calculator;

import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.unlaxer.calculator.CalculatorLanguageServer.DocumentState;
import org.unlaxer.calculator.CalculatorLanguageServer.ParseResult;

/**
 * Interactive demo for Calculator LSP.
 *
 * Run this to test the calculator interactively in the terminal.
 *
 * Usage: mvn exec:java -Dexec.mainClass="org.unlaxer.calculator.CalculatorDemo" -Dexec.classpathScope=test
 * Or run directly from IDE
 */
public class CalculatorDemo {

    // ANSI color codes
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String BOLD = "\u001B[1m";

    private final CalculatorLanguageServer server;
    private final String documentUri = "demo://calculator";

    public CalculatorDemo() {
        this.server = new CalculatorLanguageServer();
    }

    public static void main(String[] args) {
        CalculatorDemo demo = new CalculatorDemo();
        demo.run();
    }

    public void run() {
        printHeader();

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print(BOLD + "\nExpression> " + RESET);
            String input = scanner.nextLine();

            if (input == null || "exit".equalsIgnoreCase(input.trim()) || "quit".equalsIgnoreCase(input.trim())) {
                System.out.println(CYAN + "Goodbye!" + RESET);
                break;
            }

            if ("help".equalsIgnoreCase(input.trim())) {
                printHelp();
                continue;
            }

            processInput(input);
        }

        scanner.close();
    }

    private void printHeader() {
        System.out.println(CYAN + BOLD);
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║           Calculator LSP Demo                            ║");
        System.out.println("║                                                          ║");
        System.out.println("║  Features:                                               ║");
        System.out.println("║  - Four operations: +, -, *, /                           ║");
        System.out.println("║  - Parentheses: (1+2)*3                                  ║");
        System.out.println("║  - Functions: sin, sqrt, cos, tan                        ║");
        System.out.println("║  - Auto-completion: type 's', 'c', or 't'                ║");
        System.out.println("║                                                          ║");
        System.out.println("║  Commands: 'help', 'exit'                                ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println(RESET);
    }

    private void printHelp() {
        System.out.println(YELLOW);
        System.out.println("Syntax:");
        System.out.println("  Numbers:    42, 3.14, 0.5");
        System.out.println("  Operations: +, -, *, /");
        System.out.println("  Unary:      -5, +3");
        System.out.println("  Parentheses: (1+2)*3");
        System.out.println("  Functions:  sin(x), sqrt(x), cos(x), tan(x)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  1+2*3           => valid");
        System.out.println("  (1+3)/          => partially valid (green: '(1+3)', red: '/')");
        System.out.println("  sin(3.14/2)     => valid");
        System.out.println("  sqrt(2)+cos(0)  => valid");
        System.out.println();
        System.out.println("Auto-completion:");
        System.out.println("  Type 's' to see: sin, sqrt");
        System.out.println("  Type 'c' to see: cos");
        System.out.println("  Type 't' to see: tan");
        System.out.println(RESET);
    }

    private void processInput(String input) {
        // Open/update document
        DidOpenTextDocumentParams params = new DidOpenTextDocumentParams();
        params.setTextDocument(new TextDocumentItem(documentUri, "calculator", 1, input));
        server.getTextDocumentService().didOpen(params);

        // Get parse result
        DocumentState state = server.getDocuments().get(documentUri);
        if (state == null) {
            System.out.println(RED + "Error: Could not parse document" + RESET);
            return;
        }

        ParseResult result = state.parseResult;

        // Display colored output
        System.out.println();
        System.out.print("Result:     ");
        displayColoredExpression(input, result);

        // Display validation status
        System.out.print("Status:     ");
        if (result.isFullyValid()) {
            System.out.println(GREEN + "✓ Valid expression" + RESET);
        } else if (result.consumedLength > 0) {
            System.out.println(YELLOW + "⚠ Partially valid (valid: " + result.consumedLength +
                " chars, invalid: " + (result.totalLength - result.consumedLength) + " chars)" + RESET);
        } else {
            System.out.println(RED + "✗ Invalid expression" + RESET);
        }

        // Check for auto-completion
        checkCompletion(input);
    }

    private void displayColoredExpression(String input, ParseResult result) {
        if (input.isEmpty()) {
            System.out.println(YELLOW + "(empty)" + RESET);
            return;
        }

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

    private void checkCompletion(String input) {
        if (input.isEmpty()) {
            return;
        }

        // Find the last word
        int wordStart = input.length();
        while (wordStart > 0 && Character.isLetter(input.charAt(wordStart - 1))) {
            wordStart--;
        }

        if (wordStart >= input.length()) {
            return;
        }

        String lastWord = input.substring(wordStart).toLowerCase();

        // Check if it's a partial function name
        if (lastWord.length() > 0 && lastWord.length() < 4) {
            try {
                CompletionParams params = new CompletionParams();
                params.setTextDocument(new TextDocumentIdentifier(documentUri));
                params.setPosition(new Position(0, input.length()));

                CompletableFuture<Either<List<CompletionItem>, CompletionList>> future =
                    server.getTextDocumentService().completion(params);

                Either<List<CompletionItem>, CompletionList> completionResult = future.get();
                List<CompletionItem> items = completionResult.getLeft();

                if (items != null && false == items.isEmpty()) {
                    System.out.print("Suggestions: ");
                    for (int i = 0; i < items.size(); i++) {
                        if (i > 0) {
                            System.out.print(", ");
                        }
                        System.out.print(CYAN + items.get(i).getLabel() + RESET);
                    }
                    System.out.println();
                }
            } catch (Exception e) {
                // Ignore completion errors
            }
        }
    }
}
