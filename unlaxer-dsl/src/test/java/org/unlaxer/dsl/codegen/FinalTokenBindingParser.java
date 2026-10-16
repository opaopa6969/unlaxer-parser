package org.unlaxer.dsl.codegen;

/** Real valid final Parser implementation; generation must not initialize it. */
public final class FinalTokenBindingParser extends org.unlaxer.parser.elementary.WordParser {
    private static final long serialVersionUID = 1L;
    static { System.setProperty("final.token.binding.initialized", "true"); }
    public FinalTokenBindingParser() { super("a"); }
}
