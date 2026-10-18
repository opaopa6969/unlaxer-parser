use unlaxer_runtime::token::TokenKind;
use unlaxer_runtime::{
    capture_tree::{self, Element, Kind},
    Diagnostics, Expr, Memoization, ParseContext, ParseOptions, ParseResult,
};
fn decode(value: &str) -> String {
    if value == "~" {
        String::new()
    } else {
        String::from_utf8(
            value
                .as_bytes()
                .chunks_exact(2)
                .map(|p| u8::from_str_radix(std::str::from_utf8(p).unwrap(), 16).unwrap())
                .collect(),
        )
        .unwrap()
    }
}
fn hex(value: &str) -> String {
    use std::fmt::Write;
    let mut result = String::new();
    for b in value.bytes() {
        write!(result, "{b:02x}").unwrap();
    }
    if result.is_empty() {
        "~".into()
    } else {
        result
    }
}
fn canonical(value: &Element) -> String {
    let kind = match value.kind() {
        Kind::Value => "V",
        Kind::Optional => "O",
        Kind::List => "L",
        Kind::Separator => unreachable!(),
    };
    format!(
        "{kind}@{}:{}:{}[{}]",
        value.span().start,
        value.span().end,
        hex(value.text()),
        value
            .children()
            .iter()
            .map(canonical)
            .collect::<Vec<_>>()
            .join(",")
    )
}
fn effect(c: &mut ParseContext<'_>) -> ParseResult {
    c.set_state("value", c.state::<u32>("value").unwrap() + 1);
    let punctuation = if *c.state::<bool>("empty").unwrap() {
        ""
    } else {
        ","
    };
    c.parse(&if punctuation.is_empty() {
        Expr::Eof
    } else {
        Expr::Literal(punctuation)
    })
}
fn boundary(kind: Kind, child: Expr) -> Expr {
    Expr::CaptureBoundary(kind, Box::new(child))
}
#[test]
fn shared_separator_values_state_and_rollback_oracle() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for row in include_str!("../../../conformance/nested-separated/runtime.tsv")
                .lines()
                .filter(|r| !r.starts_with('#'))
            {
                let e: Vec<_> = row.split('\t').collect();
                let input = decode(e[2]);
                let mut c = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                c.set_state("value", 7u32);
                c.set_state("empty", e[1] == "empty");
                let item = (if e[1] == "empty" {
                    Expr::Eof
                } else {
                    Expr::Literal("a")
                })
                .capture_owned("item")
                .capture_value();
                let mut separator = boundary(
                    Kind::Separator,
                    Expr::Custom(effect)
                        .capture_owned("sep")
                        .capture_list(0, None)
                        .capture_optional(),
                );
                if e[1] == "nested-mask" {
                    separator = boundary(Kind::Separator, separator);
                }
                let mut list = boundary(Kind::List, item.separated_by_java(separator));
                if e[1] == "scope" {
                    list = Expr::CaptureScope(Box::new(list));
                }
                if e[1] == "choice" {
                    list = boundary(
                        Kind::List,
                        Expr::choice([
                            Expr::sequence([list, Expr::Literal("!")]),
                            Expr::literal(Box::leak(input.clone().into_boxed_str()))
                                .capture_value(),
                        ]),
                    );
                }
                assert!(c
                    .parse_with_token_mode(
                        &list.clone().repeat_java(2, Some(2)),
                        unlaxer_runtime::token::TokenKind::Consumed,
                        false
                    )
                    .is_err());
                assert_eq!(c.state::<u32>("value"), Some(&7));
                assert_eq!((c.position(), c.matched_position()), (0, 0));
                assert!(c.captured_text("item").is_none());
                assert!(c.captured_text("sep").is_none());
                assert!(c.node(0).is_none());
                let rollback: Result<(), _> = c.transaction(|c| {
                    let result = c.parse_with_token_mode(
                        &list,
                        unlaxer_runtime::token::TokenKind::Consumed,
                        false,
                    );
                    assert_eq!(result.is_ok(), e[3] == "true", "{}", e[0]);
                    assert_eq!(
                        (c.position(), c.matched_position()),
                        (e[4].parse().unwrap(), e[5].parse().unwrap()),
                        "{}",
                        e[0]
                    );
                    assert_eq!(
                        *c.state::<u32>("value").unwrap(),
                        e[6].parse::<u32>().unwrap(),
                        "{}",
                        e[0]
                    );
                    for (i, name) in [(7, "item"), (8, "sep")] {
                        let expected = if e[i] == "-" {
                            None
                        } else {
                            Some(decode(e[i]))
                        };
                        assert_eq!(c.captured_text(name), expected.as_deref(), "{}", e[0]);
                    }
                    if let Ok(parsed) = result {
                        let tree = c.tree(parsed.nodes[0]).unwrap();
                        let snapshot = capture_tree::snapshot(&tree, parsed.nodes[0]).unwrap();
                        assert_eq!(canonical(&snapshot), e[9], "{}", e[0]);
                        let masks = tree
                            .nodes
                            .iter()
                            .enumerate()
                            .filter(|(_, n)| Kind::of_rule(n.rule) == Some(Kind::Separator))
                            .collect::<Vec<_>>();
                        for (id, node) in &masks {
                            assert!(!node.children.is_empty());
                            assert_eq!(
                                capture_tree::snapshot(&tree, *id),
                                Err(capture_tree::Error::NotBoundary)
                            );
                        }
                        if e[1] == "plain" {
                            assert_eq!(masks.len(), 1, "{}", e[0]);
                        }
                        if e[1] == "nested-mask" {
                            assert_eq!(masks.len(), 2, "{}", e[0]);
                        }
                        drop(tree);
                        assert_eq!(canonical(&snapshot), e[9]);
                    }
                    Err(c.error("force rollback"))
                });
                assert!(rollback.is_err());
                assert_eq!(c.state::<u32>("value"), Some(&7));
                assert_eq!((c.position(), c.matched_position()), (0, 0));
                assert!(c.captured_text("item").is_none());
                assert!(c.captured_text("sep").is_none());
                assert!(c.node(0).is_none());
            }
        }
    }
}

