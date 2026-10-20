//! Value parameters of parameterized rules (#704) against the shared corpus
//! `spec-corpus/call-by-value/cases.json`. Its expected values are written by hand: each
//! accepted grammar has an equivalent written with CAPTURE_SCOPE / CAPTURE / SAME_AS, whose
//! identifier capture names `captures` maps from the generated (unwritable) names.
//! `CallByValueConformanceTest` checks the same file against the Java frontend and both hosts.
use std::path::Path;

#[path = "support/canonical.rs"]
mod canonical;
#[path = "support/json.rs"]
mod json;

use json::Json;
use unlaxer_ubnf::ElementKind;

fn cases() -> Vec<Json> {
    let path =
        Path::new(env!("CARGO_MANIFEST_DIR")).join("../../spec-corpus/call-by-value/cases.json");
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

/// The canonical form with each generated capture name replaced by its hand-written name.
fn renamed(file: &unlaxer_ubnf::UbnfFile, captures: Option<&Json>) -> String {
    let mut text = canonical::file(file);
    if let Some(Json::Object(members)) = captures {
        for (generated, written) in members {
            text = text.replace(
                &canonical::quote(generated),
                &canonical::quote(written.text().unwrap()),
            );
        }
    }
    text
}

#[test]
fn corpus_expansions_equal_hand_written_capture_grammars() {
    let (mut accepted, mut rejected) = (0, 0);
    for case in cases() {
        let name = case.required_text("name", "case");
        let grammar = case.required_text("grammar", name);
        if let Some(error) = case.get("error") {
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
            rejected += 1;
            continue;
        }
        let file = unlaxer_ubnf::parse(grammar).unwrap_or_else(|e| panic!("{name}: {e}"));
        let expected = unlaxer_ubnf::parse(case.required_text("expanded", name)).unwrap();
        let captures = case.get("captures");
        assert_eq!(
            renamed(&file, captures),
            canonical::file(&expected),
            "{name}"
        );
        // The generated names really are the ones the corpus maps.
        if let Some(Json::Object(members)) = captures {
            for generated in members.keys() {
                assert!(
                    canonical::file(&file).contains(&canonical::quote(generated)),
                    "{name}/{generated}"
                );
            }
        }
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
    assert_eq!((accepted, rejected), (8, 33));
}

/// The SAME_AS replacing a use keeps that use's span; synthetic wrappers take the body's.
#[test]
fn value_uses_keep_their_source_spans() {
    let source = "grammar G { Root ::= F('a' 'b'); F(=x) ::= x '-' x; }";
    let snapshot = unlaxer_ubnf::parse_with_source(source).unwrap();
    let rules = &snapshot.ast().grammars[0].rules;
    assert_eq!(rules.len(), 2);
    assert_eq!(snapshot.slice(rules[1].span), Some("F(=x) ::= x '-' x;"));
    let scope = &rules[1].body.alternatives[0].elements[0].element;
    assert_eq!(snapshot.slice(scope.span), Some("x '-' x"));
    let ElementKind::CaptureScope(body) = &scope.kind else {
        panic!("{scope:?}")
    };
    let elements = &body.alternatives[0].elements;
    let ElementKind::OwnedCapture { name, body: value } = &elements[0].element.kind else {
        panic!("{elements:?}")
    };
    assert_eq!(name, "F(=x)");
    // The value is the argument, grouped as for a by-name parameter, with its own span.
    let argument = &value.alternatives[0].elements[0].element;
    assert!(matches!(argument.kind, ElementKind::Group(_)));
    assert_eq!(snapshot.slice(argument.span), Some("'a' 'b'"));
    let uses: Vec<_> = [1, 3]
        .iter()
        .map(|&i| snapshot.slice(elements[i].element.span).unwrap())
        .collect();
    assert_eq!(uses, ["x", "x"]);
    assert!(matches!(&elements[3].element.kind, ElementKind::Replay { name } if name == "F(=x)"));
    let body = source.find("x '-' x").unwrap();
    assert_eq!(elements[1].element.span.codepoint_start, body);
    assert_eq!(elements[3].element.span.codepoint_start, body + 6);
}

/// 2S + 4 elements: the S-element argument at the call, then the instance body.
#[test]
fn the_element_limit_counts_the_capture_wrappers_exactly() {
    let limit = unlaxer_ubnf::MAX_EXPANDED_ELEMENTS;
    let grammar = |size: usize| {
        format!(
            "grammar G {{ Root ::= F({}); F(=x) ::= 'b'; }}",
            vec!["'a'"; size].join(" ")
        )
    };
    let size = (limit - 4) / 2;
    let file = unlaxer_ubnf::parse(&grammar(size)).unwrap();
    assert_eq!(file.grammars[0].rules.len(), 2);
    let error = unlaxer_ubnf::parse(&grammar(size + 1)).unwrap_err();
    assert_eq!(
        error.message,
        "E-PARAM-LIMIT: expanded parameterized rules exceed 65536 elements"
    );
}

fn value_chain(n: usize) -> String {
    let mut source = String::from("grammar G { Root ::= C0('a'); ");
    for k in 0..n {
        source.push_str(&format!("C{k}(=x) ::= C{}(x); ", k + 1));
    }
    source.push_str(&format!("C{n}(=x) ::= x; }}"));
    source
}

/// Each value instance adds its body and its CAPTURE_SCOPE body: C{k} is created at
/// 1 + 2k open bodies and nests 3 more, so C0..C62 reach 128 and C63 is refused.
#[test]
fn value_chains_stop_at_the_inline_nesting_limit_on_the_default_test_stack() {
    let n = (unlaxer_ubnf::MAX_EXPANDED_NESTING - 4) / 2;
    let file = unlaxer_ubnf::parse(&value_chain(n)).unwrap();
    assert_eq!(file.grammars[0].rules.len(), n + 2);
    let error = unlaxer_ubnf::parse(&value_chain(n + 1)).unwrap_err();
    assert_eq!(
        error.message,
        format!(
            "E-PARAM-LIMIT: expansion of rule C{} nests {} levels; the limit is 128",
            n + 1,
            2 * (n + 1) + 4
        )
    );
}

/// A value parameter replays one element, so doubling its uses does not grow arguments.
#[test]
fn value_uses_do_not_multiply_argument_size() {
    let mut source = String::from("grammar G { Root ::= D40('a'); D0(=x) ::= x x; ");
    for k in 1..=40 {
        source.push_str(&format!("D{k}(=x) ::= D{}(x x); ", k - 1));
    }
    source.push('}');
    let file = unlaxer_ubnf::parse(&source).unwrap();
    assert_eq!(file.grammars[0].rules.len(), 42);
}

/// No capture name written in UBNF can spell a generated one.
#[test]
fn generated_capture_names_cannot_be_written() {
    for source in [
        "grammar G { Root ::= CAPTURE(F(=x), 'a'); }",
        "grammar G { Root ::= SAME_AS(F(=x)); }",
    ] {
        let error = unlaxer_ubnf::parse(source).unwrap_err();
        assert_eq!(error.kind, unlaxer_ubnf::DiagnosticKind::Syntax, "{source}");
    }
}

/// Definitions without `=` expand exactly as before (#701).
#[test]
fn by_name_parameters_are_unchanged() {
    let by_name = unlaxer_ubnf::parse("grammar G { Root ::= F('a'); F(x) ::= x x; }").unwrap();
    let hand = unlaxer_ubnf::parse(&format!(
        "grammar G {{ Root ::= F_{0}; F_{0} ::= 'a' 'a'; }}",
        fnv1a64("F(t(61))")
    ))
    .unwrap();
    assert_eq!(canonical::file(&by_name), canonical::file(&hand));
}

/// Captures that never run inside a value scope stay legal: relayed outside the value
/// call, discarded by the callee, an unused by-name argument, or by-name rules only.
const OUTSIDE_VALUE_SCOPES: [&str; 6] = [
    "grammar G { Root ::= A(CAPTURE(u, 'b')) SAME_AS(u); A(y) ::= y F('a'); F(=x) ::= x; }",
    "grammar G { Root ::= H2(CAPTURE(u, 'b')) F('a') SAME_AS(u); F(=x) ::= x; H2(w) ::= w; }",
    "grammar G { Root ::= A(CAPTURE(u, 'b')) SAME_AS(u); A(y) ::= y F(Drop(y)); F(=x) ::= x; Drop(w) ::= 'c'; }",
    "grammar G { Root ::= A(CAPTURE(u, 'b')) SAME_AS(u); A(y) ::= y F('a', y); F(=x, z) ::= x; }",
    "grammar G { Root ::= A(CAPTURE(u, 'b')) SAME_AS(u); A(y) ::= B(y); B(y) ::= y; }",
    "grammar G { Root ::= A(CAPTURE(u, 'b')) SAME_AS(u); A(y) ::= H(y) F('a'); H(z) ::= z; F(=x) ::= x; }",
];

#[test]
fn captures_outside_value_scopes_are_accepted() {
    for source in OUTSIDE_VALUE_SCOPES {
        unlaxer_ubnf::parse(source).unwrap_or_else(|e| panic!("{source}: {e}"));
    }
}

/// `grammar G { d1; d2; ... }` split into its declarations (test grammars only: no `;` or
/// braces inside literals).
fn declarations(source: &str) -> (String, Vec<String>) {
    let open = source.find('{').unwrap() + 1;
    let close = source.rfind('}').unwrap();
    let decls = source[open..close]
        .split(';')
        .map(str::trim)
        .filter(|d| !d.is_empty())
        .map(|d| format!("{d};"))
        .collect();
    (source[..open].to_owned(), decls)
}

/// The outcome with the error position given as (declaration, offset in it), so that it
/// can be compared across orders of the same declarations.
fn outcome(head: &str, decls: &[String]) -> Result<(), (String, String, usize)> {
    let mut source = head.to_owned();
    let mut starts = Vec::new();
    for decl in decls {
        source.push(' ');
        starts.push(source.chars().count());
        source.push_str(decl);
    }
    source.push_str(" }");
    unlaxer_ubnf::parse(&source).map(|_| ()).map_err(|e| {
        let at = e.span.codepoint_start;
        let k = starts.iter().rposition(|&s| s <= at).unwrap();
        (e.message, decls[k].clone(), at - starts[k])
    })
}

/// Reordering the declarations (so that instances are created, and shared, in another
/// order) changes neither the verdict nor the reported CAPTURE.
#[test]
fn reordering_declarations_does_not_change_the_result() {
    let mut sources: Vec<String> = OUTSIDE_VALUE_SCOPES.iter().map(|s| s.to_string()).collect();
    for case in cases() {
        let code = case
            .get("error")
            .and_then(|e| e.get("code"))
            .and_then(Json::text);
        if code == Some("E-PARAM-CAPTURE") {
            sources.push(case.required_text("grammar", "case").to_owned());
        }
    }
    assert!(sources.len() > 20);
    for source in &sources {
        let (head, decls) = declarations(source);
        let expected = outcome(&head, &decls);
        let n = decls.len();
        let mut orders: Vec<Vec<String>> = vec![decls.iter().rev().cloned().collect()];
        for shift in 1..n {
            orders.push(
                decls[shift..]
                    .iter()
                    .chain(&decls[..shift])
                    .cloned()
                    .collect(),
            );
        }
        for order in orders {
            assert_eq!(outcome(&head, &order), expected, "{source}\n{order:?}");
        }
    }
}

/// `Root ::= D0('b'); Dk(y) ::= Ek(D(k+1)(y)) Ek(y); Ek(=x) ::= x;`, or by name.
fn fan(n: usize, by_value: bool) -> String {
    let mut source = String::from("grammar G { Root ::= D0('b'); ");
    let marker = if by_value { "=" } else { "" };
    for k in 0..n {
        source.push_str(&format!(
            "D{k}(y) ::= E{k}(D{}(y)) E{k}(y); E{k}({marker}x) ::= x; ",
            k + 1
        ));
    }
    source.push_str(&format!("D{n}(y) ::= y; }}"));
    source
}

/// The value-scope analysis keeps one rule per scope set, so a wide fan of value calls
/// costs about what the same grammar costs by name (review of #711: merging whole sets was
/// quadratic, 34 s and 8 GB at this size in release). The bound is loose against load.
#[test]
fn value_scope_analysis_stays_linear_on_a_wide_fan() {
    let mut seconds = Vec::new();
    for by_value in [true, false] {
        let source = fan(16_000, by_value);
        let start = std::time::Instant::now();
        let error = unlaxer_ubnf::parse(&source).unwrap_err();
        seconds.push(start.elapsed().as_secs_f64());
        assert!(error.message.starts_with("E-PARAM-LIMIT:"), "{error}");
    }
    assert!(seconds[0] < 10.0 * seconds[1] + 2.0, "{seconds:?}");
}
