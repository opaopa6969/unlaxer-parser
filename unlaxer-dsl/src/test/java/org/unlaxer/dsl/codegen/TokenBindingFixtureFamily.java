package org.unlaxer.dsl.codegen;

/** Canonical nested Java names are valid registry bindings, too. */
public final class TokenBindingFixtureFamily {
    private TokenBindingFixtureFamily() {}
    public static final class FinalParser extends org.unlaxer.parser.elementary.WordParser {
        private static final long serialVersionUID = 1L;
        static { System.setProperty("final.token.binding.initialized", "true"); }
        public FinalParser() { super("a"); }
    }
}
