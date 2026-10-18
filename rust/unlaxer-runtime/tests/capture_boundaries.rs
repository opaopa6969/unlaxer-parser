use unlaxer_runtime::{
    capture_tree::{self, Element, Kind},
    token::TokenKind,
    Diagnostics, Expr, Memoization, ParseContext, ParseOptions,
};
fn decode(s: &str) -> String {
    if s == "~" {
        return String::new();
    }
    String::from_utf8(
        s.as_bytes()
            .chunks_exact(2)
            .map(|p| u8::from_str_radix(std::str::from_utf8(p).unwrap(), 16).unwrap())
            .collect(),
    )
    .unwrap()
}
fn hex(s: &str) -> String {
    if s.is_empty() {
        "~".into()
    } else {
        {
            use std::fmt::Write;
            let mut result = String::new();
            for byte in s.bytes() {
                write!(result, "{byte:02x}").unwrap();
            }
            result
        }
    }
}
fn canonical(e: &Element) -> String {
    format!(
        "{}@{}:{}:{}[{}]",
        match e.kind() {
            Kind::Value => "V",
            Kind::Optional => "O",
            Kind::List => "L",
            Kind::Separator => unreachable!("separator is not a snapshot value"),
        },
        e.span().start,
        e.span().end,
        hex(e.text()),
        e.children()
            .iter()
            .map(canonical)
            .collect::<Vec<_>>()
            .join(",")
    )
}
fn group() -> Expr {
    Expr::CaptureBoundary(
        Kind::List,
        Box::new(Expr::sequence([
            Expr::literal("["),
            Expr::literal("a").capture_value().repeat_java(0, None),
            Expr::literal("]"),
        ])),
    )
}
fn generated(ctx: &mut ParseContext<'_>) -> unlaxer_runtime::ParseResult {
    ctx.declare_complete_token_trace();
    let at = ctx.position();
    let id = ctx
        .add_token_node(
            unlaxer_runtime::TOKEN_LEAF_RULE,
            TokenKind::VirtualConsumed,
            unlaxer_runtime::token::TokenSource::Generated {
                anchor: at,
                text: "x".into(),
            },
        )
        .unwrap();
    Ok(unlaxer_runtime::ParseMatch {
        span: unlaxer_runtime::Span { start: at, end: at },
        nodes: vec![id.index()],
        captures: vec![],
    })
}
fn parser(name: &str) -> Expr {
    let a = Expr::literal("a");
    match name {
        "inverse" => a.capture_value(),
        "inverse-missing" => a.capture_optional(),
        "stop-consume" => a.stop_consume().capture_value(),
        "projected" => Expr::sequence([a.clone().match_only(), a]).capture_value(),
        "trivia" => Expr::CaptureBoundary(
            Kind::List,
            Box::new(Expr::sequence([
                a.capture_value(),
                Expr::literal("b").capture_value(),
            ])),
        )
        .trivia_scope(true),
        "invalid-bounds" => a.capture_list(2, Some(1)),
        "generated" => Expr::Custom(generated).capture_value(),
        "optional" => a.capture_optional(),
        "empty-value" => Expr::Eof.capture_value(),
        "optional-list" => a.capture_list(0, None).capture_optional(),
        "list" => a.capture_list(0, None),
        "list-optional" => a.capture_optional().capture_list(0, None),
        "list-list" => a.capture_list(0, None).capture_list(0, None),
        "groups" => group().capture_list(0, None),
        "required-group" => group(),
        "unicode" => {
            Expr::choice([Expr::literal("😀"), Expr::literal("\r\n")]).capture_list(0, None)
        }
        "zero" => a.capture_list(0, Some(0)),
        "rollback" => Expr::CaptureBoundary(
            Kind::List,
            Box::new(Expr::choice([
                Expr::sequence([a.capture_list(0, None), Expr::literal("X")]),
                Expr::literal("ab").capture_value(),
            ])),
        ),
        _ => panic!("{name}"),
    }
}
#[test]
fn independent_nested_boundary_oracle() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
            Diagnostics::Auto,
        ] {
            for traced in [false, true] {
                for row in include_str!("../../../conformance/capture-boundaries/runtime.tsv")
                    .lines()
                    .filter(|s| !s.starts_with('#'))
                {
                    let c: Vec<_> = row.split('\t').collect();
                    let input = decode(c[2]);
                    let mut options =
                        ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
                    if traced {
                        options = options.with_token_trace(Default::default());
                    }
                    let mut ctx = ParseContext::with_options(&input, options);
                    if c[5] != "~" {
                        let prior = Box::leak(decode(c[5]).into_boxed_str());
                        ctx.parse(&Expr::literal(prior)).unwrap();
                    }
                    let kind = match c[3] {
                        "consumed" => TokenKind::Consumed,
                        "matchOnly" => TokenKind::MatchOnly,
                        "virtualTokenConsumed" => TokenKind::VirtualConsumed,
                        _ => TokenKind::VirtualMatchOnly,
                    };
                    let result = ctx.parse_with_token_mode(&parser(c[1]), kind, c[4] == "true");
                    assert_eq!(result.is_ok(), c[6] == "true", "{}", c[0]);
                    assert_eq!(
                        (ctx.position(), ctx.matched_position()),
                        (c[7].parse().unwrap(), c[8].parse().unwrap()),
                        "{}",
                        c[0]
                    );
                    if let Ok(result) = result {
                        let tree = ctx.tree(result.nodes[0]).unwrap();
                        let snapshot = capture_tree::snapshot(&tree, result.nodes[0]).unwrap();
                        assert_eq!(snapshot.token_kind(), kind);
                        assert_eq!(
                            snapshot.generated(),
                            c[1] == "generated" && kind == TokenKind::VirtualConsumed,
                            "{}",
                            c[0]
                        );
                        if traced {
                            let text = ctx
                                .token_trace()
                                .unwrap()
                                .unwrap()
                                .iter()
                                .map(|leaf| leaf.text(&input).unwrap())
                                .collect::<String>();
                            assert_eq!(
                                text,
                                format!("{}{}", decode(c[5]), snapshot.text()),
                                "trace {}",
                                c[0]
                            );
                        }
                        drop(tree);
                        drop(ctx);
                        assert_eq!(
                            canonical(&snapshot),
                            c[9],
                            "{} {memo:?} {diagnostics:?} trace={traced}",
                            c[0]
                        );
                    }
                }
            }
        }
    }
}

