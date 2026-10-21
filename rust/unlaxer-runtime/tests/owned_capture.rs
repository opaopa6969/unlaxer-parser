use std::fmt::Write as _;
use unlaxer_runtime::{token::TokenKind, Expr, ParseContext};

#[test]
fn projected_capture_keeps_the_child_text_instead_of_slicing_the_input() {
    let mut context = ParseContext::new("abcbb");
    context.set_reset_matched_with_consumed(false);
    context
        .parse_with_token_mode(&Expr::Literal("a"), TokenKind::MatchOnly, false)
        .unwrap();
    context
        .parse_with_token_mode(
            &Expr::sequence([Expr::Literal("b"), Expr::Literal("b")]).capture_owned("value"),
            TokenKind::VirtualConsumed,
            false,
        )
        .unwrap();
    context
        .parse_with_token_mode(&Expr::Literal("c"), TokenKind::Consumed, false)
        .unwrap();
    context
        .parse_with_token_mode(&Expr::Replay("value"), TokenKind::Consumed, false)
        .unwrap();
    assert_eq!(context.position(), 5);
}

use unlaxer_runtime::{
    token::TokenSource, Diagnostics, Memoization, ParseMatch, ParseOptions, ParseResult, Span,
};
fn decode(s: &str) -> String {
    if s == "~" {
        String::new()
    } else {
        String::from_utf8(
            s.as_bytes()
                .chunks_exact(2)
                .map(|b| u8::from_str_radix(std::str::from_utf8(b).unwrap(), 16).unwrap())
                .collect(),
        )
        .unwrap()
    }
}
fn w(s: &'static str) -> Expr {
    Expr::Literal(s)
}
fn cap(child: Expr) -> Expr {
    child.capture_owned("value")
}
fn replay() -> Expr {
    Expr::Replay("value")
}
fn generated(c: &mut ParseContext<'_>) -> ParseResult {
    c.declare_complete_token_trace();
    let at = c.position();
    let id = c
        .add_token_node(
            unlaxer_runtime::TOKEN_LEAF_RULE,
            TokenKind::VirtualConsumed,
            TokenSource::Generated {
                anchor: at,
                text: "x".into(),
            },
        )
        .unwrap();
    Ok(ParseMatch {
        span: Span { start: at, end: at },
        nodes: vec![id.index()],
        captures: vec![],
    })
}
fn parse(c: &mut ParseContext<'_>, roots: &mut Vec<usize>, e: Expr) {
    let matched = c
        .parse_with_token_mode(&e, TokenKind::Consumed, false)
        .unwrap();
    roots.extend(matched.nodes);
}
fn scenario(
    c: &mut ParseContext<'_>,
    roots: &mut Vec<usize>,
    name: &str,
    k: TokenKind,
    inv: bool,
) -> ParseResult {
    match name {
        "input" => parse(c, roots, cap(w("a"))),
        "unicode" => parse(c, roots, cap(w("😀"))),
        "combining" => parse(c, roots, cap(w("e\u{301}"))),
        "family" => parse(c, roots, cap(w("👩‍👩‍👧‍👦"))),
        "crlf" => parse(c, roots, cap(w("a\r\n😀"))),
        "inverted" => parse(c, roots, cap(w("ab"))),
        "empty" => parse(c, roots, cap(Expr::sequence([]))),
        "generated" => parse(c, roots, cap(Expr::Custom(generated))),
        "generated-prefix" => {
            parse(c, roots, cap(w("p")));
            parse(c, roots, cap(Expr::Custom(generated)));
        }
        "projected" => {
            c.set_reset_matched_with_consumed(false);
            roots.extend(
                c.parse_with_token_mode(&w("a"), TokenKind::MatchOnly, false)
                    .unwrap()
                    .nodes,
            );
            roots.extend(
                c.parse_with_token_mode(
                    &cap(Expr::sequence([w("b"), w("b")])),
                    TokenKind::VirtualConsumed,
                    false,
                )
                .unwrap()
                .nodes,
            );
            parse(c, roots, w("c"));
        }
        "prior-failure" => {
            parse(c, roots, cap(w("a")));
            roots.extend(
                c.parse_with_token_mode(&w("bc"), TokenKind::MatchOnly, false)
                    .unwrap()
                    .nodes,
            );
        }
        "consume-stopper" => {
            parse(c, roots, cap(w("a")));
            return c.parse_with_token_mode(&replay().stop_consume().match_only(), k, inv);
        }
        "invert-stopper" => {
            parse(c, roots, cap(w("ab")));
            return c.parse_with_token_mode(&replay().stop_invert().invert_match(), k, inv);
        }
        "undefined" => {}
        "cap-peg" => return c.parse_with_token_mode(&cap(w("a").ahead()), k, inv),
        "virtual-empty" => {
            parse(c, roots, cap(w("p")));
            return c.parse_with_token_mode(&cap(Expr::sequence([])), k, inv);
        }
        "shadow" => {
            parse(c, roots, cap(w("a")));
            parse(
                c,
                roots,
                Expr::sequence([cap(w("b")), replay()]).capture_scope(),
            );
        }
        "read-scope" => {
            parse(c, roots, cap(w("a")));
            parse(c, roots, replay().capture_scope());
        }
        "local" => parse(c, roots, cap(w("a")).capture_scope()),
        "nested" => {
            parse(c, roots, cap(w("a")));
            parse(
                c,
                roots,
                Expr::sequence([cap(w("b")), cap(w("c")).capture_scope(), replay()])
                    .capture_scope(),
            );
        }
        "scope-failure" => {
            parse(c, roots, cap(w("a")));
            return c.parse_with_token_mode(
                &Expr::sequence([cap(w("b")), w("z")]).capture_scope(),
                TokenKind::Consumed,
                false,
            );
        }
        "match-only" => {
            parse(c, roots, cap(w("a")));
            return c.parse_with_token_mode(&cap(w("b")).match_only(), k, inv);
        }
        "match-new" => return c.parse_with_token_mode(&cap(w("a")).match_only(), k, inv),
        "peg" => return c.parse_with_token_mode(&cap(w("a")).ahead(), k, inv),
        "choice" => {
            return c.parse_with_token_mode(
                &Expr::choice([Expr::sequence([cap(w("a")), w("x")]), cap(w("a"))]),
                k,
                inv,
            )
        }
        "retry" => {
            parse(c, roots, cap(w("a")));
            assert!(c
                .parse_with_token_mode(
                    &Expr::sequence([cap(w("a")), w("z")]).capture_scope(),
                    k,
                    inv
                )
                .is_err());
        }
        _ => panic!("{name}"),
    }
    c.parse_with_token_mode(&replay(), k, inv)
}
fn kind(s: &str) -> TokenKind {
    match s {
        "consumed" => TokenKind::Consumed,
        "matchOnly" => TokenKind::MatchOnly,
        "virtualTokenConsumed" => TokenKind::VirtualConsumed,
        "virtualTokenMatchOnly" => TokenKind::VirtualMatchOnly,
        _ => panic!("{s}"),
    }
}
#[test]
fn shared_independent_oracle() {
    let rows = include_str!("../../../conformance/owned-capture/cases.tsv");
    let mut failures = Vec::new();
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostic in [
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
            Diagnostics::Auto,
        ] {
            for row in rows
                .lines()
                .filter(|r| !r.starts_with('#') && !r.is_empty())
            {
                let e: Vec<_> = row.split('\t').collect();
                let input = decode(e[1]);
                let mut c = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostic),
                );
                let mut roots = Vec::new();
                let result = scenario(&mut c, &mut roots, e[2], kind(e[3]), e[4] == "true");
                if let Ok(matched) = &result {
                    roots.extend(matched.nodes.iter().copied());
                }
                let label = format!("{}/{memo:?}/{diagnostic:?}", e[0]);
                let mut check = |actual: String, expected: &str| {
                    if actual != expected {
                        failures.push(format!("{label}: actual {actual:?}, expected {expected:?}"));
                    }
                };
                check(result.is_ok().to_string(), e[5]);
                check(c.position().to_string(), e[6]);
                check(c.matched_position().to_string(), e[7]);
                let expected_value = if e[8] == "-" {
                    "<undefined>".to_owned()
                } else {
                    decode(e[8])
                };
                check(
                    c.captured_text("value")
                        .map(str::to_owned)
                        .unwrap_or_else(|| "<undefined>".into()),
                    &expected_value,
                );
                if let Some(source) = c.captured_source("value") {
                    let (p, span) = match source {
                        TokenSource::Input(s) => ("INPUT", *s),
                        TokenSource::Projected { span, .. } => ("PROJECTED", *span),
                        TokenSource::Generated { anchor, .. } => (
                            "GENERATED",
                            Span {
                                start: *anchor,
                                end: *anchor,
                            },
                        ),
                    };
                    check(p.into(), e[9]);
                    check(span.start.to_string(), e[10]);
                    check(span.end.to_string(), e[11]);
                }
                if let Ok(matched) = result {
                    let text = matched
                        .nodes
                        .first()
                        .and_then(|n| c.token_node_id(*n))
                        .and_then(|id| c.token_text(id))
                        .unwrap_or("");
                    check(text.into(), &decode(e[12]));
                    check(matched.span.start.to_string(), e[13]);
                    check(matched.span.end.to_string(), e[14]);
                }
                check(roots.len().to_string(), e[15]);
                for node in roots {
                    assert!(
                        c.tree(node).is_some(),
                        "{label}: published root lost after rollback"
                    );
                }
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

use std::cell::Cell;
use unlaxer_runtime::token::NodeId;
thread_local! { static FRESH: Cell<Option<NodeId>> = const { Cell::new(None) }; }
fn mutates(c: &mut ParseContext<'_>) -> ParseResult {
    c.declare_complete_token_trace();
    c.set_state("value", 99u32);
    let old = *c.state::<NodeId>("old").unwrap();
    assert!(c.put_token_extra(old, "label", "during"));
    let matched =
        c.parse_with_token_mode(&cap(w("😀")), c.token_mode().kind, c.token_mode().invert)?;
    let id = c.token_node_id(matched.nodes[0]).unwrap();
    FRESH.with(|v| v.set(Some(id)));
    assert!(c.put_related_token(old, "new", id));
    if c.state::<String>("outcome").unwrap() == "exception" {
        Err(c.error("child callback"))
    } else {
        Ok(matched)
    }
}
#[test]
fn shared_owned_state_and_controlled_error_cleanup() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostic in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for row in include_str!("../../../conformance/owned-capture/state.tsv")
                .lines()
                .filter(|r| !r.starts_with('#') && !r.is_empty())
            {
                let e: Vec<_> = row.split('\t').collect();
                let mut c = ParseContext::with_options(
                    "p😀;",
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostic),
                );
                c.set_state("value", 7u32);
                c.set_state("outcome", e[2].to_owned());
                let old = c
                    .parse_with_token_mode(&cap(w("p")), TokenKind::Consumed, false)
                    .unwrap();
                let old = c.token_node_id(old.nodes[0]).unwrap();
                c.set_state("old", old);
                assert!(c.put_token_extra(old, "label", "before"));
                let mut child = Expr::Custom(mutates);
                if e[2] == "failure" {
                    child = Expr::sequence([child, w("z")]);
                } else if e[2] == "resource" {
                    let mut deep = w("x");
                    for _ in 0..1500 {
                        deep = deep.capture_scope();
                    }
                    child = Expr::sequence([child, deep]);
                }
                let parser = match e[1] {
                    "capture" => child.capture_owned("outer"),
                    "scope" => child.capture_scope(),
                    "match" => child.match_only(),
                    "peg" => child.ahead(),
                    _ => panic!("{}", e[1]),
                };
                FRESH.with(|v| v.set(None));
                let mut roots = vec![old.index()];
                let result = if e[2] == "rollback" {
                    c.transaction(|c| {
                        c.parse_with_token_mode(&parser, TokenKind::Consumed, false)?;
                        Err::<ParseMatch, _>(c.error("outer rollback"))
                    })
                } else {
                    c.parse_with_token_mode(&parser, TokenKind::Consumed, false)
                };
                if e[2] == "success" {
                    let matched = result.unwrap();
                    roots.extend(matched.nodes);
                } else {
                    assert!(result.is_err(), "{}", e[0]);
                }
                assert_eq!(c.position().to_string(), e[3], "{}", e[0]);
                assert_eq!(c.matched_position().to_string(), e[4], "{}", e[0]);
                assert_eq!(
                    c.state::<u32>("value").unwrap().to_string(),
                    e[5],
                    "{}",
                    e[0]
                );
                assert_eq!(
                    c.captured_text("value"),
                    Some(decode(e[6]).as_str()),
                    "{}",
                    e[0]
                );
                assert_eq!(
                    c.token_info(old).unwrap().extra("label"),
                    Some(e[7]),
                    "{}",
                    e[0]
                );
                let fresh = FRESH.with(Cell::get).unwrap();
                assert_eq!(c.token_info(fresh).is_some(), e[8] == "true", "{}", e[0]);
                assert_eq!(roots.len().to_string(), e[9], "{}", e[0]);
                if e[2] == "exception" {
                    c.parse_with_token_mode(&w("😀"), TokenKind::Consumed, false)
                        .unwrap();
                }
                if e[2] == "resource" {
                    assert!(c.parse(&Expr::Empty).is_err());
                }
                std::mem::forget(parser); // deeply nested hand-built AST destruction is outside execution budgets
            }
        }
    }
}

