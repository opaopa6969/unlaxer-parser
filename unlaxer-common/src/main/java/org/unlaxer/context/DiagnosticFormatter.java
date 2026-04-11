package org.unlaxer.context;

import java.util.List;
import java.util.Set;

import org.unlaxer.CodePointIndex;
import org.unlaxer.CodePointIndexInLine;
import org.unlaxer.LineNumber;
import org.unlaxer.Source;

/**
 * Formats parse errors with source context, similar to Rust/Elm error display.
 *
 * <pre>
 * error: Expected ';' after rule definition
 *   --> source:15:28
 *    |
 * 15 | Expression = Term { Op Term }
 *    |                             ^ expected ';' here
 * </pre>
 */
public final class DiagnosticFormatter {

    private DiagnosticFormatter() {}

    /**
     * Format a diagnostic with source context using a code-point offset.
     *
     * @param source  the source text
     * @param offset  zero-based code-point offset into the source
     * @param message human-readable error message
     * @return formatted diagnostic string
     */
    public static String format(Source source, int offset, String message) {
        if (source == null || source.sourceAsString().isEmpty()) {
            return formatEmpty(message);
        }

        int safeOffset = Math.max(0, Math.min(offset, source.codePointLength().value()));
        CodePointIndex codePointIndex = new CodePointIndex(safeOffset);

        LineNumber lineNumber = source.lineNumberFrom(codePointIndex);
        CodePointIndexInLine columnIndex = source.codePointIndexInLineFrom(codePointIndex);

        int line = lineNumber.value() + 1;   // 1-based
        int column = columnIndex.value() + 1; // 1-based

        return formatInternal(source, line, column, message);
    }

    /**
     * Format a diagnostic with explicit 1-based line and column numbers.
     *
     * @param source  the source text
     * @param line    1-based line number
     * @param column  1-based column number
     * @param message human-readable error message
     * @return formatted diagnostic string
     */
    public static String format(Source source, int line, int column, String message) {
        if (source == null || source.sourceAsString().isEmpty()) {
            return formatEmpty(message);
        }
        return formatInternal(source, line, column, message);
    }

    /**
     * Format a diagnostic from ParseFailureDiagnostics.
     *
     * @param source      the source text
     * @param diagnostics parse failure diagnostics
     * @return formatted diagnostic string
     */
    public static String format(Source source, ParseFailureDiagnostics diagnostics) {
        if (source == null || source.sourceAsString().isEmpty()) {
            return formatEmpty(buildDiagnosticsMessage(diagnostics));
        }

        int line = diagnostics.getLine() + 1;   // 1-based
        int column = diagnostics.getColumn() + 1; // 1-based

        String message = buildDiagnosticsMessage(diagnostics);
        return formatInternal(source, line, column, message);
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private static String formatEmpty(String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("error: ").append(message).append('\n');
        sb.append("  --> source:0:0").append('\n');
        sb.append("   |").append('\n');
        sb.append("   | (empty source)").append('\n');
        sb.append("   |");
        return sb.toString();
    }

    private static String formatInternal(Source source, int line, int column, String message) {
        String sourceText = source.sourceAsString();
        String[] lines = sourceText.split("\n", -1);

        int lineIndex = line - 1; // 0-based index into lines array
        if (lineIndex < 0) {
            lineIndex = 0;
        }
        if (lineIndex >= lines.length) {
            lineIndex = lines.length - 1;
        }

        String sourceLine = lines[lineIndex];
        int displayLine = lineIndex + 1; // 1-based for display
        int displayColumn = Math.max(1, column);

        String lineNumStr = String.valueOf(displayLine);
        int gutterWidth = lineNumStr.length();

        StringBuilder sb = new StringBuilder();

        // error header
        sb.append("error: ").append(message).append('\n');

        // location
        sb.append("  --> source:").append(displayLine).append(':').append(displayColumn).append('\n');

        // blank gutter line
        sb.append(gutterPad(gutterWidth)).append(" |").append('\n');

        // source line
        sb.append(lineNumStr).append(" | ").append(sourceLine).append('\n');

        // pointer line
        int pointerPosition = displayColumn - 1;
        // Clamp pointer to not exceed line length
        if (pointerPosition > sourceLine.length()) {
            pointerPosition = sourceLine.length();
        }
        sb.append(gutterPad(gutterWidth)).append(" | ");
        for (int i = 0; i < pointerPosition; i++) {
            if (i < sourceLine.length() && sourceLine.charAt(i) == '\t') {
                sb.append('\t');
            } else {
                sb.append(' ');
            }
        }
        sb.append('^').append(' ').append(message);

        return sb.toString();
    }

    private static String gutterPad(int width) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < width; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String buildDiagnosticsMessage(ParseFailureDiagnostics diagnostics) {
        StringBuilder sb = new StringBuilder();

        Set<String> expectedTokens = diagnostics.getExpectedTokens();
        List<String> expectedParsers = diagnostics.getExpectedParsers();
        List<ParseFailureDiagnostics.ExpectedHintCandidate> hints = diagnostics.getExpectedHintCandidates();

        if (false == expectedTokens.isEmpty()) {
            sb.append("expected ");
            sb.append(String.join(" or ", expectedTokens));
        } else if (false == hints.isEmpty()) {
            sb.append("expected ");
            boolean first = true;
            for (ParseFailureDiagnostics.ExpectedHintCandidate hint : hints) {
                if (false == first) {
                    sb.append(" or ");
                }
                sb.append(hint.getDisplayHint());
                first = false;
            }
        } else if (false == expectedParsers.isEmpty()) {
            sb.append("expected ");
            sb.append(String.join(" or ", expectedParsers));
        } else {
            sb.append("parse error at offset ").append(diagnostics.getFarthestOffset());
        }

        String deepestRule = diagnostics.getDeepestMatchedRule();
        if (deepestRule != null && false == deepestRule.isEmpty()) {
            sb.append(" (in ").append(deepestRule).append(')');
        }

        return sb.toString();
    }
}