#[test]
fn owned_capture_lookahead_failure_and_snapshot_lifetime() {
    let mut ctx = ParseContext::new("ab");
    let first = ctx
        .parse_with_token_mode(
            &Expr::literal("a").capture_owned("old").capture_value(),
            TokenKind::Consumed,
            false,
        )
        .unwrap();
    let tree = ctx.tree(first.nodes[0]).unwrap();
    let before = capture_tree::snapshot(&tree, first.nodes[0]).unwrap();
    let next = Expr::literal("b").capture_owned("new").capture_value();
    assert!(ctx
        .parse_with_token_mode(
            &Expr::sequence([next.clone(), Expr::literal("missing")]),
            TokenKind::Consumed,
            false
        )
        .is_err());
    assert_eq!((ctx.position(), ctx.matched_position()), (1, 1));
    assert_eq!(ctx.captured_text("old"), Some("a"));
    assert_eq!(ctx.captured_text("new"), None);
    ctx.parse(&next.clone().ahead()).unwrap();
    assert_eq!((ctx.position(), ctx.matched_position()), (1, 1));
    assert_eq!(ctx.captured_text("new"), None);
    let last = ctx
        .parse_with_token_mode(&next, TokenKind::Consumed, false)
        .unwrap();
    assert_eq!(ctx.captured_text("new"), Some("b"));
    let last_tree = ctx.tree(last.nodes[0]).unwrap();
    let after = capture_tree::snapshot(&last_tree, last.nodes[0]).unwrap();
    ctx.parse_with_token_mode(
        &Expr::literal("ignored")
            .capture_owned("never")
            .capture_list(0, Some(0)),
        TokenKind::Consumed,
        false,
    )
    .unwrap();
    assert_eq!(ctx.captured_text("never"), None);
    drop(ctx);
    drop(tree);
    drop(last_tree);
    assert_eq!(canonical(&before), "V@0:1:61[]");
    assert_eq!(canonical(&after), "V@1:2:62[]");
}

