package org.unlaxer.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.Test;
import org.unlaxer.Source;
import org.unlaxer.StringSource;

/**
 * Tests for {@link DiagnosticFormatter}.
 */
public class DiagnosticFormatterTest {

    @Test
    public void testBasicFormatWithLineAndColumn() {
        Source source = StringSource.createRootSource("hello world");
        String result = DiagnosticFormatter.format(source, 1, 7, "unexpected token");

        assertTrue(result.contains("error: unexpected token"));
        assertTrue(result.contains("--> source:1:7"));
        assertTrue(result.contains("hello world"));
        assertTrue(result.contains("^"));
    }

    @Test
    public void testFormatWithOffset() {
        Source source = StringSource.createRootSource("abc");
        // offset 1 means character 'b', which is line 1, column 2
        String result = DiagnosticFormatter.format(source, 1, "bad char");

        assertTrue(result.contains("error: bad char"));
        assertTrue(result.contains("--> source:1:2"));
        assertTrue(result.contains("abc"));
        assertTrue(result.contains("^"));
    }

    @Test
    public void testMultiLineSourceErrorOnFirstLine() {
        Source source = StringSource.createRootSource("line one\nline two\nline three");
        // offset 0 = 'l' of "line one", line 1, column 1
        String result = DiagnosticFormatter.format(source, 0, "error at start");

        assertTrue(result.contains("--> source:1:1"));
        assertTrue(result.contains("line one"));
        assertTrue(result.contains("^ error at start"));
    }

    @Test
    public void testMultiLineSourceErrorOnSecondLine() {
        Source source = StringSource.createRootSource("line one\nline two\nline three");
        // offset 9 = 'l' of "line two", line 2, column 1
        String result = DiagnosticFormatter.format(source, 9, "second line error");

        assertTrue(result.contains("--> source:2:1"));
        assertTrue(result.contains("line two"));
    }

    @Test
    public void testMultiLineSourceErrorAtEndOfSecondLine() {
        Source source = StringSource.createRootSource("line one\nline two\nline three");
        // offset 16 = 'o' of "two", line 2, column 8
        String result = DiagnosticFormatter.format(source, 16, "end of line two");

        assertTrue(result.contains("--> source:2:8"));
        assertTrue(result.contains("line two"));
    }

    @Test
    public void testEmptySource() {
        Source source = StringSource.createRootSource("");
        String result = DiagnosticFormatter.format(source, 0, "empty input");

        assertTrue(result.contains("error: empty input"));
        assertTrue(result.contains("(empty source)"));
    }

    @Test
    public void testNullSource() {
        String result = DiagnosticFormatter.format((Source) null, 0, "null source");

        assertTrue(result.contains("error: null source"));
        assertTrue(result.contains("(empty source)"));
    }

    @Test
    public void testUnicodeContentJapanese() {
        Source source = StringSource.createRootSource("変数 = 値;");
        // offset 0 = first character
        String result = DiagnosticFormatter.format(source, 0, "unexpected character");

        assertTrue(result.contains("error: unexpected character"));
        assertTrue(result.contains("変数 = 値;"));
        assertTrue(result.contains("--> source:1:1"));
    }

    @Test
    public void testPointerAlignmentAtBeginning() {
        Source source = StringSource.createRootSource("Expression = Term { Op Term }");
        String result = DiagnosticFormatter.format(source, 1, 1, "error here");

        // pointer should be at column 1 (beginning)
        String[] lines = result.split("\n");
        // find the pointer line (last line)
        String pointerLine = lines[lines.length - 1];
        assertTrue(pointerLine.contains("^ error here"));
        // No leading spaces before ^ on the content area
        int barIndex = pointerLine.indexOf("| ");
        String afterBar = pointerLine.substring(barIndex + 2);
        assertEquals('^', afterBar.charAt(0));
    }