thread_local! {static ITEMS:std::cell::Cell<usize>=const{std::cell::Cell::new(0)};}
fn recursive(c: &mut ParseContext<'_>) -> ParseResult {
    c.parse(&Expr::Custom(recursive))
}
fn resource(c: &mut ParseContext<'_>) -> ParseResult {
    c.set_state("value", 99u32);
    c.parse(&Expr::Eof.repeat_java(0, Some(0)).capture_owned("effect"))?;
    c.parse(&Expr::Custom(recursive))
}
fn resource_item(c: &mut ParseContext<'_>) -> ParseResult {
    let count = ITEMS.with(|v| {
        v.set(v.get() + 1);
        v.get()
    });
    let phase = c.state::<String>("phase").unwrap();
    if phase == "first" || phase == "tail" && count > 1 {
        resource(c)
    } else {
        c.parse(&Expr::Eof.repeat_java(0, Some(0)))
    }
}
#[test]
fn resource_abort_across_every_separated_position_remains_sticky_and_restores_publication() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for row in include_str!("../../../conformance/nested-separated/resource.tsv")
                .lines()
                .filter(|r| !r.starts_with('#'))
            {
                let e: Vec<_> = row.split('\t').collect();
                let input = decode(e[1]);
                let mut c = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                c.set_state("value", 7u32);
                c.set_state("phase", e[0].to_owned());
                ITEMS.with(|v| v.set(0));
                c.parse_with_token_mode(
                    &Expr::Literal("p").capture_owned("old"),
                    unlaxer_runtime::token::TokenKind::Consumed,
                    false,
                )
                .unwrap();
                c.parse_with_token_mode(
                    &Expr::Literal("😀"),
                    unlaxer_runtime::token::TokenKind::MatchOnly,
                    false,
                )
                .unwrap();
                let zero = Expr::Eof.repeat_java(0, Some(0));
                let mask = boundary(
                    Kind::Separator,
                    if e[0] == "separator" {
                        Expr::Custom(resource)
                    } else {
                        zero.clone()
                    },
                );
                let list = boundary(
                    Kind::List,
                    Expr::Custom(resource_item)
                        .capture_value()
                        .separated_by_java(mask),
                );
                c.parse(&list.clone().repeat_java(0, Some(0))).unwrap();
                assert_eq!(ITEMS.with(std::cell::Cell::get), 0);
                let error = c.parse(&list).unwrap_err();
                assert!(error.expected.iter().any(|v| v.contains("below")));
                assert_eq!(c.parse(&zero).unwrap_err(), error);
                assert_eq!(
                    (c.position(), c.matched_position()),
                    (e[2].parse().unwrap(), e[3].parse().unwrap())
                );
                assert_eq!(
                    *c.state::<u32>("value").unwrap(),
                    e[4].parse::<u32>().unwrap()
                );
                assert_eq!(c.captured_text("old"), Some("p"));
                assert!(c.captured_text("effect").is_none());
                assert!(c.node(2).is_none());
            }
        }
    }
}

