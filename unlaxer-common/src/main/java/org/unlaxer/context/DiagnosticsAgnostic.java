package org.unlaxer.context;

/**
 * Declares that a parser does not read {@link ParseContext#getParseFailureDiagnostics()}
 * during parsing and can be rerun on the same input in a fresh context.
 * Callbacks and external effects must also tolerate that retry. Subclasses inherit this contract.
 * This declaration covers this parser's own behavior; children are checked separately.
 * It does not declare memoization safety.
 */
public interface DiagnosticsAgnostic {}