#[test]
fn legacy_ast_bindings_and_owned_values_are_separate_and_remain_owned() {
    let retained;
    {
        let mut c = ParseContext::new("abab");
        c.parse_with_token_mode(&w("a").capture("value"), TokenKind::Consumed, false)
            .unwrap();
        c.parse_with_token_mode(&cap(w("b")), TokenKind::Consumed, false)
            .unwrap();
        assert_eq!(c.captured("value"), Some("a"));
        assert_eq!(c.captured_text("value"), Some("b"));
        c.parse_with_token_mode(&Expr::Backreference("value"), TokenKind::Consumed, false)
            .unwrap();
        c.parse_with_token_mode(&replay(), TokenKind::Consumed, false)
            .unwrap();
        assert_eq!(c.position(), 4);
        assert_eq!(c.capture_spans("value"), [Span { start: 0, end: 1 }]);
        assert_eq!(
            c.capture_sources("value"),
            [TokenSource::Input(Span { start: 1, end: 2 })]
        );
    }
    {
        let mut c = ParseContext::new("abcbb");
        c.set_reset_matched_with_consumed(false);
        c.parse_with_token_mode(&w("a"), TokenKind::MatchOnly, false)
            .unwrap();
        c.parse_with_token_mode(
            &cap(Expr::sequence([w("b"), w("b")])),
            TokenKind::VirtualConsumed,
            false,
        )
        .unwrap();
        retained = c.captured_source("value").unwrap().clone();
        assert!(c
            .parse_with_token_mode(
                &Expr::sequence([cap(w("c")), w("z")]),
                TokenKind::Consumed,
                false
            )
            .is_err());
        assert_eq!(c.captured_source("value"), Some(&retained));
        parse(&mut c, &mut Vec::new(), w("c"));
        parse(&mut c, &mut Vec::new(), replay());
        assert_eq!(c.captured_text("value"), Some("bb"));
    }
    let mut other = ParseContext::new("different");
    parse(&mut other, &mut Vec::new(), cap(w("different")));
    assert_eq!(
        retained,
        TokenSource::Projected {
            span: Span { start: 1, end: 3 },
            text: std::sync::Arc::from("bb")
        }
    );
}