fn provenance_token(c: &mut ParseContext<'_>) -> ParseResult {
    use unlaxer_runtime::{
        token::{TokenKind, TokenSource},
        ParseMatch, Span, TOKEN_LEAF_RULE,
    };
    c.declare_complete_token_trace();
    let projected = *c.state::<bool>("projected").unwrap();
    let source = if projected {
        TokenSource::Projected {
            span: Span { start: 1, end: 3 },
            text: std::sync::Arc::from("bb"),
        }
    } else {
        TokenSource::Generated {
            anchor: 0,
            text: "bb".into(),
        }
    };
    let span = if projected {
        Span { start: 1, end: 3 }
    } else {
        Span { start: 0, end: 0 }
    };
    let node = c
        .add_token_node(TOKEN_LEAF_RULE, TokenKind::VirtualConsumed, source)
        .unwrap();
    if projected {
        assert!(c.advance(1));
    }
    Ok(ParseMatch {
        span,
        nodes: vec![node.index()],
        captures: vec![],
    })
}
#[test]
fn opaque_separator_does_not_destroy_general_projected_or_generated_snapshots() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for row in include_str!("../../../conformance/nested-separated/provenance.tsv")
                .lines()
                .filter(|r| !r.starts_with('#'))
            {
                let e: Vec<_> = row.split('\t').collect();
                let input = decode(e[1]);
                let mut c = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                c.set_state("projected", e[0] == "projected");
                let expression = boundary(
                    Kind::Separator,
                    Expr::Custom(provenance_token).capture_value(),
                );
                let parsed = c
                    .parse_with_token_mode(
                        &expression,
                        unlaxer_runtime::token::TokenKind::VirtualConsumed,
                        false,
                    )
                    .unwrap();
                assert_eq!(
                    (c.position(), c.matched_position()),
                    (e[2].parse().unwrap(), e[3].parse().unwrap())
                );
                let tree = c.tree(parsed.nodes[0]).unwrap();
                let values = tree
                    .nodes
                    .iter()
                    .enumerate()
                    .filter(|(_, n)| Kind::of_rule(n.rule) == Some(Kind::Value))
                    .collect::<Vec<_>>();
                assert_eq!(values.len(), 1);
                let snapshot = capture_tree::snapshot(&tree, values[0].0).unwrap();
                assert_eq!(canonical(&snapshot), e[5]);
                assert_eq!(snapshot.generated(), e[4] == "true");
                assert_eq!(
                    capture_tree::snapshot(&tree, parsed.nodes[0]),
                    Err(capture_tree::Error::NotBoundary)
                );
            }
        }
    }
}

