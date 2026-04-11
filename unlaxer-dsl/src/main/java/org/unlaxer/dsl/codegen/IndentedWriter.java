package org.unlaxer.dsl.codegen;

/**
 * Lightweight indentation-aware writer for generating Java source code.
 * Replaces raw {@code sb.append(indent).append(...)} chains with
 * structured {@link #line}, {@link #indent} / {@link #dedent} calls.
 */
final class IndentedWriter {
    private final StringBuilder sb = new StringBuilder();
    private int indent;

    IndentedWriter(int baseIndent) { this.indent = baseIndent; }

    /** Append one indented line (with trailing newline). */
    IndentedWriter line(String text) {
        sb.append("    ".repeat(indent)).append(text).append('\n');
        return this;
    }

    /** Append a blank line (no indentation, just newline). */
    IndentedWriter blankLine() {
        sb.append('\n');
        return this;
    }

    /** Append raw text without any indentation or trailing newline. */
    IndentedWriter raw(String text) {
        sb.append(text);
        return this;
    }

    /** Increase indent level by one. */
    IndentedWriter indent() { indent++; return this; }

    /** Decrease indent level by one. */
    IndentedWriter dedent() { indent--; return this; }

    String build() { return sb.toString(); }
}
