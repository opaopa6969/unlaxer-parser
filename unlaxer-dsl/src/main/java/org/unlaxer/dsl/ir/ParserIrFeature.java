package org.unlaxer.dsl.ir;

/**
 * Declared capability flags for parser IR adapters.
 */
public enum ParserIrFeature {
    INTERLEAVE,
    BACKREFERENCE,
    SCOPE_TREE,
    TOKENS,
    TRIVIA,
    SCOPE_EVENTS,
    ANNOTATIONS,
    DIAGNOSTICS
}