fn memo_driver(c: &mut ParseContext<'_>) -> ParseResult {
    let expected = include_str!("../../../conformance/nested-separated/runtime.tsv")
        .lines()
        .find(|r| r.starts_with("normal\t"))
        .unwrap()
        .split('\t')
        .nth(9)
        .unwrap();
    let rollback: Result<(), _> = c.transaction(|c| {
        let first = c.parse(&Expr::Rule(1))?;
        let mut tree = c.tree(first.nodes[0]).unwrap();
        let root = tree.nodes[first.nodes[0]].children[0];
        assert_eq!(
            canonical(&capture_tree::snapshot(&tree, root).unwrap()),
            expected
        );
        tree.nodes[root].children.clear();
        Err(unlaxer_runtime::ParseError {
            offset: 0,
            expected: vec![],
        })
    });
    assert!(rollback.is_err());
    assert_eq!((c.position(), c.matched_position()), (0, 0));
    let second = c.parse(&Expr::Rule(1))?;
    let tree = c.tree(second.nodes[0]).unwrap();
    let root = tree.nodes[second.nodes[0]].children[0];
    assert_eq!(
        canonical(&capture_tree::snapshot(&tree, root).unwrap()),
        expected
    );
    assert_eq!(
        c.memoized_success_hits() > 0,
        *c.state::<bool>("enabled").unwrap()
    );
    assert_eq!((c.position(), c.matched_position()), (3, 3));
    Ok(second)
}
#[test]
fn memo_success_replays_opaque_separator_and_copies_original_boundaries() {
    let grammar = unlaxer_runtime::share_grammar(vec![
        unlaxer_runtime::Rule {
            name: "driver",
            expression: Expr::Custom(memo_driver),
        },
        unlaxer_runtime::Rule {
            name: "list",
            expression: boundary(
                Kind::List,
                Expr::Literal("a")
                    .capture_value()
                    .separated_by_java(boundary(
                        Kind::Separator,
                        Expr::Literal(",").capture_value(),
                    )),
            ),
        },
    ]);
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let mut c = ParseContext::with_options(
                "a,a",
                ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
            );
            c.set_state("enabled", memo == Memoization::SafeFailures);
            c.parse_shared_grammar(&grammar, 0, false).unwrap();
        }
    }
}

#[test]
fn separated_failure_reset_same_context_fallback_and_outer_rollback_match_shared_oracle() {
    let rows: Vec<_> = include_str!("../../../conformance/nested-separated/reset.tsv")
        .lines()
        .filter(|row| !row.starts_with('#') && !row.is_empty())
        .collect();
    assert_eq!(rows.len(), 8);
    let mut actual = Vec::new();
    let mut expected = Vec::new();
    for (memo, memo_name) in [
        (Memoization::Off, "OFF"),
        (Memoization::SafeFailures, "SAFE_FAILURES"),
    ] {
        for (diagnostics, diagnostic_name) in [
            (Diagnostics::Auto, "AUTO"),
            (Diagnostics::Detailed, "DETAILED"),
            (Diagnostics::DetailedOnFailure, "DETAILED_ON_FAILURE"),
        ] {
            for row in &rows {
                let f: Vec<_> = row.split('\t').collect();
                let label = format!("{}\t{}\t{memo_name}\t{diagnostic_name}", f[1], f[2]);
                let input = decode(f[3]);
                let mut c = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                c.set_reset_matched_with_consumed(f[2] == "true");
                let item = || Expr::InputCaptureBoundary(Kind::Value, Box::new(Expr::literal("a")));
                let parser = Expr::InputCaptureBoundary(
                    Kind::List,
                    Box::new(Expr::sequence([
                        item(),
                        Expr::sequence([boundary(Kind::Separator, Expr::literal(",")), item()])
                            .repeat_java(0, None),
                    ])),
                );
                let mut inner = String::new();
                let rollback: Result<(), _> = c.transaction(|c| {
                    let kind = match f[1] {
                        "consumed" => TokenKind::Consumed,
                        "matchOnly" => TokenKind::MatchOnly,
                        "virtualTokenConsumed" => TokenKind::VirtualConsumed,
                        "virtualTokenMatchOnly" => TokenKind::VirtualMatchOnly,
                        _ => panic!("unknown kind"),
                    };
                    let parsed = c.parse_with_token_mode(&parser, kind, false);
                    inner = format!(
                        "{}\t{}\t{}\t{}\t{}",
                        parsed.is_ok(),
                        c.position(),
                        c.matched_position(),
                        c.reset_matched_with_consumed(),
                        c.node(0).is_none()
                    );
                    let fallback = c.parse(&Expr::literal("?"));
                    inner.push_str(&format!(
                        "\t{}\t{}\t{}",
                        fallback.is_ok(),
                        c.position(),
                        c.matched_position()
                    ));
                    Err(c.error("force parent rollback"))
                });
                assert!(rollback.is_err());
                actual.push(format!(
                    "{label}\t{inner}\t{}\t{}\t{}\t{}",
                    c.position(),
                    c.matched_position(),
                    c.reset_matched_with_consumed(),
                    c.node(0).is_none()
                ));
                expected.push(format!(
                    "{label}\t{}\t{}\t{}\t{}\ttrue\t{}\t{}\t{}\t0\t0\t{}\ttrue",
                    f[4], f[5], f[6], f[7], f[9], f[10], f[11], f[8]
                ));
            }
        }
    }
    let output = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../target/nested-separated-reset-rust.tsv");
    std::fs::create_dir_all(output.parent().unwrap()).unwrap();
    std::fs::write(output, actual.join("\n") + "\n").unwrap();
    assert_eq!(actual.len(), 48);
    assert_eq!(
        actual, expected,
        "all observations recorded before checking reset contract"
    );
}

