//! First-order parameterized rules (#701) against the shared corpus
//! `spec-corpus/parameterized-rules/cases.json`, whose expected values are written by
//! hand: the expanded grammar is a hand-written equivalent, instance names come from
//! hand-written canonical keys, and rejections carry their code-point span and position.
//! `ParameterizedRuleConformanceTest` checks the same file against the Java frontend.
use std::path::Path;

#[path = "support/canonical.rs"]
mod canonical;
#[path = "support/json.rs"]
mod json;

use json::Json;
use unlaxer_ubnf::ElementKind;

fn cases() -> Vec<Json> {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../spec-corpus/parameterized-rules/cases.json");
    let text = std::fs::read_to_string(&path).unwrap();
    json::parse(&text).unwrap().array().to_vec()
}

fn number(value: &Json, key: &str) -> usize {
    match value.get(key) {
        Some(Json::Number(n)) => *n as usize,
        other => panic!("{key}: {other:?}"),
    }
}

fn fnv1a64(key: &str) -> String {
    let mut hash = 0xcbf2_9ce4_8422_2325u64;
    for byte in key.bytes() {
        hash ^= u64::from(byte);
        hash = hash.wrapping_mul(0x0100_0000_01b3);
    }
    format!("{hash:016x}")
}

#[test]
fn corpus_expansions_equal_hand_written_grammars() {
    let mut accepted = 0;
    for case in cases() {
        let name = case.required_text("name", "case");
        let grammar = case.required_text("grammar", name);
        let frontend_error = case
            .get("error")
            .filter(|e| e.get("stage").and_then(Json::text) == Some("frontend"));
        if let Some(error) = frontend_error {
            let diagnostic = unlaxer_ubnf::parse(grammar).expect_err(name);
            assert_eq!(
                diagnostic.message,
                error.required_text("message", name),
                "{name}"
            );
            assert_eq!(
                (
                    diagnostic.span.codepoint_start,
                    diagnostic.span.codepoint_end,
                    diagnostic.line,
                    diagnostic.column
                ),
                (
                    number(error, "start"),
                    number(error, "end"),
                    number(error, "line"),
                    number(error, "column")
                ),
                "{name}"
            );
            assert_eq!(diagnostic.kind, unlaxer_ubnf::DiagnosticKind::InvalidValue);
            continue;
        }
        let file = unlaxer_ubnf::parse(grammar).unwrap_or_else(|e| panic!("{name}: {e}"));
        let expected = unlaxer_ubnf::parse(case.required_text("expanded", name)).unwrap();
        assert_eq!(canonical::file(&file), canonical::file(&expected), "{name}");
        let rules = &file.grammars[0].rules;
        for instance in case.get("instances").unwrap().array() {
            let key = instance.required_text("key", name);
            let rule = instance.required_text("name", name);
            assert_eq!(
                format!("{}_{}", &key[..key.find('(').unwrap()], fnv1a64(key)),
                rule,
                "{name}"
            );
            let decl = rules.iter().find(|r| r.name == rule).unwrap();
            assert!(decl.annotations.is_empty(), "{name}");
        }
        for span in case.get("spans").map_or(&[][..], Json::array) {
            let rule = span.required_text("rule", name);
            let decl = rules.iter().find(|r| r.name == rule).unwrap();
            let expected: Vec<usize> = span
                .get("span")
                .unwrap()
                .array()
                .iter()
                .map(|n| match n {
                    Json::Number(n) => *n as usize,
                    _ => unreachable!(),
                })
                .collect();
            assert_eq!(
                vec![decl.span.codepoint_start, decl.span.codepoint_end],
                expected,
                "{name}/{rule}"
            );
        }
        accepted += 1;
    }
    assert_eq!(accepted, 10);
}

#[test]
fn call_sites_keep_their_source_spans() {
    let source = "grammar G { Root ::= Pair('a', Pair('b', 'c')); Pair(l, r) ::= l r; }";
    let snapshot = unlaxer_ubnf::parse_with_source(source).unwrap();
    let rules = &snapshot.ast().grammars[0].rules;
    let root = &rules[0].body.alternatives[0].elements[0].element;
    assert_eq!(snapshot.slice(root.span), Some("Pair('a', Pair('b', 'c'))"));
    // Arguments are expanded before their call, so the inner instance comes first.
    assert_eq!(rules.len(), 3);
    assert_eq!(snapshot.slice(rules[1].span), Some("Pair(l, r) ::= l r;"));
    let outer = &rules[2];
    assert_eq!(snapshot.slice(outer.span), Some("Pair(l, r) ::= l r;"));
    let first = &outer.body.alternatives[0].elements[0].element;
    let second = &outer.body.alternatives[0].elements[1].element;
    assert_eq!(snapshot.slice(first.span), Some("'a'"));
    assert_eq!(snapshot.slice(second.span), Some("Pair('b', 'c')"));
}

fn chain(levels: usize) -> String {
    let mut source = String::from("grammar G { Root ::= C0('a'); ");
    for k in 0..levels {
        source.push_str(&format!("C{k}(x) ::= C{}(x); ", k + 1));
    }
    source.push_str(&format!("C{levels}(x) ::= x; }}"));
    source
}

#[test]
fn the_deepest_allowed_call_chain_expands_on_the_default_test_stack() {
    let limit = unlaxer_ubnf::MAX_EXPANSION_DEPTH;
    let file = unlaxer_ubnf::parse(&chain(limit - 1)).unwrap();
    assert_eq!(file.grammars[0].rules.len(), 1 + limit);
    let error = unlaxer_ubnf::parse(&chain(limit)).unwrap_err();
    assert!(error.message.starts_with("E-PARAM-LIMIT:"), "{error}");
}

#[test]
fn calls_are_scoped_to_the_grammar_that_defines_them() {
    let file = unlaxer_ubnf::parse(include_str!(
        "fixtures/positive/parameterized-rules-two-grammars.ubnf"
    ))
    .unwrap();
    assert_eq!(file.grammars[0].rules.len(), 2);
    // Grammar B has no Name(params) definition: F('b') stays a reference and a group.
    let elements = &file.grammars[1].rules[0].body.alternatives[0].elements;
    assert_eq!(elements.len(), 2);
    assert!(matches!(&elements[0].element.kind, ElementKind::RuleRef { name, .. } if name == "F"));
    assert!(matches!(elements[1].element.kind, ElementKind::Group(_)));
}

#[test]
fn grammars_without_definitions_are_untouched() {
    // An adjacent NAME( without a Name(params) definition stays a reference and a group.
    let file = unlaxer_ubnf::parse("grammar G { Root ::= CAPTUREX('a') Foo('b'); }").unwrap();
    let elements = &file.grammars[0].rules[0].body.alternatives[0].elements;
    assert_eq!(elements.len(), 4);
}