#[test]
fn nested_owned_scopes_respect_the_budget_on_a_two_mib_stack() {
    std::thread::Builder::new()
        .stack_size(2 * 1024 * 1024)
        .spawn(|| {
            for scoped in [false, true] {
                let mut expression = cap(w("x"));
                for _ in 0..1500 {
                    expression = if scoped {
                        expression.capture_scope()
                    } else {
                        cap(expression)
                    };
                }
                let mut c = ParseContext::new("x");
                let error = c
                    .parse_with_token_mode(&expression, TokenKind::Consumed, false)
                    .unwrap_err();
                assert!(error.expected.iter().any(|e| e.contains("nesting below")));
                assert_eq!((c.position(), c.matched_position()), (0, 0));
                assert!(c.captured_source("value").is_none());
                std::mem::forget(expression);
            }
        })
        .unwrap()
        .join()
        .unwrap();
}

#[test]
fn committed_terminal_trace_keeps_replay_positions_and_discards_failed_branches() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for row in include_str!("../../../conformance/owned-capture/trace.tsv")
            .lines()
            .filter(|r| !r.starts_with('#'))
        {
            let e: Vec<_> = row.split('\t').collect();
            let input = decode(e[0]);
            let options = ParseOptions::with_memoization(memo)
                .with_token_trace(unlaxer_runtime::token_trace::Limits::default());
            let mut c = ParseContext::with_options(&input, options);
            let _ = scenario(&mut c, &mut Vec::new(), e[1], TokenKind::Consumed, false);
            let actual = c
                .token_trace()
                .unwrap()
                .unwrap()
                .iter()
                .map(|leaf| {
                    let span = leaf.span();
                    let text = leaf.text(&input).unwrap().as_bytes().iter().fold(
                        String::new(),
                        |mut text, b| {
                            write!(text, "{b:02x}").unwrap();
                            text
                        },
                    );
                    let kind = match leaf.kind {
                        TokenKind::Consumed => "consumed",
                        TokenKind::MatchOnly => "matchOnly",
                        TokenKind::VirtualConsumed => "virtualTokenConsumed",
                        TokenKind::VirtualMatchOnly => "virtualTokenMatchOnly",
                    };
                    format!(
                        "{}@{}:{}:{text},{kind},{}:{}",
                        leaf.label, span.start, span.end, leaf.consumed, leaf.matched
                    )
                })
                .collect::<Vec<_>>()
                .join(";");
            assert_eq!(actual, e[2], "{}", e[1]);
        }
    }
}

