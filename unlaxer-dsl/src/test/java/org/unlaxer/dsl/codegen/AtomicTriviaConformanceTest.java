package org.unlaxer.dsl.codegen;

import java.nio.file.Path;

/** Real Java/native generated consumers, with native and explicit Classic entry points. */
public class AtomicTriviaConformanceTest extends RuleTriviaConformanceTest {
    @Override protected Path fixtureCorpus() {
        return Path.of("../conformance/token-propagation/generated-atomic-trivia.json");
    }
    @Override protected boolean explicitClassicMapping() { return true; }
    @Override protected String rustProbe() {
        return super.rustProbe().replace("let options =", """
            let mut classic = unlaxer_runtime::ParseContext::new(&input);
            let classic_parsed = classic.parse_with_token_mode(&generated::parser::GeneratedParser,
                unlaxer_runtime::token::TokenKind::Consumed, false);
            let classic_ok = classic_parsed.is_ok();
            if let Ok(parsed) = classic_parsed {
                if classic.position() == input.chars().count() {
                    let root_id = classic.token_node_id(parsed.nodes[0]).unwrap();
                    assert_eq!(classic.token_text(root_id).unwrap().as_bytes(), input.as_bytes(),
                        "Classic root raw UTF-8: {input}");
                    let tree = classic.tree(parsed.nodes[0]).unwrap();
                    let native = generated::parser::parse_tree(&input).unwrap();
                    assert_eq!(generated::mapper::map(&tree).unwrap().canonical_json(),
                        generated::mapper::map(&native).unwrap().canonical_json(), "Classic AST: {input}");
                }
            }
            assert_eq!((prefix_ok,context.position(),context.matched_position()),
                (classic_ok,classic.position(),classic.matched_position()), "Classic prefix: {input}");
            let options =
            """);
    }
}
