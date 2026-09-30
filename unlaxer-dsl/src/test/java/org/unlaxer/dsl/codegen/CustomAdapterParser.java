package org.unlaxer.dsl.codegen;

import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.elementary.WordParser;

/** Test host implementation: consume in two steps so failure exercises rollback. */
public class CustomAdapterParser extends Chain {
    private static final long serialVersionUID = 1L;
    static { System.setProperty("token.adapter.probe.loaded", "true"); }

    public CustomAdapterParser() {
        super(new WordParser("😀"), new WordParser("x"));
    }
}