#[test]
fn recovered_failure_does_not_leak_binding_and_outer_error_discards_recovery_events() {
    let mut c = ParseContext::new("a;");
    let parser = Expr::sequence([cap(w("a")), w("z")]).recover(
        unlaxer_runtime::RecoveryMode::Sync,
        [";"],
        "recover",
    );
    let result = c.transaction(|c| {
        c.parse_with_token_mode(&parser, TokenKind::Consumed, false)?;
        assert!(c.captured_text("value").is_none());
        assert_eq!(c.position(), 2);
        assert_eq!(c.recoveries().len(), 1);
        Err::<(), _>(c.error("outer failure"))
    });
    assert!(result.is_err());
    assert_eq!(c.position(), 0);
    assert!(c.recoveries().is_empty());
    assert!(c.captured_text("value").is_none());
}

#[test]
fn new_general_api_selects_classic_tokens_at_an_ordinary_entry() {
    let mut c = ParseContext::new("aa");
    let matched = c.parse(&Expr::sequence([cap(w("a")), replay()])).unwrap();
    assert_eq!(c.position(), 2);
    assert_eq!(c.captured_text("value"), Some("a"));
    let tree = c.tree(matched.nodes[0]).unwrap();
    let children = &tree.nodes[tree.root].children;
    assert_eq!(children.len(), 2);
    assert_eq!(tree.nodes[children[0]].span, Span { start: 0, end: 1 });
    assert_eq!(tree.nodes[children[1]].span, Span { start: 1, end: 2 });
}