#[test]
fn malformed_or_oversized_snapshots_fail_without_panics() {
    use capture_tree::Error;
    let mut bad = ParseContext::new("ab");
    let parsed = bad
        .parse(&Expr::CaptureBoundary(
            Kind::Optional,
            Box::new(Expr::sequence([
                Expr::literal("a").capture_value(),
                Expr::literal("b").capture_value(),
            ])),
        ))
        .unwrap();
    let bad = bad.tree(parsed.nodes[0]).unwrap();
    assert_eq!(
        capture_tree::snapshot(&bad, parsed.nodes[0]),
        Err(Error::InvalidTree)
    );
    let mut ctx = ParseContext::new("");
    let parsed = ctx.parse(&Expr::Eof.capture_list(0, Some(0))).unwrap();
    let mut tree = ctx.tree(parsed.nodes[0]).unwrap();
    assert_eq!(
        capture_tree::snapshot(&tree, usize::MAX),
        Err(Error::InvalidTree)
    );
    let mut bad_span = tree.clone();
    bad_span.nodes[parsed.nodes[0]].span.end = 1;
    assert_eq!(
        capture_tree::snapshot(&bad_span, parsed.nodes[0]),
        Err(Error::InvalidTree)
    );
    let prototype = tree.nodes[parsed.nodes[0]].clone();
    let mut root = parsed.nodes[0];
    for _ in 0..300 {
        let mut node = prototype.clone();
        node.children = vec![root];
        root = tree.nodes.len();
        tree.nodes.push(node);
    }
    assert_eq!(capture_tree::snapshot(&tree, root), Err(Error::Limit));
    let mut cyclic = ctx.tree(parsed.nodes[0]).unwrap();
    cyclic.nodes[parsed.nodes[0]].children = vec![parsed.nodes[0]];
    assert_eq!(
        capture_tree::snapshot(&cyclic, parsed.nodes[0]),
        Err(Error::Limit)
    );
    let text = Box::leak("a".repeat(2 * 1024 * 1024 + 1).into_boxed_str());
    let mut tiny_context = ParseContext::new(text);
    let tiny = tiny_context
        .parse(&Expr::literal("a").capture_value())
        .unwrap();
    let tiny_tree = tiny_context.tree(tiny.nodes[0]).unwrap();
    assert_eq!(
        canonical(&capture_tree::snapshot(&tiny_tree, tiny.nodes[0]).unwrap()),
        "V@0:1:61[]"
    );
    assert_eq!(
        capture_tree::snapshot(&tiny_tree, usize::MAX),
        Err(Error::InvalidTree)
    );
    let mut ctx = ParseContext::new(text);
    let parsed = ctx.parse(&Expr::literal(text).capture_value()).unwrap();
    let tree = ctx.tree(parsed.nodes[0]).unwrap();
    assert_eq!(
        capture_tree::snapshot(&tree, parsed.nodes[0]),
        Err(Error::Limit)
    );
}

#[test]
fn boundary_nesting_uses_existing_parser_resource_limits() {
    let mut expression = Expr::literal("a");
    for _ in 0..2000 {
        expression = expression.capture_optional();
    }
    let mut ctx = ParseContext::new("a");
    let error = ctx
        .parse_with_token_mode(&expression, TokenKind::Consumed, false)
        .unwrap_err();
    assert!(error.expected.iter().any(|s| s.contains("below")));
    assert_eq!((ctx.position(), ctx.matched_position()), (0, 0));
    assert!(ctx.parse(&Expr::literal("a")).is_err());
}

#[test]
fn memoized_failures_leave_no_boundary_and_preserve_diagnostics() {
    let grammar = unlaxer_runtime::share_grammar(vec![
        unlaxer_runtime::Rule {
            name: "root",
            expression: Expr::choice([Expr::Rule(1), Expr::Rule(1)]),
        },
        unlaxer_runtime::Rule {
            name: "boundary",
            expression: Expr::sequence([Expr::literal("a").capture_value(), Expr::literal("!")])
                .capture_value(),
        },
    ]);
    assert!(unlaxer_runtime::grammar_allows_deferred_diagnostics(
        &grammar
    ));
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            let mut context = ParseContext::with_options("a?", options);
            assert!(context.parse_shared_grammar(&grammar, 0, false).is_err());
            assert_eq!((context.position(), context.matched_position()), (0, 0));
            assert!(context.node(0).is_none());
            assert_eq!(
                context.memoized_failure_hits() > 0,
                memo == Memoization::SafeFailures
            );
            let diagnostic =
                unlaxer_runtime::parse_detailed_with_options(&grammar, 0, false, "a?", options)
                    .unwrap_err();
            assert_eq!(diagnostic.farthest.offset, 1);
            assert_eq!(diagnostic.farthest.expected, ["!"]);
            let parsed = context.parse(&Expr::literal("a").capture_value()).unwrap();
            let tree = context.tree(parsed.nodes[0]).unwrap();
            assert_eq!(
                canonical(&capture_tree::snapshot(&tree, parsed.nodes[0]).unwrap()),
                "V@0:1:61[]"
            );
        }
    }
}

#[test]
fn input_boundary_failure_reset_and_outer_rollback_match_shared_oracle() {
    let rows: Vec<_> = include_str!("../../../conformance/input-capture-boundary-reset/cases.tsv")
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
                let parser = Expr::InputCaptureBoundary(Kind::Value, Box::new(Expr::literal("a")));
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
                    "{label}\t{}\t{}\t{}\t{}\ttrue\t0\t0\t{}\ttrue",
                    f[4], f[5], f[6], f[7], f[8]
                ));
            }
        }
    }
    let output = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../target/input-capture-boundary-reset-rust.tsv");
    std::fs::create_dir_all(output.parent().unwrap()).unwrap();
    std::fs::write(output, actual.join("\n") + "\n").unwrap();
    assert_eq!(actual.len(), 48);
    assert_eq!(
        actual, expected,
        "all observations recorded before checking reset contract"
    );
}