#[test]
fn separator_snapshot_visit_depth_limits_and_opaque_mapper_validation_match_shared_oracle() {
    fn node(tree: &mut unlaxer_runtime::Tree, rule: usize, children: Vec<usize>) -> usize {
        let id = tree.nodes.len();
        tree.nodes.push(unlaxer_runtime::Node {
            rule,
            span: unlaxer_runtime::Span { start: 0, end: 0 },
            children,
            captures: vec![],
        });
        id
    }
    fn boundary_rule(kind: Kind) -> usize {
        let mut context = ParseContext::new("");
        let parsed = context
            .parse(&Expr::CaptureBoundary(kind, Box::new(Expr::Eof)))
            .unwrap();
        context.node(parsed.nodes[0]).unwrap().rule
    }
    let value_rule = boundary_rule(Kind::Value);
    let separator_rule = boundary_rule(Kind::Separator);
    let list_rule = boundary_rule(Kind::List);
    let mut actual = Vec::new();
    let mut expected = Vec::new();
    for row in include_str!("../../../conformance/nested-separated/snapshot-limits.tsv")
        .lines()
        .filter(|r| !r.starts_with('#'))
    {
        let e: Vec<_> = row.split('\t').collect();
        let size: usize = e[2].parse().unwrap();
        let mut tree = unlaxer_runtime::parse(
            &[unlaxer_runtime::Rule {
                name: "root",
                expression: Expr::Eof,
            }],
            0,
            false,
            "",
        )
        .unwrap();
        tree.nodes.clear();
        let mut children = Vec::new();
        match e[1] {
            "visits" => {
                children.push(node(&mut tree, value_rule, vec![]));
                for _ in 0..size {
                    children.push(node(&mut tree, separator_rule, vec![]));
                }
            }
            "depth" => {
                let mut child = node(&mut tree, separator_rule, vec![]);
                for _ in 0..size {
                    child = node(&mut tree, 0, vec![child]);
                }
                children.push(child);
            }
            "opaque" => {
                let value = node(&mut tree, value_rule, vec![]);
                let mut child = value;
                for _ in 0..size {
                    child = node(&mut tree, 0, vec![child]);
                }
                let separator = node(&mut tree, separator_rule, vec![child]);
                children.push(node(&mut tree, value_rule, vec![]));
                children.push(separator);
                assert_eq!(tree.nodes[separator].children, vec![child]);
                assert_eq!(
                    canonical(&capture_tree::snapshot(&tree, value).unwrap()),
                    "V@0:0:~[]"
                );
            }
            _ => panic!("unknown shape"),
        }
        tree.root = node(&mut tree, list_rule, children);
        let snapshot = match capture_tree::snapshot(&tree, tree.root) {
            Ok(value) => {
                assert_eq!(value.text(), "");
                assert_eq!(value.span(), unlaxer_runtime::Span { start: 0, end: 0 });
                format!("OK:{}", value.children().len())
            }
            Err(capture_tree::Error::Limit) => "LIMIT".into(),
            Err(error) => panic!("unexpected snapshot error {error:?}"),
        };
        let guard = match unlaxer_runtime::mapping::Context::new(&tree, 1) {
            Ok(_) => "OK".into(),
            Err(error) => error.split(':').next().unwrap().to_owned(),
        };
        actual.push(format!("{}\t{snapshot}\t{guard}", e[0]));
        expected.push(format!("{}\t{}\t{}", e[0], e[3], e[4]));
    }
    let output = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../target/nested-separated-snapshot-limits-rust.tsv");
    std::fs::write(output, actual.join("\n") + "\n").unwrap();
    assert_eq!(actual.len(), 5);
    assert_eq!(actual, expected);
}