#[test]
fn failed_replay_has_the_shared_name_and_code_point_diagnostic() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostic in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for row in include_str!("../../../conformance/owned-capture/failures.tsv")
                .lines()
                .filter(|r| !r.starts_with('#'))
            {
                let e: Vec<_> = row.split('\t').collect();
                let input = decode(e[0]);
                let mut c = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostic),
                );
                let error = scenario(&mut c, &mut Vec::new(), e[1], kind(e[2]), e[3] == "true")
                    .unwrap_err();
                let deferred = diagnostic == Diagnostics::DetailedOnFailure;
                assert_eq!(
                    error.offset,
                    if deferred { 0 } else { e[4].parse().unwrap() },
                    "{}",
                    e[1]
                );
                assert_eq!(
                    error.expected,
                    if deferred {
                        Vec::new()
                    } else {
                        vec![e[5].to_owned()]
                    },
                    "{}",
                    e[1]
                );
            }
        }
    }
}

#[test]
fn lookahead_capture_replays_matched_text_at_every_full_input_entry() {
    use unlaxer_runtime::{
        parse_detailed_shared_with_rule_stack, parse_detailed_with_options, Rule,
    };

    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for reset in [false, true] {
                for (input, prefix, pattern) in [
                    ("😀x", "😀", "x"),
                    ("a😀", "a", "😀"),
                    ("a\r\n😀", "a", "\r\n😀"),
                ] {
                    let options =
                        ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
                    let capture = Expr::JavaLookahead {
                        pattern,
                        positive: true,
                    }
                    .capture_owned("value");
                    let mut context = ParseContext::with_options(input, options);
                    context.set_reset_matched_with_consumed(reset);
                    context.parse(&Expr::literal(prefix)).unwrap();
                    context.parse(&capture).unwrap();
                    let start = prefix.chars().count();
                    let end = input.chars().count();
                    assert_eq!(
                        (context.position(), context.matched_position()),
                        (start, end)
                    );
                    assert_eq!(context.captured_text("value"), Some(pattern));
                    assert_eq!(
                        context.captured_source("value"),
                        Some(&TokenSource::Input(Span { start, end }))
                    );
                    context.parse(&Expr::Replay("value")).unwrap();
                    assert_eq!((context.position(), context.matched_position()), (end, end));

                    let rules = [Rule {
                        name: "R0",
                        expression: Expr::sequence([
                            Expr::literal(prefix),
                            capture,
                            Expr::Replay("value"),
                        ]),
                    }];
                    let tree =
                        parse_detailed_with_options(&rules, 0, false, input, options).unwrap();
                    assert_eq!(tree.nodes[tree.root].span, Span { start: 0, end });
                    // 全入力入口では Classic の meta node を増やさない。
                    assert_eq!(tree.nodes.len(), 1);
                    let shared = std::sync::Arc::from(rules);
                    assert!(parse_detailed_shared_with_rule_stack(
                        &shared, 0, false, input, options
                    )
                    .is_ok());
                }
            }
        }
    }
}