    @Test
    public void testPointerAlignmentAtMiddle() {
        Source source = StringSource.createRootSource("Expression = Term { Op Term }");
        // column 14 = 'T' of Term
        String result = DiagnosticFormatter.format(source, 1, 14, "expected ';' here");

        assertTrue(result.contains("--> source:1:14"));
        // Verify pointer is preceded by spaces
        String[] lines = result.split("\n");
        String pointerLine = lines[lines.length - 1];
        assertTrue(pointerLine.contains("^"));
        // Count spaces before ^
        int barIndex = pointerLine.indexOf("| ");
        String afterBar = pointerLine.substring(barIndex + 2);
        int caretPos = afterBar.indexOf('^');
        assertEquals(13, caretPos); // 0-based, column 14 means 13 spaces
    }

    @Test
    public void testPointerAlignmentAtEnd() {
        Source source = StringSource.createRootSource("abc");
        // column 4 = past end
        String result = DiagnosticFormatter.format(source, 1, 4, "expected more");

        assertTrue(result.contains("--> source:1:4"));
        assertTrue(result.contains("^ expected more"));
    }

    @Test
    public void testFormatWithParseDiagnostics() {
        ParseFailureDiagnostics diagnostics = new ParseFailureDiagnostics(
            5, 5, 5,
            0, 5,  // line 0 (0-based), column 5 (0-based)
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            true,
            Collections.emptyList(),
            Set.of("';'", "')'"),
            "Statement",
            5
        );

        Source source = StringSource.createRootSource("x = 1 + 2");
        String result = DiagnosticFormatter.format(source, diagnostics);

        assertTrue(result.contains("error:"));
        assertTrue(result.contains("expected"));
        assertTrue(result.contains("x = 1 + 2"));
        assertTrue(result.contains("Statement"));
    }

    @Test
    public void testFormatWithEmptyDiagnostics() {
        ParseFailureDiagnostics diagnostics = new ParseFailureDiagnostics(
            3, 3, 3,
            0, 3,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            false,
            Collections.emptyList(),
            Collections.emptySet(),
            "",
            0
        );

        Source source = StringSource.createRootSource("abcdef");
        String result = DiagnosticFormatter.format(source, diagnostics);

        assertTrue(result.contains("error:"));
        assertTrue(result.contains("parse error at offset 3"));
    }

    @Test
    public void testLineNumberGutterWidthForLargeLineNumbers() {
        // Create a source with many lines
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("line ").append(i).append('\n');
        }
        sb.append("error line");
        Source source = StringSource.createRootSource(sb.toString());

        // Format at line 101 (the last line)
        String result = DiagnosticFormatter.format(source, 101, 6, "problem");

        assertTrue(result.contains("--> source:101:6"));
        assertTrue(result.contains("error line"));
        // gutter should be 3 characters wide for "101"
        assertTrue(result.contains("101 | error line"));
    }

    @Test
    public void testFormatWithExplicitLineColumnMatchesExpectedShape() {
        Source source = StringSource.createRootSource("Expression = Term { Op Term }");
        String result = DiagnosticFormatter.format(source, 1, 29, "expected ';' here");

        // Output has 5 lines:
        // 0: error: expected ';' here
        // 1:   --> source:1:29
        // 2:  |
        // 3: 1 | Expression = Term { Op Term }
        // 4:  |                             ^ expected ';' here
        String[] lines = result.split("\n");
        assertEquals(5, lines.length);
        assertEquals("error: expected ';' here", lines[0]);
        assertEquals("  --> source:1:29", lines[1]);
        // gutter width for "1" is 1, so blank gutter line is " " + " |"
        assertTrue(lines[2].endsWith("|"));
        assertTrue(lines[2].trim().equals("|"));
        assertTrue(lines[3].startsWith("1 | Expression = Term { Op Term }"));
        assertTrue(lines[4].contains("^"));
        assertTrue(lines[4].contains("expected ';' here"));
    }
}
