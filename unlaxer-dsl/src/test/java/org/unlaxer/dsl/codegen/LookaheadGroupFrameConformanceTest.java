package org.unlaxer.dsl.codegen;

import java.nio.file.Path;

/** Failed group/choice frames after LOOKAHEAD: Java and both generated Rust hosts (#793). */
public class LookaheadGroupFrameConformanceTest extends AtomicTriviaConformanceTest {
    @Override protected Path fixtureCorpus() {
        return Path.of("../spec-corpus/lookahead-group-frame/corpus.json");
    }

    @Override protected String reportName() { return "rust-lookahead-group-frame"; }
}