#[test]
fn selected_lookahead_source_survives_rule_success_memo_without_changing_cst() {
    use unlaxer_runtime::{parse_detailed_with_options, Rule};

    let rules = [
        Rule {
            name: "R0",
            expression: Expr::choice([
                Expr::sequence([Expr::Rule(1).capture_owned("discard"), Expr::literal("z")]),
                Expr::sequence([Expr::Rule(1).capture_owned("w"), Expr::Replay("w")]),
            ]),
        },
        Rule {
            name: "R1",
            expression: Expr::choice([
                Expr::JavaLookahead {
                    pattern: "x",
                    positive: true,
                },
                Expr::literal("q"),
            ]),
        },
    ];
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        let options = ParseOptions::with_memoization(memo);
        let mut context = ParseContext::with_options("x", options);
        context.parse_grammar(rules.to_vec(), 0, false).unwrap();
        assert_eq!(context.captured_text("w"), Some("x"));
        assert_eq!(context.position(), 1);
        if memo == Memoization::SafeFailures {
            assert!(context.memoized_success_hits() > 0);
        }
        let tree = parse_detailed_with_options(&rules, 0, false, "x", options).unwrap();
        assert_eq!(tree.nodes.len(), 2);
        assert_eq!(tree.nodes[0].span, Span { start: 0, end: 0 });
        assert_eq!(tree.nodes[tree.root].span, Span { start: 0, end: 1 });
    }
}
