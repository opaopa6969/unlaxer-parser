package org.unlaxer.dsl.codegen;

import java.nio.file.Path;

/** Real Java/native generated consumers, with native and explicit Classic entry points. */
public class AtomicTriviaConformanceTest extends RuleTriviaConformanceTest {
    @Override protected Path fixtureCorpus() {
        return Path.of("../conformance/token-propagation/generated-atomic-trivia.json");
    }
    @Override protected String rustProbe() {
        return super.rustProbe().replace("let options =", """
            let mut classic = unlaxer_runtime::ParseContext::new(&input);
            let classic_ok = classic.parse_with_token_mode(&generated::parser::GeneratedParser,
                unlaxer_runtime::token::TokenKind::Consumed, false).is_ok();
            assert_eq!((prefix_ok,context.position(),context.matched_position()),
                (classic_ok,classic.position(),classic.matched_position()), "Classic prefix: {input}");
            let options =
            """);
    }
}
