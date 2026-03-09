package org.unlaxer.tinycalc;

import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenPrinter;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;

/**
 * TinyCalc demo - parses sample expressions and displays results.
 */
public class TinyCalcDemo {

    static final String ANSI_RESET  = "\u001B[0m";
    static final String ANSI_GREEN  = "\u001B[32m";
    static final String ANSI_RED    = "\u001B[31m";
    static final String ANSI_YELLOW = "\u001B[33m";
    static final String ANSI_CYAN   = "\u001B[36m";
    static final String ANSI_BOLD   = "\u001B[1m";

    public static void main(String[] args) {
        System.out.println(ANSI_BOLD + "=== TinyCalc Demo ===" + ANSI_RESET);
        System.out.println();

        // Simple expressions
        System.out.println(ANSI_BOLD + "--- Simple Expressions ---" + ANSI_RESET);
        parseExpression("1+2*3");
        parseExpression("1 + 2 * 3");
        parseExpression("(1+2)*3");
        parseExpression("10/2-3");

        // Unary expressions
        System.out.println(ANSI_BOLD + "--- Unary Expressions ---" + ANSI_RESET);
        parseExpression("-5+3");
        parseExpression("+42");
        parseExpression("-(1+2)");

        // Function calls
        System.out.println(ANSI_BOLD + "--- Function Calls ---" + ANSI_RESET);
        parseExpression("sin(3.14)");
        parseExpression("sqrt(2)");
        parseExpression("cos(0)+1");
        parseExpression("max(1,2)");
        parseExpression("min(10, 20)");
        parseExpression("pow(2, 8)");
        parseExpression("random()");

        // Nested functions
        System.out.println(ANSI_BOLD + "--- Nested Expressions ---" + ANSI_RESET);
        parseExpression("sin(3.14)+cos(0)");
        parseExpression("sqrt(abs(-16))");
        parseExpression("max(sin(1), cos(1))");

        // Identifiers
        System.out.println(ANSI_BOLD + "--- Identifier Expressions ---" + ANSI_RESET);
        parseExpression("x+y*z");
        parseExpression("price * tax");

        // Full TinyCalc with variable declarations
        System.out.println(ANSI_BOLD + "--- Full TinyCalc (with variables) ---" + ANSI_RESET);
        parseTinyCalc("var x; x");
        parseTinyCalc("var x set 10; x");
        parseTinyCalc("var x set 10; var y set 20; x + y");
        parseTinyCalc("variable radius set 5; 3.14 * radius * radius");
        parseTinyCalc("var x set 10; sin(x) + sqrt(3.14)");

        // Expected failures
        System.out.println(ANSI_BOLD + "--- Expected Failures ---" + ANSI_RESET);
        parseExpression("");
        parseExpression("+++");
        parseExpression("1 +");

        // Detailed trace for documentation
        System.out.println();
        System.out.println(ANSI_BOLD + "=== Detailed Trace: 1+2*3 ===" + ANSI_RESET);
        parseExpressionWithTrace("1+2*3");
    }

    static void parseExpression(String input) {
        Parser parser = TinyCalcParsers.getExpressionParser();
        ParseContext context = new ParseContext(StringSource.createRootSource(input));
        Parsed parsed = parser.parse(context);
        printResult("Expression", input, parsed);
    }

    static void parseTinyCalc(String input) {
        Parser parser = TinyCalcParsers.getRootParser();
        ParseContext context = new ParseContext(StringSource.createRootSource(input));
        Parsed parsed = parser.parse(context);
        printResult("TinyCalc", input, parsed);
    }

    static void parseExpressionWithTrace(String input) {
        Parser parser = TinyCalcParsers.getExpressionParser();
        ParseContext context = new ParseContext(StringSource.createRootSource(input));
        Parsed parsed = parser.parse(context);
        printResult("Trace", input, parsed);

        if (parsed.isSucceeded()) {
            Token rootToken = parsed.getRootToken();
            System.out.println(ANSI_CYAN + "Token Tree:" + ANSI_RESET);
            System.out.println(TokenPrinter.get(rootToken));
            System.out.println();
            System.out.println(ANSI_CYAN + "Reduced Token Tree (AST):" + ANSI_RESET);
            System.out.println(TokenPrinter.get(parsed.getRootToken(true)));
        }
    }

    static void printResult(String label, String input, Parsed parsed) {
        String statusColor;
        String statusText;

        if (parsed.isSucceeded()) {
            Token rootToken = parsed.getRootToken();
            String matched = rootToken.source.toString();
            if (matched.length() == input.length()) {
                statusColor = ANSI_GREEN;
                statusText = "FULL MATCH";
            } else {
                statusColor = ANSI_YELLOW;
                statusText = "PARTIAL [" + matched.length() + "/" + input.length() + "]";
            }
            System.out.printf("  %s[%s]%s %-12s \"%s\" -> matched: \"%s\"%n",
                statusColor, statusText, ANSI_RESET, label, input, matched);
        } else {
            statusColor = ANSI_RED;
            statusText = "FAILED";
            System.out.printf("  %s[%s]%s   %-12s \"%s\"%n",
                statusColor, statusText, ANSI_RESET, label, input);
        }
    }
}
